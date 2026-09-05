package dev.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.domain.tool.ToolAnnotations;
import dev.mcmcp.domain.tool.ToolDefinition;
import dev.mcmcp.observability.McmcpLogger;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed catalog of the 4 MCP tools. Loads schemas from classpath resources at startup.
 * The schema files are the single source of truth (IMPLEMENTATION.md §9.5).
 *
 * Tool order is stable and matches PRD §7.
 */
public final class ToolCatalog {

    public static final String EXECUTE_COMMAND = "minecraft_execute_command";
    public static final String GET_CHAT_MESSAGES = "minecraft_get_chat_messages";
    public static final String GET_GAME_STATE = "minecraft_get_game_state";
    public static final String CAPTURE_SCREENSHOT = "minecraft_capture_screenshot";

    private static final List<ToolDefinition> TOOLS = List.of(
        new ToolDefinition(
            EXECUTE_COMMAND,
            "Execute Minecraft Command",
            "Submit a slash command as the current player. Returns 'submitted', not 'succeeded'. Read chat messages for server feedback.",
            "/mcmcp/schema/execute-command-input.json",
            "/mcmcp/schema/execute-command-output.json",
            new ToolAnnotations(false, true, false, true)
        ),
        new ToolDefinition(
            GET_CHAT_MESSAGES,
            "Get Minecraft Chat Messages",
            "Read chat HUD messages captured during the current client session from a bounded in-memory buffer.",
            "/mcmcp/schema/get-chat-input.json",
            "/mcmcp/schema/get-chat-output.json",
            new ToolAnnotations(true, false, true, true)
        ),
        new ToolDefinition(
            GET_GAME_STATE,
            "Get Minecraft Game State",
            "Get a structured snapshot of client, connection, player, world, debug, and target state from the current client thread.",
            "/mcmcp/schema/get-game-state-input.json",
            "/mcmcp/schema/get-game-state-output.json",
            new ToolAnnotations(true, false, true, true)
        ),
        new ToolDefinition(
            CAPTURE_SCREENSHOT,
            "Capture Minecraft Screenshot",
            "Capture the current Minecraft window framebuffer as a PNG image. Only one screenshot at a time.",
            "/mcmcp/schema/capture-screenshot-input.json",
            "/mcmcp/schema/capture-screenshot-output.json",
            new ToolAnnotations(true, false, true, false)
        )
    );

    private static final Map<String, ToolDefinition> BY_NAME = buildByName();

    private static Map<String, ToolDefinition> buildByName() {
        Map<String, ToolDefinition> m = new LinkedHashMap<>();
        for (var t : TOOLS) m.put(t.name(), t);
        return Map.copyOf(m);
    }

    public static List<ToolDefinition> tools() {
        return TOOLS;
    }

    public static ToolDefinition get(String name) {
        return BY_NAME.get(name);
    }

    public static boolean exists(String name) {
        return BY_NAME.containsKey(name);
    }

    /**
     * Load a schema resource as a parsed JsonObject.
     */
    public static JsonObject loadSchema(String resourcePath) {
        try (InputStream is = ToolCatalog.class.getResourceAsStream(resourcePath)) {
            if (is == null)
                throw new IllegalStateException("schema resource not found: " + resourcePath);
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonElement el = JsonParser.parseReader(isr);
                if (!el.isJsonObject())
                    throw new IllegalStateException("schema must be object: " + resourcePath);
                return el.getAsJsonObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load schema " + resourcePath, e);
        }
    }

    /**
     * Build the tools/list JSON array with stable field order.
     */
    public static JsonArray buildToolsListJson() {
        JsonArray arr = new JsonArray();
        for (var def : TOOLS) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", def.name());
            tool.addProperty("title", def.title());
            tool.addProperty("description", def.description());
            tool.add("inputSchema", loadSchema(def.inputSchemaResource()));
            tool.add("outputSchema", loadSchema(def.outputSchemaResource()));

            JsonObject ann = new JsonObject();
            ann.addProperty("readOnlyHint", def.annotations().readOnlyHint());
            ann.addProperty("destructiveHint", def.annotations().destructiveHint());
            ann.addProperty("idempotentHint", def.annotations().idempotentHint());
            ann.addProperty("openWorldHint", def.annotations().openWorldHint());
            tool.add("annotations", ann);

            arr.add(tool);
        }
        return arr;
    }

    /**
     * Self-check: verify all schema resources are parseable at startup.
     */
    public static void selfCheck() {
        for (var def : TOOLS) {
            loadSchema(def.inputSchemaResource());
            loadSchema(def.outputSchemaResource());
        }
        McmcpLogger.info("tool_catalog_selfcheck", "tools", String.valueOf(TOOLS.size()));
    }
}
