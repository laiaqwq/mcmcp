package dev.mcmcp.application.tools;

import com.google.gson.JsonObject;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.application.ToolCallOutcome;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.protocol.JsonRpcCodec;
import dev.mcmcp.protocol.McpResponses;
import dev.mcmcp.util.TimeUtil;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for minecraft_execute_command.
 * See PRD §7.1 and IMPLEMENTATION.md §11.1.
 */
public final class ExecuteCommandHandler {

    private final MinecraftPorts.CommandPort commandPort;
    private final MinecraftPorts.LifecyclePort lifecyclePort;
    private final long defaultDeadlineNanos;

    public ExecuteCommandHandler(
        MinecraftPorts.CommandPort commandPort,
        MinecraftPorts.LifecyclePort lifecyclePort,
        long defaultDeadlineNanos
    ) {
        this.commandPort = commandPort;
        this.lifecyclePort = lifecyclePort;
        this.defaultDeadlineNanos = defaultDeadlineNanos;
    }

    public CompletableFuture<ToolCallOutcome> handle(JsonObject params, long deadlineNanos) {
        // Schema guarantees command field exists and is a string.
        String command = params.get("command").getAsString();

        // Business validation per PRD §7.1
        if (command == null || command.isEmpty())
            return CompletableFuture.completedFuture(ToolCallOutcome.error(
                ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "command must not be empty")));

        if (!command.startsWith("/"))
            return CompletableFuture.completedFuture(ToolCallOutcome.error(
                ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "command must start with '/'")));

        // Reject leading/trailing whitespace
        if (command.length() != command.trim().length())
            return CompletableFuture.completedFuture(ToolCallOutcome.error(
                ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "command must not have leading/trailing whitespace")));

        // Remove first '/'
        String withoutSlash = command.substring(1);

        // Length check: 1–256 Java chars
        if (withoutSlash.length() < 1 || withoutSlash.length() > 256)
            return CompletableFuture.completedFuture(ToolCallOutcome.error(
                ToolError.of(ToolErrorCode.INVALID_ARGUMENT,
                    "command length after removing '/' must be 1–256, got " + withoutSlash.length())));

        // Reject CR/LF/NUL, §, and control characters
        for (int i = 0; i < withoutSlash.length(); i++) {
            char c = withoutSlash.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0' || c == '\u00a7' || Character.isISOControl(c))
                return CompletableFuture.completedFuture(ToolCallOutcome.error(
                    ToolError.of(ToolErrorCode.INVALID_ARGUMENT,
                        "command contains illegal character at position " + i)));
        }

        long generation = lifecyclePort.currentGeneration();
        long deadline = deadlineNanos > 0 ? deadlineNanos : defaultDeadlineNanos;

        return commandPort.execute(withoutSlash, generation, deadline)
            .thenApply(result -> {
                if (result.isSuccess()) {
                    var r = result.value();
                    String json = JsonRpcCodec.encodeCommandResult(r).toString();
                    return ToolCallOutcome.success(json);
                } else {
                    return ToolCallOutcome.error(result.error());
                }
            });
    }
}
