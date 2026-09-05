package dev.mcmcp.observability;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured logger for MCMCP. Never logs command text, chat content, screenshot data,
 * or full state responses. Only logs metadata: request_id, method, tool, duration, result.
 */
public final class McmcpLogger {
    private static final Logger LOGGER = Logger.getLogger("mcmcp");

    public static void info(String event, String... kv) {
        LOGGER.log(Level.INFO, format(event, kv));
    }

    public static void warn(String event, String... kv) {
        LOGGER.log(Level.WARNING, format(event, kv));
    }

    public static void error(String event, String... kv) {
        LOGGER.log(Level.SEVERE, format(event, kv));
    }

    public static void debug(String event, String... kv) {
        LOGGER.log(Level.FINE, format(event, kv));
    }

    private static String format(String event, String... kv) {
        var sb = new StringBuilder();
        sb.append("event=").append(event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(' ').append(kv[i]).append('=').append(kv[i + 1]);
        }
        return sb.toString();
    }
}
