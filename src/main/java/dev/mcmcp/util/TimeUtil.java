package dev.mcmcp.util;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * UTC RFC 3339 timestamp utilities. All timestamps in MCMCP use this format.
 */
public final class TimeUtil {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    public static String nowUtc() {
        return FORMATTER.format(Instant.now());
    }

    public static String formatUtc(Instant instant) {
        return FORMATTER.format(instant);
    }

    public static Instant parseUtc(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid RFC 3339 timestamp: " + s, e);
        }
    }

    private TimeUtil() {}
}
