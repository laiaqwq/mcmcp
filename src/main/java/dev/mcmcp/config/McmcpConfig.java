package dev.mcmcp.config;

import java.nio.file.Path;

/**
 * Immutable MCMCP configuration. Loaded once at startup from {@code config/mcmcp.json}.
 * See PRD §10 and IMPLEMENTATION.md §12.
 */
public record McmcpConfig(
    boolean enabled,
    int port,
    int chatBufferSize,
    int requestTimeoutMs,
    int requestsPerSecond,
    int screenshotsPerSecond
) {
    public static final Path CONFIG_PATH = Path.of("config", "mcmcp.json");
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;
    public static final int MIN_CHAT_BUFFER = 100;
    public static final int MAX_CHAT_BUFFER = 10000;
    public static final int MIN_TIMEOUT_MS = 1000;
    public static final int MAX_TIMEOUT_MS = 30000;
    public static final int MIN_RPS = 1;
    public static final int MAX_RPS = 1000;
    public static final int MIN_SPS = 1;
    public static final int MAX_SPS = 10;

    public static McmcpConfig defaults() {
        return new McmcpConfig(true, 25585, 1000, 5000, 20, 1);
    }

    public void validate() {
        if (port < MIN_PORT || port > MAX_PORT)
            throw new ConfigException("port must be in [" + MIN_PORT + ", " + MAX_PORT + "], got " + port);
        if (chatBufferSize < MIN_CHAT_BUFFER || chatBufferSize > MAX_CHAT_BUFFER)
            throw new ConfigException("chat_buffer_size must be in [" + MIN_CHAT_BUFFER + ", " + MAX_CHAT_BUFFER + "], got " + chatBufferSize);
        if (requestTimeoutMs < MIN_TIMEOUT_MS || requestTimeoutMs > MAX_TIMEOUT_MS)
            throw new ConfigException("request_timeout_ms must be in [" + MIN_TIMEOUT_MS + ", " + MAX_TIMEOUT_MS + "], got " + requestTimeoutMs);
        if (requestsPerSecond < MIN_RPS || requestsPerSecond > MAX_RPS)
            throw new ConfigException("requests_per_second must be in [" + MIN_RPS + ", " + MAX_RPS + "], got " + requestsPerSecond);
        if (screenshotsPerSecond < MIN_SPS || screenshotsPerSecond > MAX_SPS)
            throw new ConfigException("screenshots_per_second must be in [" + MIN_SPS + ", " + MAX_SPS + "], got " + screenshotsPerSecond);
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
