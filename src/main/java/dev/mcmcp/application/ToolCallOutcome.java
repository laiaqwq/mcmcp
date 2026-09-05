package dev.mcmcp.application;

import dev.mcmcp.domain.error.ToolError;
import java.util.concurrent.CompletableFuture;

/**
 * Result of dispatching a tools/call request.
 * Either a JSON-serializable success value (as a Gson JsonObject string),
 * or a tool error.
 */
public record ToolCallOutcome(
    String structuredContentJson,
    byte[] imagePngBytes,
    ToolError error
) {
    public static ToolCallOutcome success(String json) {
        return new ToolCallOutcome(json, null, null);
    }

    public static ToolCallOutcome successWithImage(String json, byte[] png) {
        return new ToolCallOutcome(json, png, null);
    }

    public static ToolCallOutcome error(ToolError err) {
        return new ToolCallOutcome(null, null, err);
    }

    public boolean isError() { return error != null; }
    public boolean hasImage() { return imagePngBytes != null; }
}
