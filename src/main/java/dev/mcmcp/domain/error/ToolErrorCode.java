package dev.mcmcp.domain.error;

/**
 * Stable tool execution error codes as defined in PRD §8.
 * These codes are part of the public API and must not change within a major version.
 */
public enum ToolErrorCode {
    INVALID_ARGUMENT(false),
    GAME_NOT_READY(true),
    PLAYER_NOT_AVAILABLE(true),
    CONNECTION_NOT_AVAILABLE(true),
    COMMAND_REJECTED(false),
    SCREENSHOT_UNAVAILABLE(true),
    RATE_LIMITED(true),
    BUSY(true),
    TIMEOUT(true),
    INTERNAL_ERROR(null);

    private final Boolean retryable;

    ToolErrorCode(Boolean retryable) {
        this.retryable = retryable;
    }

    public Boolean retryable() {
        return retryable;
    }
}
