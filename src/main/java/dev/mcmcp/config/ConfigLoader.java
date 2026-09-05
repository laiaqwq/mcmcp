package dev.mcmcp.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import dev.mcmcp.observability.McmcpLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Loads {@link McmcpConfig} from {@code config/mcmcp.json}.
 * Fail-closed: any parse error, unknown field, or out-of-range value prevents
 * the HTTP service from starting. See IMPLEMENTATION.md §12, §7.1.
 */
public final class ConfigLoader {

    public static McmcpConfig load(Path path) {
        if (!Files.exists(path)) {
            McmcpLogger.info("config_not_found", "path", path.toString(), "action", "writing_defaults");
            writeDefault(path);
        }
        return readAndValidate(path);
    }

    public static McmcpConfig load() {
        return load(McmcpConfig.CONFIG_PATH);
    }

    private static void writeDefault(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Path tmp = Files.createTempFile(path.getParent(), "mcmcp-", ".tmp");
            try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.WRITE)) {
                os.write(DEFAULT_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            McmcpLogger.error("config_write_failed", "path", path.toString(), "error", e.getMessage());
        }
    }

    private static McmcpConfig readAndValidate(Path path) {
        try (InputStream is = Files.newInputStream(path);
             InputStreamReader isr = new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
             JsonReader reader = new JsonReader(isr)) {
            reader.setLenient(false);
            JsonElement el = JsonParser.parseReader(reader);
            if (!el.isJsonObject())
                throw new McmcpConfig.ConfigException("config must be a JSON object");
            JsonObject obj = el.getAsJsonObject();

            // Reject unknown fields
            for (String key : obj.keySet()) {
                if (!KNOWN_FIELDS.contains(key))
                    throw new McmcpConfig.ConfigException("unknown config field: " + key);
            }

            McmcpConfig config = new McmcpConfig(
                reqBool(obj, "enabled"),
                reqInt(obj, "port"),
                reqInt(obj, "chat_buffer_size"),
                reqInt(obj, "request_timeout_ms"),
                reqInt(obj, "requests_per_second"),
                reqInt(obj, "screenshots_per_second")
            );
            config.validate();
            return config;
        } catch (IOException e) {
            throw new McmcpConfig.ConfigException("cannot read config: " + e.getMessage());
        }
    }

    private static boolean reqBool(JsonObject obj, String key) {
        if (!obj.has(key)) throw new McmcpConfig.ConfigException("missing field: " + key);
        JsonPrimitive p = obj.getAsJsonPrimitive(key);
        if (p == null || !p.isBoolean())
            throw new McmcpConfig.ConfigException("field " + key + " must be boolean");
        return p.getAsBoolean();
    }

    private static int reqInt(JsonObject obj, String key) {
        if (!obj.has(key)) throw new McmcpConfig.ConfigException("missing field: " + key);
        JsonPrimitive p = obj.getAsJsonPrimitive(key);
        if (p == null || !p.isNumber())
            throw new McmcpConfig.ConfigException("field " + key + " must be integer");
        return p.getAsInt();
    }

    private static final java.util.Set<String> KNOWN_FIELDS = java.util.Set.of(
        "enabled", "port", "chat_buffer_size", "request_timeout_ms", "requests_per_second", "screenshots_per_second"
    );

    private static final String DEFAULT_JSON = """
        {
          "enabled": true,
          "port": 25585,
          "chat_buffer_size": 1000,
          "request_timeout_ms": 5000,
          "requests_per_second": 20,
          "screenshots_per_second": 1
        }
        """;
}
