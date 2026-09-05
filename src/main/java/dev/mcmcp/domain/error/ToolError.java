package dev.mcmcp.domain.error;

/**
 * Immutable tool execution error. Serialized as JSON text content in an MCP Tool Result
 * with isError=true. See PRD §8.
 */
public record ToolError(ToolErrorCode code, String message, Boolean retryable) {

    public static ToolError of(ToolErrorCode code, String message) {
        return new ToolError(code, message, code.retryable());
    }

    public static ToolError internal(String message) {
        return new ToolError(ToolErrorCode.INTERNAL_ERROR, message, null);
    }
}
