package dev.mcmcp.protocol;

import com.google.gson.JsonObject;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.domain.state.GameStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-JSON tests for {@link JsonRpcCodec}: exact key sets and values of the
 * encoded wire objects (field order is not significant).
 */
class JsonRpcCodecTest {

    private static void assertKeys(JsonObject o, String... keys) {
        assertEquals(Set.of(keys), o.keySet(), "unexpected key set in " + o);
    }

    // ---- Command result ----

    @Test
    void encodeCommandResultGolden() {
        JsonObject o = JsonRpcCodec.encodeCommandResult(
            new CommandResult("submitted", "/time set day", "2026-01-01T00:00:00Z"));

        assertKeys(o, "status", "command", "submitted_at");
        assertEquals("submitted", o.get("status").getAsString());
        assertEquals("/time set day", o.get("command").getAsString());
        assertEquals("2026-01-01T00:00:00Z", o.get("submitted_at").getAsString());
    }

    // ---- Chat result ----

    @Test
    void encodeChatResultGolden() {
        ChatMessage m1 = new ChatMessage(1, "sess-1", ChatMessage.MessageType.CHAT,
            "<Steve> hi", "2026-01-01T00:00:01Z", 12345L);
        ChatMessage m2 = new ChatMessage(2, "sess-1", ChatMessage.MessageType.SYSTEM,
            "Server started", "2026-01-01T00:00:02Z", null);
        ChatQueryResult r = new ChatQueryResult(
            List.of(m1, m2), 1L, 2L, 2L, false, false, 0);

        JsonObject o = JsonRpcCodec.encodeChatResult(r);

        assertKeys(o, "messages", "oldest_available_id", "newest_available_id",
            "next_after_id", "has_more", "gap_detected", "evicted_message_count");
        assertEquals(1, o.get("oldest_available_id").getAsLong());
        assertEquals(2, o.get("newest_available_id").getAsLong());
        assertEquals(2, o.get("next_after_id").getAsLong());
        assertFalse(o.get("has_more").getAsBoolean());
        assertFalse(o.get("gap_detected").getAsBoolean());
        assertEquals(0, o.get("evicted_message_count").getAsLong());

        var msgs = o.getAsJsonArray("messages");
        assertEquals(2, msgs.size());

        JsonObject jm1 = msgs.get(0).getAsJsonObject();
        assertKeys(jm1, "id", "session_id", "type", "plain_text", "received_at", "game_tick");
        assertEquals(1, jm1.get("id").getAsLong());
        assertEquals("sess-1", jm1.get("session_id").getAsString());
        assertEquals("chat", jm1.get("type").getAsString());
        assertEquals("<Steve> hi", jm1.get("plain_text").getAsString());
        assertEquals("2026-01-01T00:00:01Z", jm1.get("received_at").getAsString());
        assertEquals(12345L, jm1.get("game_tick").getAsLong());

        JsonObject jm2 = msgs.get(1).getAsJsonObject();
        assertEquals("system", jm2.get("type").getAsString());
        // game_tick is always present; null when unavailable.
        assertTrue(jm2.get("game_tick").isJsonNull());
    }

    @Test
    void encodeChatResultNullCursorFields() {
        ChatQueryResult r = new ChatQueryResult(
            List.of(), null, null, null, false, false, 3);
        JsonObject o = JsonRpcCodec.encodeChatResult(r);

        assertEquals(0, o.getAsJsonArray("messages").size());
        assertTrue(o.get("oldest_available_id").isJsonNull());
        assertTrue(o.get("newest_available_id").isJsonNull());
        assertTrue(o.get("next_after_id").isJsonNull());
        assertEquals(3, o.get("evicted_message_count").getAsLong());
    }

    // ---- Screenshot result ----

    @Test
    void encodeScreenshotResultGolden() {
        ScreenshotResult s = new ScreenshotResult(
            "2026-01-01T00:00:00Z", 1280, 720, "image/png", 2560, 1440, new byte[]{9});
        JsonObject o = JsonRpcCodec.encodeScreenshotResult(s);

        assertKeys(o, "captured_at", "width", "height", "mime_type", "source_width", "source_height");
        assertEquals("2026-01-01T00:00:00Z", o.get("captured_at").getAsString());
        assertEquals(1280, o.get("width").getAsInt());
        assertEquals(720, o.get("height").getAsInt());
        assertEquals("image/png", o.get("mime_type").getAsString());
        assertEquals(2560, o.get("source_width").getAsInt());
        assertEquals(1440, o.get("source_height").getAsInt());
    }

    // ---- Game state snapshot ----

    private static GameStateSnapshot minimalSnapshot() {
        return new GameStateSnapshot("2026-01-01T00:00:00Z", 999L, true,
            null, null, null, null, null, null);
    }

    @Test
    void encodeGameStateBaseFieldsOnly() {
        JsonObject o = JsonRpcCodec.encodeGameState(minimalSnapshot(), List.of());

        assertKeys(o, "captured_at", "game_tick", "ready");
        assertEquals("2026-01-01T00:00:00Z", o.get("captured_at").getAsString());
        assertEquals(999L, o.get("game_tick").getAsLong());
        assertTrue(o.get("ready").getAsBoolean());
    }

    @Test
    void encodeGameStateNullGameTick() {
        var snap = new GameStateSnapshot("t", null, false,
            null, null, null, null, null, null);
        JsonObject o = JsonRpcCodec.encodeGameState(snap, List.of());
        assertTrue(o.get("game_tick").isJsonNull());
        assertFalse(o.get("ready").getAsBoolean());
    }

    @Test
    void encodeGameStateUnknownSectionIgnored() {
        JsonObject o = JsonRpcCodec.encodeGameState(minimalSnapshot(), List.of("nonsense"));
        assertKeys(o, "captured_at", "game_tick", "ready");
    }

    @Test
    void encodeGameStateClientSectionGolden() {
        var client = new GameStateSnapshot.ClientSection(
            "1.21.4", "0.1.0", 60, "TitleScreen", false, true, 3);
        var snap = new GameStateSnapshot("t", 1L, true,
            client, null, null, null, null, null);

        JsonObject o = JsonRpcCodec.encodeGameState(snap, List.of("client"));

        assertKeys(o, "captured_at", "game_tick", "ready", "client");
        JsonObject c = o.getAsJsonObject("client");
        assertKeys(c, "minecraft_version", "mod_version", "fps", "screen",
            "paused", "window_focused", "gui_scale");
        assertEquals("1.21.4", c.get("minecraft_version").getAsString());
        assertEquals("0.1.0", c.get("mod_version").getAsString());
        assertEquals(60, c.get("fps").getAsInt());
        assertEquals("TitleScreen", c.get("screen").getAsString());
        assertFalse(c.get("paused").getAsBoolean());
        assertTrue(c.get("window_focused").getAsBoolean());
        assertEquals(3, c.get("gui_scale").getAsInt());
    }

    @Test
    void encodeGameStateClientSectionNullablesNull() {
        var client = new GameStateSnapshot.ClientSection(
            "1.21.4", "0.1.0", null, null, true, false, null);
        var snap = new GameStateSnapshot("t", 1L, true,
            client, null, null, null, null, null);

        JsonObject c = JsonRpcCodec.encodeGameState(snap, List.of("client"))
            .getAsJsonObject("client");
        // Nullable fields are present with JSON null rather than omitted.
        assertTrue(c.get("fps").isJsonNull());
        assertTrue(c.get("screen").isJsonNull());
        assertTrue(c.get("gui_scale").isJsonNull());
    }

    @Test
    void encodeGameStateConnectionSectionGolden() {
        var conn = new GameStateSnapshot.ConnectionSection(
            true, "multiplayer", "mc.example.com", 42);
        var snap = new GameStateSnapshot("t", 1L, true,
            null, conn, null, null, null, null);

        JsonObject c = JsonRpcCodec.encodeGameState(snap, List.of("connection"))
            .getAsJsonObject("connection");

        assertKeys(c, "connected", "type", "server_address", "latency_ms");
        assertTrue(c.get("connected").getAsBoolean());
        assertEquals("multiplayer", c.get("type").getAsString());
        assertEquals("mc.example.com", c.get("server_address").getAsString());
        assertEquals(42, c.get("latency_ms").getAsInt());
    }

    @Test
    void encodeGameStatePlayerSectionGolden() {
        var player = new GameStateSnapshot.PlayerSection(
            "Steve", "uuid-1", "survival",
            new GameStateSnapshot.Vec3(1.5, 64.0, -2.25),
            new GameStateSnapshot.Vec3i(1, 64, -3),
            new GameStateSnapshot.Rotation(90.0, -10.0),
            new GameStateSnapshot.Vec3(0.0, 0.0, 0.0),
            true, false, false, false, false,
            20.0, 20.0, 0.0, 20, 5.0, 300, 300, 0,
            3, 0.5, 0,
            new GameStateSnapshot.SelectedItem("minecraft:diamond_sword", 1, "Diamond Sword"),
            List.of(new GameStateSnapshot.StatusEffect(
                "minecraft:speed", 1, 200L, false, true)));
        var snap = new GameStateSnapshot("t", 1L, true,
            null, null, player, null, null, null);

        JsonObject p = JsonRpcCodec.encodeGameState(snap, List.of("player"))
            .getAsJsonObject("player");

        assertKeys(p, "name", "uuid", "game_mode", "position", "block_position",
            "rotation", "velocity", "on_ground", "sprinting", "sneaking", "swimming",
            "fall_flying", "health", "max_health", "absorption", "food", "saturation",
            "air", "max_air", "armor", "experience_level", "experience_progress",
            "selected_hotbar_slot", "selected_item", "status_effects");
        assertEquals("Steve", p.get("name").getAsString());
        assertEquals("survival", p.get("game_mode").getAsString());

        assertKeys(p.getAsJsonObject("position"), "x", "y", "z");
        assertEquals(1.5, p.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(-3, p.getAsJsonObject("block_position").get("z").getAsInt());
        assertKeys(p.getAsJsonObject("rotation"), "yaw", "pitch");

        JsonObject item = p.getAsJsonObject("selected_item");
        assertKeys(item, "id", "count", "display_name");
        assertEquals("minecraft:diamond_sword", item.get("id").getAsString());

        var effects = p.getAsJsonArray("status_effects");
        assertEquals(1, effects.size());
        JsonObject eff = effects.get(0).getAsJsonObject();
        assertKeys(eff, "id", "amplifier", "duration_ticks", "ambient", "show_particles");
        assertEquals("minecraft:speed", eff.get("id").getAsString());
        assertEquals(200L, eff.get("duration_ticks").getAsLong());
    }

    @Test
    void encodeGameStateWorldSectionGolden() {
        var world = new GameStateSnapshot.WorldSection(
            "minecraft:overworld", "minecraft:plains", "normal", false,
            10L, 6000L, false, true, new GameStateSnapshot.Light(4, 15));
        var snap = new GameStateSnapshot("t", 1L, true,
            null, null, null, world, null, null);

        JsonObject w = JsonRpcCodec.encodeGameState(snap, List.of("world"))
            .getAsJsonObject("world");

        assertKeys(w, "dimension", "biome", "difficulty", "hardcore", "day",
            "time_of_day", "raining", "thundering", "light");
        assertEquals("minecraft:overworld", w.get("dimension").getAsString());
        assertEquals(6000L, w.get("time_of_day").getAsLong());
        assertTrue(w.get("thundering").getAsBoolean());
        assertKeys(w.getAsJsonObject("light"), "block", "sky");
        assertEquals(4, w.getAsJsonObject("light").get("block").getAsInt());
        assertEquals(15, w.getAsJsonObject("light").get("sky").getAsInt());
    }

    @Test
    void encodeGameStateTargetSectionGolden() {
        var block = new GameStateSnapshot.BlockTarget(
            "minecraft:oak_log", new GameStateSnapshot.Vec3i(1, 64, -3),
            "up", Map.of("axis", "y"), List.of("minecraft:logs"));
        var target = new GameStateSnapshot.TargetSection("block", 2.5, block, null, null);
        var snap = new GameStateSnapshot("t", 1L, true,
            null, null, null, null, null, target);

        JsonObject t = JsonRpcCodec.encodeGameState(snap, List.of("target"))
            .getAsJsonObject("target");

        assertKeys(t, "type", "distance", "block", "fluid", "entity");
        assertEquals("block", t.get("type").getAsString());
        assertEquals(2.5, t.get("distance").getAsDouble());
        assertTrue(t.get("fluid").isJsonNull());
        assertTrue(t.get("entity").isJsonNull());

        JsonObject b = t.getAsJsonObject("block");
        assertKeys(b, "id", "position", "side", "properties", "tags");
        assertEquals("minecraft:oak_log", b.get("id").getAsString());
        assertEquals("y", b.getAsJsonObject("properties").get("axis").getAsString());
        assertEquals(1, b.getAsJsonArray("tags").size());
    }
}
