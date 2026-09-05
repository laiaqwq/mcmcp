package dev.mcmcp.transport.netty;

import dev.mcmcp.observability.Metrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits the number of in-flight requests globally. When the limit is exceeded,
 * returns HTTP 503 and closes the connection. See IMPLEMENTATION.md §8.2.
 */
final class InFlightGateHandler extends ChannelInboundHandlerAdapter {

    private final int maxInflight;
    private final AtomicInteger inflightCounter;
    private final Metrics metrics;
    private boolean counted = false;

    InFlightGateHandler(int maxInflight, AtomicInteger inflightCounter, Metrics metrics) {
        this.maxInflight = maxInflight;
        this.inflightCounter = inflightCounter;
        this.metrics = metrics;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest) {
            int current = inflightCounter.incrementAndGet();
            if (current > maxInflight) {
                inflightCounter.decrementAndGet();
                metrics.requestRejected();
                sendServiceUnavailable(ctx);
                ((FullHttpRequest) msg).release();
                return;
            }
            counted = true;
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (counted) {
            inflightCounter.decrementAndGet();
            counted = false;
        }
        ctx.fireChannelReadComplete();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (counted) {
            inflightCounter.decrementAndGet();
            counted = false;
        }
        ctx.fireChannelInactive();
    }

    private void sendServiceUnavailable(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE, Unpooled.EMPTY_BUFFER);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(response).addListener(f -> ctx.close());
    }
}
