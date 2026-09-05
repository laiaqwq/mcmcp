package dev.mcmcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.domain.state.GameStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests: validate real handler outputs (encoded via {@link JsonRpcCodec})
 * and representative request payloads against the JSON Schemas in
 * {@code src/main/resources/mcmcp/schema/} — the single source of truth.
 * Uses com.networknt:json-schema-validator (JSON Schema draft 2020-12).
 */
class SchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static JsonSchema schemaFor(String resourcePath) throws Exception {
        JsonObject schemaJson = ToolCatalog.loadSchema(resourcePath);
        return FACTORY.getSchema(MAPPER.readTree(schemaJson.toString()));
    }

    private static Set<ValidationMessage> validate(JsonSchema schema, String payloadJson) {
        try {
            JsonNode node = MAPPER.readTree(payloadJson);
            return schema.validate(node);
        } catch (Exception e) {
            throw new AssertionError("test payload is not valid JSON: " + payloadJson, e);
        }
    }

    private static Set<ValidationMessage> validate(JsonSchema schema, JsonObject gsonObj) {
        return validate(schema, gsonObj.toString());
    }

    private static void assertValid(JsonSchema schema, String payloadJson) {
        Set<ValidationMessage> errors = validate(schema, payloadJson);
        assertTrue(errors.isEmpty(), "expected valid payload, got: " + errors);
    }

    private static void assertValid(JsonSchema schema, JsonObject gsonObj) {
        Set<ValidationMessage> errors = validate(schema, gsonObj);
        assertTrue(errors.isEmpty(), "expected valid payload " + gsonObj + ", got: " + errors);
    }

    private static void assertInvalid(JsonSchema schema, String payloadJson) {
        Set<ValidationMessage> errors = validate(schema, payloadJson);
        assertFalse(errors.isEmpty(), "expected schema violations for " + payloadJson);
    }

    // ---- All schemas compile as draft 2020-12 ----

    @Test
    void allEightSchemasCompile() throws Exception {
        for (var def : ToolCatalog.tools()) {
            assertNotNull(schemaFor(def.inputSchemaResource()), def.inputSchemaResource());
            assertNotNull(schemaFor(def.outputSchemaResource()), def.outputSchemaResource());
        }
    }

    // ---- minecraft_execute_command ----

    @Test
    void executeCommandInputSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/execute-command-input.json");

        assertValid(schema, "{\"command\": \"/say hello\"}");
        assertValid(schema, "{\"command\": \"/\"}");

        // Missing required field
        assertInvalid(schema, "{}");
        // minLength violation
        assertInvalid(schema, "{\"command\": \"\"}");
        // Wrong type
        assertInvalid(schema, "{\"command\": 42}");
        // additionalProperties: false
        assertInvalid(schema, "{\"command\": \"/x\", \"extra\": true}");
    }

    @Test
    void executeCommandOutputMatchesSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/execute-command-output.json");

        // Real handler output produced by the codec.
        JsonObject encoded = JsonRpcCodec.encodeCommandResult(
            new CommandResult("submitted", "/time set day", "2026-01-01T00:00:00Z"));
        assertValid(schema, encoded);

        // McpResponses.commandStructured must produce the same contract.
        assertValid(schema, McpResponses.commandStructured("/say hi", "2026-01-01T00:00:00Z"));

        // status enum only allows "submitted"
        assertInvalid(schema,
            "{\"status\": \"executed\", \"command\": \"/x\", \"submitted_at\": \"t\"}");
        // Missing required field
        assertInvalid(schema, "{\"status\": \"submitted\", \"command\": \"/x\"}");
        // additionalProperties: false
        assertInvalid(schema,
            "{\"status\": \"submitted\", \"command\": \"/x\", \"submitted_at\": \"t\", \"x\": 1}");
    }

    // ---- minecraft_get_chat_messages ----

    @Test
    void getChatInputSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/get-chat-input.json");

        assertValid(schema, "{}");
        assertValid(schema, "{\"after_id\": 0, \"limit\": 50, \"types\": [\"chat\", \"system\"]}");
        assertValid(schema, "{\"types\": [\"chat\"]}");

        // limit bounds
        assertInvalid(schema, "{\"limit\": 0}");
        assertInvalid(schema, "{\"limit\": 201}");
        // after_id minimum
        assertInvalid(schema, "{\"after_id\": -1}");
        // types enum
        assertInvalid(schema, "{\"types\": [\"whisper\"]}");
        // uniqueItems
        assertInvalid(schema, "{\"types\": [\"chat\", \"chat\"]}");
        // additionalProperties: false
        assertInvalid(schema, "{\"bogus\": 1}");
    }

    @Test
    void getChatOutputMatchesSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/get-chat-output.json");

        ChatMessage m = new ChatMessage(7, "sess-1", ChatMessage.MessageType.CHAT,
            "<Alex> hi", "2026-01-01T00:00:00Z", 1234L);
        ChatQueryResult r = new ChatQueryResult(
            List.of(m), 1L, 7L, 7L, true, false, 2);
        assertValid(schema, JsonRpcCodec.encodeChatResult(r));

        // Null cursor fields and null game_tick are allowed.
        ChatMessage m2 = new ChatMessage(8, "sess-1", ChatMessage.MessageType.SYSTEM,
            "sys", "2026-01-01T00:00:01Z", null);
        ChatQueryResult r2 = new ChatQueryResult(List.of(m2), null, null, null, false, true, 0);
        assertValid(schema, JsonRpcCodec.encodeChatResult(r2));

        // Missing required top-level field
        assertInvalid(schema, "{\"messages\": []}");
        // Message type outside enum
        assertInvalid(schema, """
            {"messages": [{"id": 1, "session_id": "s", "type": "whisper",
              "plain_text": "x", "received_at": "t", "game_tick": 1}],
             "oldest_available_id": null, "newest_available_id": null,
             "next_after_id": null, "has_more": false, "gap_detected": false,
             "evicted_message_count": 0}
            """);
        // additionalProperties: false inside message items
        assertInvalid(schema, """
            {"messages": [{"id": 1, "session_id": "s", "type": "chat",
              "plain_text": "x", "received_at": "t", "game_tick": 1, "extra": 1}],
             "oldest_available_id": null, "newest_available_id": null,
             "next_after_id": null, "has_more": false, "gap_detected": false,
             "evicted_message_count": 0}
            """);
    }

    // ---- minecraft_get_game_state ----

    @Test
    void getGameStateInputSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/get-game-state-input.json");

        assertValid(schema, "{}");
        assertValid(schema,
            "{\"sections\": [\"client\", \"connection\", \"player\", \"world\", \"debug\", \"target\"]}");

        // Invalid section name
        assertInvalid(schema, "{\"sections\": [\"inventory\"]}");
        // uniqueItems
        assertInvalid(schema, "{\"sections\": [\"client\", \"client\"]}");
        // Wrong item type
        assertInvalid(schema, "{\"sections\": [1]}");
        // additionalProperties: false
        assertInvalid(schema, "{\"sections\": [], \"x\": 1}");
    }

    @Test
    void getGameStateOutputMatchesSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/get-game-state-output.json");

        var client = new GameStateSnapshot.ClientSection(
            "1.21.4", "0.1.0", 60, null, false, true, 3);
        var conn = new GameStateSnapshot.ConnectionSection(
            true, "multiplayer", "mc.example.com", 42);
        var snap = new GameStateSnapshot("2026-01-01T00:00:00Z", 999L, true,
            client, conn, null, null, null, null);

        assertValid(schema,
            JsonRpcCodec.encodeGameState(snap, List.of("client", "connection")));

        // Empty-sections output still satisfies the schema (required top-level fields only).
        var bare = new GameStateSnapshot("t", null, false,
            null, null, null, null, null, null);
        assertValid(schema, JsonRpcCodec.encodeGameState(bare, List.of()));

        // Missing required top-level field
        assertInvalid(schema, "{\"captured_at\": \"t\", \"game_tick\": 1}");
        // additionalProperties: false at top level
        assertInvalid(schema,
            "{\"captured_at\": \"t\", \"game_tick\": 1, \"ready\": true, \"bogus\": {}}");
        // connection.type enum violation
        assertInvalid(schema, """
            {"captured_at": "t", "game_tick": 1, "ready": true,
             "connection": {"connected": true, "type": "dialup",
               "server_address": null, "latency_ms": 5}}
            """);
        // client missing required field
        assertInvalid(schema, """
            {"captured_at": "t", "game_tick": 1, "ready": true,
             "client": {"minecraft_version": "1.21.4"}}
            """);
    }

    // ---- minecraft_capture_screenshot ----

    @Test
    void captureScreenshotInputSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/capture-screenshot-input.json");

        assertValid(schema, "{}");
        assertValid(schema, "{\"max_width\": 1}");
        assertValid(schema, "{\"max_width\": 3840}");

        assertInvalid(schema, "{\"max_width\": 0}");
        assertInvalid(schema, "{\"max_width\": 3841}");
        assertInvalid(schema, "{\"max_width\": \"1920\"}");
        assertInvalid(schema, "{\"max_width\": 100, \"extra\": true}");
    }

    @Test
    void captureScreenshotOutputMatchesSchema() throws Exception {
        JsonSchema schema = schemaFor("/mcmcp/schema/capture-screenshot-output.json");

        ScreenshotResult shot = new ScreenshotResult(
            "2026-01-01T00:00:00Z", 1280, 720, "image/png", 2560, 1440, new byte[0]);
        assertValid(schema, JsonRpcCodec.encodeScreenshotResult(shot));
        // The structured-content builder must satisfy the same contract.
        assertValid(schema, McpResponses.screenshotStructured(shot));

        // mime_type must be the const "image/png"
        assertInvalid(schema, """
            {"captured_at": "t", "width": 1, "height": 1,
             "mime_type": "image/jpeg", "source_width": 1, "source_height": 1}
            """);
        // Missing required field
        assertInvalid(schema,
            "{\"captured_at\": \"t\", \"width\": 1, \"height\": 1, \"mime_type\": \"image/png\"}");
        // additionalProperties: false
        assertInvalid(schema, """
            {"captured_at": "t", "width": 1, "height": 1, "mime_type": "image/png",
             "source_width": 1, "source_height": 1, "png_base64": "..."}
            """);
        // Non-positive dimensions
        assertInvalid(schema, """
            {"captured_at": "t", "width": 0, "height": 1, "mime_type": "image/png",
             "source_width": 1, "source_height": 1}
            """);
    }
}
