package dev.mcmcp.application;

import dev.mcmcp.application.tools.CaptureScreenshotHandler;
import dev.mcmcp.application.tools.ExecuteCommandHandler;
import dev.mcmcp.application.tools.GetChatMessagesHandler;
import dev.mcmcp.application.tools.GetGameStateHandler;
import dev.mcmcp.config.McmcpConfig;
import dev.mcmcp.protocol.ToolCatalog;
import dev.mcmcp.util.RateLimiter;
import java.util.concurrent.TimeUnit;

/**
 * Top-level application assembly. Wires ports to handlers and the dispatcher.
 * The transport layer uses this to dispatch requests.
 * See IMPLEMENTATION.md §4.1.
 */
public final class McpApplication {

    private final McmcpConfig config;
    private final String modVersion;
    private final ToolDispatcher dispatcher;
    private final RateLimiter globalRateLimiter;
    private final RateLimiter screenshotRateLimiter;

    public McpApplication(
        McmcpConfig config,
        String modVersion,
        MinecraftPorts.CommandPort commandPort,
        MinecraftPorts.ChatPort chatPort,
        MinecraftPorts.StatePort statePort,
        MinecraftPorts.ScreenshotPort screenshotPort,
        MinecraftPorts.LifecyclePort lifecyclePort
    ) {
        this.config = config;
        this.modVersion = modVersion;
        this.globalRateLimiter = new RateLimiter(config.requestsPerSecond());
        this.screenshotRateLimiter = new RateLimiter(config.screenshotsPerSecond());

        long deadlineNanos = TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs());

        var commandHandler = new ExecuteCommandHandler(commandPort, lifecyclePort, deadlineNanos);
        var chatHandler = new GetChatMessagesHandler(chatPort);
        var stateHandler = new GetGameStateHandler(statePort, deadlineNanos);
        var screenshotHandler = new CaptureScreenshotHandler(screenshotPort, deadlineNanos);

        this.dispatcher = new ToolDispatcher(
            modVersion, commandHandler, chatHandler, stateHandler, screenshotHandler,
            globalRateLimiter, screenshotRateLimiter
        );
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }

    public McmcpConfig config() {
        return config;
    }

    public String modVersion() {
        return modVersion;
    }

    public void selfCheck() {
        ToolCatalog.selfCheck();
    }
}
