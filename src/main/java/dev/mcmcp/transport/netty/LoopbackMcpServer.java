package dev.mcmcp.transport.netty;

import dev.mcmcp.application.ToolDispatcher;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.observability.Metrics;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback MCP HTTP server. Binds only to 127.0.0.1.
 * Uses independent Netty EventLoop groups (not the game's connection threads).
 * See IMPLEMENTATION.md §5, §8.
 */
public final class LoopbackMcpServer {

    public static final int MAX_CONNECTIONS = 32;
    public static final int MAX_INFLIGHT = 8;
    public static final int IDLE_TIMEOUT_SECONDS = 15;
    public static final int READ_TIMEOUT_SECONDS = 5;

    private final int port;
    private final ToolDispatcher dispatcher;
    private final Metrics metrics;
    private final long requestTimeoutNanos;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger inflightRequests = new AtomicInteger(0);

    public LoopbackMcpServer(int port, ToolDispatcher dispatcher, Metrics metrics, long requestTimeoutNanos) {
        this.port = port;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.requestTimeoutNanos = requestTimeoutNanos;
    }

    /**
     * Start the server. Returns true if binding succeeded.
     * Idempotent: calling start() when already running is a no-op.
     */
    public boolean start() {
        if (!running.compareAndSet(false, true)) return false;

        try {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(2);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 32)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Connection limit
                        if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                            activeConnections.decrementAndGet();
                            ch.close();
                            return;
                        }
                        ch.closeFuture().addListener(f -> activeConnections.decrementAndGet());

                        var gate = new HttpSecurityGate(port);
                        ch.pipeline()
                            .addLast("codec", new HttpServerCodec())
                            .addLast("headerGate", new HeaderGateHandler(gate))
                            .addLast("aggregator", new HttpObjectAggregator(HttpSecurityGate.MAX_BODY_BYTES))
                            .addLast("inflightGate", new InFlightGateHandler(MAX_INFLIGHT, inflightRequests, metrics))
                            .addLast("idle", new IdleStateHandler(READ_TIMEOUT_SECONDS, 0, IDLE_TIMEOUT_SECONDS))
                            .addLast("dispatch", new ProtocolDispatchHandler(dispatcher, metrics, requestTimeoutNanos));
                    }
                });

            // Bind to literal 127.0.0.1 only
            serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", port)).sync().channel();
            McmcpLogger.info("mcp_server_started",
                "address", "127.0.0.1", "port", String.valueOf(port),
                "auth", "none");
            return true;
        } catch (Exception e) {
            running.set(false);
            McmcpLogger.error("mcp_server_bind_failed",
                "port", String.valueOf(port), "error", e.getMessage());
            shutdownGroups();
            return false;
        }
    }

    /**
     * Stop the server. Idempotent.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (serverChannel != null) serverChannel.close().sync();
        } catch (InterruptedException ignored) {
        }
        shutdownGroups();
        McmcpLogger.info("mcp_server_stopped", "port", String.valueOf(port));
    }

    private void shutdownGroups() {
        if (workerGroup != null) workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        if (bossGroup != null) bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }
}
