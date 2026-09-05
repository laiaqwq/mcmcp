package dev.mcmcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConfigLoader}. The loader is fail-closed: it never silently
 * falls back to defaults — every problem results in a
 * {@link McmcpConfig.ConfigException} (or a wrapped JSON parse failure).
 */
class ConfigLoaderTest {

    private static final String VALID_JSON = """
        {
          "enabled": true,
          "port": 25585,
          "chat_buffer_size": 1000,
          "request_timeout_ms": 5000,
          "requests_per_second": 20,
          "screenshots_per_second": 1
        }
        """;

    // ---- 1. Default config creation ----

    @Test
    void createsDefaultFileWhenMissing(@TempDir Path dir) {
        Path path = dir.resolve("mcmcp.json");
        assertFalse(Files.exists(path));

        McmcpConfig config = ConfigLoader.load(path);

        assertEquals(McmcpConfig.defaults(), config);
        assertTrue(Files.exists(path), "default config file should be written");
    }

    @Test
    void createdDefaultFileIsParseableAndMatchesDefaults(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        ConfigLoader.load(path);

        String written = Files.readString(path);
        assertTrue(written.contains("\"port\""), "written file should contain real config keys");

        // Loading the file it just wrote must succeed and yield the defaults.
        McmcpConfig reloaded = ConfigLoader.load(path);
        assertEquals(McmcpConfig.defaults(), reloaded);
    }

    @Test
    void createsParentDirectoriesForDefaultFile(@TempDir Path dir) {
        Path path = dir.resolve("nested").resolve("deep").resolve("mcmcp.json");
        McmcpConfig config = ConfigLoader.load(path);
        assertEquals(McmcpConfig.defaults(), config);
        assertTrue(Files.exists(path));
    }

    @Test
    void doesNotOverwriteExistingFile(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("25585", "30000"));

        McmcpConfig config = ConfigLoader.load(path);

        assertEquals(30000, config.port());
        assertTrue(Files.readString(path).contains("30000"), "existing file must be left untouched");
    }

    @Test
    void loadsValidConfig(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("true", "false"));

        McmcpConfig config = ConfigLoader.load(path);

        assertFalse(config.enabled());
        assertEquals(25585, config.port());
        assertEquals(1000, config.chatBufferSize());
        assertEquals(5000, config.requestTimeoutMs());
        assertEquals(20, config.requestsPerSecond());
        assertEquals(1, config.screenshotsPerSecond());
    }

    // ---- 2. Unknown fields ----

    @Test
    void rejectsUnknownField(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("\"port\": 25585",
            "\"port\": 25585, \"bogus_field\": 42"));

        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("bogus_field"), "error should name the unknown field");
    }

    // ---- 3. Missing fields: actual behavior is ConfigException (all fields required) ----

    @Test
    void rejectsMissingField(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, """
            {
              "enabled": true,
              "port": 25585
            }
            """);

        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("missing field"), "error should report a missing field");
    }

    @Test
    void rejectsEmptyObject(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, "{}");
        assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
    }

    // ---- 4. Wrong-typed fields: actual behavior is ConfigException ----

    @Test
    void rejectsStringWhereIntExpected(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("\"port\": 25585", "\"port\": \"25585\""));
        assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void rejectsIntWhereBoolExpected(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("\"enabled\": true", "\"enabled\": 1"));
        assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void rejectsNullField(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("\"port\": 25585", "\"port\": null"));
        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("port"), "error should name the field");
        assertTrue(e.getMessage().contains("must be integer"), "error should report type");
    }

    @Test
    void rejectsOutOfRangeValue(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON.replace("25585", "80"));
        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("port"));
    }

    // ---- 5. Malformed / illegal JSON ----

    @Test
    void rejectsTruncatedJson(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, "{ \"enabled\": true, ");
        // Fail-closed: malformed JSON must never produce a config object.
        assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void rejectsNonObjectJson(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, "[1, 2, 3]");
        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("JSON object"));
    }

    @Test
    void rejectsScalarJson(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, "42");
        assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void rejectsTrailingContentAfterObject(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON + " { \"extra\": true }");
        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("trailing content"), "error should report trailing content");
    }

    @Test
    void acceptsTrailingWhitespaceAfterObject(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("mcmcp.json");
        Files.writeString(path, VALID_JSON + "\n\n  \n");
        assertDoesNotThrow(() -> ConfigLoader.load(path));
    }

    // ---- 6. IO failure ----

    @Test
    void failsWhenParentIsARegularFile(@TempDir Path dir) throws IOException {
        // Path cannot be created because a regular file blocks its parent dir.
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "not a dir");
        Path path = blocker.resolve("mcmcp.json");

        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(path));
        assertTrue(e.getMessage().contains("cannot read config"),
            "IO failures must surface as ConfigException, got: " + e.getMessage());
    }

    @Test
    void failsWhenPathIsADirectory(@TempDir Path dir) throws IOException {
        Path dir2 = Files.createDirectory(dir.resolve("configdir"));
        var e = assertThrows(McmcpConfig.ConfigException.class, () -> ConfigLoader.load(dir2));
        assertTrue(e.getMessage().contains("cannot read config") || e.getMessage().contains("parse config"),
            "IO failures must surface as ConfigException, got: " + e.getMessage());
    }
}
