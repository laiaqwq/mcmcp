package dev.mcmcp.client;

import dev.mcmcp.application.McpApplication;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.chat.BoundedChatBuffer;
import dev.mcmcp.config.ConfigLoader;
import dev.mcmcp.config.McmcpConfig;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.observability.Metrics;
import dev.mcmcp.transport.netty.LoopbackMcpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.concurrent.TimeUnit;

/**
 * MCMCP client entrypoint. Wires the application, starts the HTTP server,
 * and registers lifecycle/chat events. See IMPLEMENTATION.md §7.
 */
public final class McmcpClient implements ClientModInitializer {

    private static final String MOD_VERSION = "0.1.0";

    private McpApplication application;
    private LoopbackMcpServer server;
    private Metrics metrics;
    private BoundedChatBuffer chatBuffer;
    private ClientSessionTracker sessionTracker;
    private ChatListener chatListener;
    private boolean firstWorldPromptShown = false;

    @Override
    public void onInitializeClient() {
        McmcpLogger.info("mcmcp_init", "version", MOD_VERSION);

        // Load config (fail-closed)
        McmcpConfig config;
        try {
            config = ConfigLoader.load();
        } catch (Exception e) {
            McmcpLogger.error("config_load_failed", "error", e.getMessage());
            showInGameError("MCMCP config error: " + e.getMessage() + " — MCP service disabled");
            return;
        }

        if (!config.enabled()) {
            McmcpLogger.info("mcmcp_disabled", "reason", "config.enabled=false");
            return;
        }

        // Initialize infrastructure
        metrics = new Metrics();
        chatBuffer = new BoundedChatBuffer(config.chatBufferSize());
        sessionTracker = new ClientSessionTracker();

        // Scheduler using Minecraft's client thread executor
        long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs());
        MinecraftClientScheduler scheduler = new MinecraftClientScheduler(deadlineNanos);

        // Port implementations
        MinecraftPorts.CommandPort commandPort = new MinecraftCommandAdapter(scheduler, sessionTracker);
        MinecraftPorts.ChatPort chatPort = new MinecraftChatAdapter(chatBuffer);
        MinecraftPorts.StatePort statePort = new MinecraftStateAdapter(scheduler, sessionTracker, MOD_VERSION);
        MinecraftPorts.ScreenshotPort screenshotPort = new MinecraftScreenshotAdapter(scheduler);
        MinecraftPorts.LifecyclePort lifecyclePort = sessionTracker::currentGeneration;

        // Application assembly
        application = new McpApplication(
            config, MOD_VERSION, commandPort, chatPort, statePort, screenshotPort, lifecyclePort
        );
        application.selfCheck();

        // Chat listener
        chatListener = new ChatListener(chatBuffer, sessionTracker);
        chatListener.register();

        // Lifecycle events
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sessionTracker.onJoin();
            firstWorldPromptShown = false;
            showFirstWorldPrompt(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            sessionTracker.onDisconnect();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            stopServer();
        });

        // Start HTTP server after client started
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            startServer(config);
        });

        McmcpLogger.info("mcmcp_initialized",
            "port", String.valueOf(config.port()),
            "chat_buffer", String.valueOf(config.chatBufferSize()));
    }

    private void startServer(McmcpConfig config) {
        if (server != null && server.isRunning()) return;

        long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs());
        server = new LoopbackMcpServer(
            config.port(), application.dispatcher(), metrics, deadlineNanos
        );

        boolean started = server.start();
        if (!started) {
            showInGameError("MCMCP failed to bind port " + config.port() + " — MCP service unavailable");
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void showFirstWorldPrompt(Minecraft client) {
        if (firstWorldPromptShown || client == null || client.player == null) return;
        firstWorldPromptShown = true;
        client.player.sendSystemMessage(Component.literal(
            "MCMCP: local programs can read chat, state, screen, and submit commands via MCP"
        ));
    }

    private void showInGameError(String message) {
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(message));
            }
        } catch (Exception ignored) {}
    }
}
