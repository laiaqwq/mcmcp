package dev.mcmcp.application.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.application.ToolCallOutcome;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.protocol.JsonRpcCodec;
import dev.mcmcp.protocol.McpResponses;
import java.util.concurrent.CompletableFuture;

/**
 * Handler for minecraft_capture_screenshot.
 * See PRD §7.4 and IMPLEMENTATION.md §11.4.
 */
public final class CaptureScreenshotHandler {

    private final MinecraftPorts.ScreenshotPort screenshotPort;
    private final long defaultDeadlineNanos;

    public CaptureScreenshotHandler(
        MinecraftPorts.ScreenshotPort screenshotPort,
        long defaultDeadlineNanos
    ) {
        this.screenshotPort = screenshotPort;
        this.defaultDeadlineNanos = defaultDeadlineNanos;
    }

    public CompletableFuture<ToolCallOutcome> handle(JsonObject params, long deadlineNanos) {
        int maxWidth = 3840;
        if (params != null && params.has("max_width") && !params.get("max_width").isJsonNull()) {
            JsonElement maxWidthEl = params.get("max_width");
            if (!maxWidthEl.isJsonPrimitive() || !maxWidthEl.getAsJsonPrimitive().isNumber()) {
                return CompletableFuture.completedFuture(ToolCallOutcome.error(
                    ToolError.of(ToolErrorCode.INVALID_ARGUMENT, "max_width must be a number")));
            }
            maxWidth = maxWidthEl.getAsInt();
            if (maxWidth < 1 || maxWidth > 3840)
                return CompletableFuture.completedFuture(ToolCallOutcome.error(
                    ToolError.of(ToolErrorCode.INVALID_ARGUMENT,
                        "max_width must be 1–3840, got " + maxWidth)));
        }

        long deadline = deadlineNanos > 0 ? deadlineNanos : defaultDeadlineNanos;

        return screenshotPort.capture(maxWidth, deadline)
            .handle((result, throwable) -> {
                if (throwable != null) {
                    Throwable cause = throwable instanceof java.util.concurrent.CompletionException
                        && throwable.getCause() != null ? throwable.getCause() : throwable;
                    return ToolCallOutcome.error(ToolError.internal(
                        "screenshot port failed: " + cause.getMessage()));
                }
                if (result.isSuccess()) {
                    var shot = result.value();
                    String json = JsonRpcCodec.encodeScreenshotResult(shot).toString();
                    return ToolCallOutcome.successWithImage(json, shot.pngBytes());
                } else {
                    return ToolCallOutcome.error(result.error());
                }
            });
    }
}
