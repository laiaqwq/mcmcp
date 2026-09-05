package dev.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.state.GameStateSnapshot;
import java.util.List;

/**
 * Encodes domain DTOs into JSON for MCP responses.
 * Stable field ordering, UTF-8, no Gson reflection on Minecraft objects.
 * See IMPLEMENTATION.md §9.6.
 */
public final class JsonRpcCodec {

    // ---- Command result ----

    public static JsonObject encodeCommandResult(CommandResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("command", r.command());
        o.addProperty("submitted_at", r.submittedAt());
        return o;
    }

    // ---- Chat result ----

    public static JsonObject encodeChatResult(ChatQueryResult r) {
        JsonObject o = new JsonObject();
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : r.messages()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("id", m.id());
            msg.addProperty("session_id", m.sessionId());
            msg.addProperty("type", m.type().wireName());
            msg.addProperty("plain_text", m.plainText());
            msg.addProperty("received_at", m.receivedAt());
            if (m.gameTick() != null) {
                msg.addProperty("game_tick", m.gameTick());
            } else {
                msg.add("game_tick", com.google.gson.JsonNull.INSTANCE);
            }
            msgs.add(msg);
        }
        o.add("messages", msgs);
        addNullableLong(o, "oldest_available_id", r.oldestAvailableId());
        addNullableLong(o, "newest_available_id", r.newestAvailableId());
        addNullableLong(o, "next_after_id", r.nextAfterId());
        o.addProperty("has_more", r.hasMore());
        o.addProperty("gap_detected", r.gapDetected());
        o.addProperty("evicted_message_count", r.evictedMessageCount());
        return o;
    }

    // ---- Screenshot result ----

    public static JsonObject encodeScreenshotResult(dev.mcmcp.domain.screenshot.ScreenshotResult s) {
        JsonObject o = new JsonObject();
        o.addProperty("captured_at", s.capturedAt());
        o.addProperty("width", s.width());
        o.addProperty("height", s.height());
        o.addProperty("mime_type", s.mimeType());
        o.addProperty("source_width", s.sourceWidth());
        o.addProperty("source_height", s.sourceHeight());
        return o;
    }

    // ---- Game state snapshot ----

    public static JsonObject encodeGameState(GameStateSnapshot s, List<String> requestedSections) {
        JsonObject o = new JsonObject();
        o.addProperty("captured_at", s.capturedAt());
        addNullableLong(o, "game_tick", s.gameTick());
        o.addProperty("ready", s.ready());

        for (String section : requestedSections) {
            switch (section) {
                case "client" -> o.add("client", encodeClient(s.client()));
                case "connection" -> o.add("connection", encodeConnection(s.connection()));
                case "player" -> o.add("player", encodePlayer(s.player()));
                case "world" -> o.add("world", encodeWorld(s.world()));
                case "debug" -> o.add("debug", encodeDebug(s.debug()));
                case "target" -> o.add("target", encodeTarget(s.target()));
            }
        }
        return o;
    }

    private static JsonObject encodeClient(GameStateSnapshot.ClientSection c) {
        if (c == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("minecraft_version", c.minecraftVersion());
        o.addProperty("mod_version", c.modVersion());
        addNullableInt(o, "fps", c.fps());
        addNullableString(o, "screen", c.screen());
        o.addProperty("paused", c.paused());
        o.addProperty("window_focused", c.windowFocused());
        addNullableInt(o, "gui_scale", c.guiScale());
        return o;
    }

    private static JsonObject encodeConnection(GameStateSnapshot.ConnectionSection c) {
        if (c == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("connected", c.connected());
        addNullableString(o, "type", c.type());
        addNullableString(o, "server_address", c.serverAddress());
        addNullableInt(o, "latency_ms", c.latencyMs());
        return o;
    }

    private static JsonObject encodePlayer(GameStateSnapshot.PlayerSection p) {
        if (p == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name());
        o.addProperty("uuid", p.uuid());
        addNullableString(o, "game_mode", p.gameMode());
        o.add("position", vec3(p.position()));
        o.add("block_position", vec3i(p.blockPosition()));
        o.add("rotation", rotation(p.rotation()));
        o.add("velocity", vec3(p.velocity()));
        o.addProperty("on_ground", p.onGround());
        o.addProperty("sprinting", p.sprinting());
        o.addProperty("sneaking", p.sneaking());
        o.addProperty("swimming", p.swimming());
        o.addProperty("fall_flying", p.fallFlying());
        o.addProperty("health", p.health());
        o.addProperty("max_health", p.maxHealth());
        o.addProperty("absorption", p.absorption());
        o.addProperty("food", p.food());
        o.addProperty("saturation", p.saturation());
        o.addProperty("air", p.air());
        o.addProperty("max_air", p.maxAir());
        o.addProperty("armor", p.armor());
        o.addProperty("experience_level", p.experienceLevel());
        o.addProperty("experience_progress", p.experienceProgress());
        o.addProperty("selected_hotbar_slot", p.selectedHotbarSlot());
        if (p.selectedItem() != null) {
            JsonObject item = new JsonObject();
            item.addProperty("id", p.selectedItem().id());
            item.addProperty("count", p.selectedItem().count());
            item.addProperty("display_name", p.selectedItem().displayName());
            o.add("selected_item", item);
        } else {
            o.add("selected_item", com.google.gson.JsonNull.INSTANCE);
        }
        JsonArray effects = new JsonArray();
        if (p.statusEffects() != null) {
            for (var e : p.statusEffects()) {
                JsonObject eff = new JsonObject();
                eff.addProperty("id", e.id());
                eff.addProperty("amplifier", e.amplifier());
                addNullableLong(eff, "duration_ticks", e.durationTicks());
                eff.addProperty("ambient", e.ambient());
                eff.addProperty("show_particles", e.showParticles());
                effects.add(eff);
            }
        }
        o.add("status_effects", effects);
        return o;
    }

    private static JsonObject encodeWorld(GameStateSnapshot.WorldSection w) {
        if (w == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("dimension", w.dimension());
        addNullableString(o, "biome", w.biome());
        addNullableString(o, "difficulty", w.difficulty());
        o.addProperty("hardcore", w.hardcore());
        addNullableLong(o, "day", w.day());
        addNullableLong(o, "time_of_day", w.timeOfDay());
        o.addProperty("raining", w.raining());
        o.addProperty("thundering", w.thundering());
        JsonObject light = new JsonObject();
        light.addProperty("block", w.light().block());
        light.addProperty("sky", w.light().sky());
        o.add("light", light);
        return o;
    }

    private static JsonObject encodeDebug(GameStateSnapshot.DebugSection d) {
        if (d == null) return null;
        JsonObject o = new JsonObject();
        o.add("coordinates", encodeCoordinates(d.coordinates()));
        o.add("facing", encodeFacing(d.facing()));
        o.add("chunk", encodeChunk(d.chunk()));
        o.add("render", encodeRender(d.render()));
        o.add("system", encodeSystem(d.system()));
        return o;
    }

    private static JsonObject encodeCoordinates(GameStateSnapshot.Coordinates c) {
        JsonObject o = new JsonObject();
        o.add("precise", vec3(c.precise()));
        o.add("block", vec3i(c.block()));
        o.add("chunk", vec3i(c.chunk()));
        o.add("in_chunk", vec3i(c.inChunk()));
        o.add("chunk_origin", vec3i(c.chunkOrigin()));
        JsonObject r = new JsonObject();
        r.addProperty("x", c.region().x());
        r.addProperty("z", c.region().z());
        o.add("region", r);
        return o;
    }

    private static JsonObject encodeFacing(GameStateSnapshot.Facing f) {
        JsonObject o = new JsonObject();
        o.addProperty("direction", f.direction());
        o.addProperty("axis", f.axis());
        o.addProperty("towards", f.towards());
        o.addProperty("yaw", f.yaw());
        o.addProperty("pitch", f.pitch());
        return o;
    }

    private static JsonObject encodeChunk(GameStateSnapshot.ChunkInfo c) {
        JsonObject o = new JsonObject();
        o.addProperty("loaded", c.loaded());
        addNullableString(o, "status", c.status());
        addNullableDouble(o, "local_difficulty", c.localDifficulty());
        addNullableLong(o, "inhabited_time_ticks", c.inhabitedTimeTicks());
        if (c.heightmaps() != null) {
            JsonObject hm = new JsonObject();
            addNullableInt(hm, "world_surface", c.heightmaps().worldSurface());
            addNullableInt(hm, "motion_blocking", c.heightmaps().motionBlocking());
            o.add("heightmaps", hm);
        } else {
            o.add("heightmaps", com.google.gson.JsonNull.INSTANCE);
        }
        return o;
    }

    private static JsonObject encodeRender(GameStateSnapshot.RenderInfo r) {
        JsonObject o = new JsonObject();
        addNullableInt(o, "render_distance_chunks", r.renderDistanceChunks());
        addNullableInt(o, "simulation_distance_chunks", r.simulationDistanceChunks());
        addNullableInt(o, "fps", r.fps());
        addNullableDouble(o, "frame_time_ms", r.frameTimeMs());
        addNullableInt(o, "chunks_rendered", r.chunksRendered());
        addNullableInt(o, "entities_rendered", r.entitiesRendered());
        addNullableInt(o, "entities_loaded", r.entitiesLoaded());
        addNullableInt(o, "particles", r.particles());
        return o;
    }

    private static JsonObject encodeSystem(GameStateSnapshot.SystemInfo s) {
        JsonObject o = new JsonObject();
        o.addProperty("java_version", s.javaVersion());
        addNullableLong(o, "memory_used_mib", s.memoryUsedMiB());
        addNullableLong(o, "memory_allocated_mib", s.memoryAllocatedMiB());
        addNullableLong(o, "memory_max_mib", s.memoryMaxMiB());
        addNullableString(o, "cpu", s.cpu());
        addNullableString(o, "gpu", s.gpu());
        addNullableInt(o, "display_width", s.displayWidth());
        addNullableInt(o, "display_height", s.displayHeight());
        addNullableString(o, "graphics_backend", s.graphicsBackend());
        return o;
    }

    private static JsonObject encodeTarget(GameStateSnapshot.TargetSection t) {
        JsonObject o = new JsonObject();
        o.addProperty("type", t.type());
        if (t.distance() != null) {
            o.addProperty("distance", t.distance());
        } else {
            o.add("distance", com.google.gson.JsonNull.INSTANCE);
        }
        if (t.block() != null) {
            JsonObject b = new JsonObject();
            b.addProperty("id", t.block().id());
            b.add("position", vec3i(t.block().position()));
            b.addProperty("side", t.block().side());
            b.add("properties", McpResponses.toJsonObject(t.block().properties()));
            JsonArray tags = new JsonArray();
            for (String tag : t.block().tags()) tags.add(tag);
            b.add("tags", tags);
            o.add("block", b);
        } else {
            o.add("block", com.google.gson.JsonNull.INSTANCE);
        }
        if (t.fluid() != null) {
            JsonObject f = new JsonObject();
            f.addProperty("id", t.fluid().id());
            f.add("properties", McpResponses.toJsonObject(t.fluid().properties()));
            JsonArray tags = new JsonArray();
            for (String tag : t.fluid().tags()) tags.add(tag);
            f.add("tags", tags);
            o.add("fluid", f);
        } else {
            o.add("fluid", com.google.gson.JsonNull.INSTANCE);
        }
        if (t.entity() != null) {
            JsonObject e = new JsonObject();
            e.addProperty("id", t.entity().id());
            e.addProperty("type", t.entity().type());
            e.addProperty("display_name", t.entity().displayName());
            e.addProperty("uuid", t.entity().uuid());
            e.add("position", vec3(t.entity().position()));
            e.addProperty("distance", t.entity().distance());
            o.add("entity", e);
        } else {
            o.add("entity", com.google.gson.JsonNull.INSTANCE);
        }
        return o;
    }

    // ---- Helpers ----

    private static JsonObject vec3(GameStateSnapshot.Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x());
        o.addProperty("y", v.y());
        o.addProperty("z", v.z());
        return o;
    }

    private static JsonObject vec3i(GameStateSnapshot.Vec3i v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x());
        o.addProperty("y", v.y());
        o.addProperty("z", v.z());
        return o;
    }

    private static JsonObject rotation(GameStateSnapshot.Rotation r) {
        JsonObject o = new JsonObject();
        o.addProperty("yaw", r.yaw());
        o.addProperty("pitch", r.pitch());
        return o;
    }

    private static void addNullableString(JsonObject o, String key, String val) {
        if (val != null) o.addProperty(key, val);
        else o.add(key, com.google.gson.JsonNull.INSTANCE);
    }

    private static void addNullableInt(JsonObject o, String key, Integer val) {
        if (val != null) o.addProperty(key, val);
        else o.add(key, com.google.gson.JsonNull.INSTANCE);
    }

    private static void addNullableLong(JsonObject o, String key, Long val) {
        if (val != null) o.addProperty(key, val);
        else o.add(key, com.google.gson.JsonNull.INSTANCE);
    }

    private static void addNullableDouble(JsonObject o, String key, Double val) {
        if (val != null) o.addProperty(key, val);
        else o.add(key, com.google.gson.JsonNull.INSTANCE);
    }

    private JsonRpcCodec() {}
}
