package dev.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-JSON tests for {@link McpResponses} and {@link JsonRpcErrors}.
 * Assert the exact key set and values of the serialized wire format
 * (field order is not significant).
 */
class McpResponsesTest {

    private static void assertKeys(JsonObject o, String... keys) {
        assertEquals(Set.of(keys), o.keySet(), "unexpected key set in " + o);
    }

    // ---- success / error envelopes ----

    @Test
    void successEnvelopeShape() {
        JsonObject result = new JsonObject();
        result.addProperty("x", 1);
        JsonObject resp = McpResponses.success(new JsonPrimitive(7), result);

        assertKeys(resp, "jsonrpc", "id", "result");
        assertEquals("2.0", resp.get("jsonrpc").getAsString());
        assertEquals(7, resp.get("id").getAsInt());
        assertSame(result, resp.getAsJsonObject("result"));
    }

    @Test
    void successEnvelopeSupportsStringAndNullIds() {
        JsonObject r1 = McpResponses.success(new JsonPrimitive("abc"), new JsonObject());
        assertEquals("abc", r1.get("id").getAsString());

        JsonObject r2 = McpResponses.success(JsonNull.INSTANCE, new JsonObject());
        assertTrue(r2.get("id").isJsonNull());
    }

    @Test
    void errorEnvelopeWithoutData() {
        JsonObject resp = McpResponses.error(new JsonPrimitive(1),
            JsonRpcErrors.METHOD_NOT_FOUND, "method not found");

        assertKeys(resp, "jsonrpc", "id", "error");
        assertEquals("2.0", resp.get("jsonrpc").getAsString());
        JsonObject err = resp.getAsJsonObject("error");
        assertKeys(err, "code", "message");
        assertEquals(-32601, err.get("code").getAsInt());
        assertEquals("method not found", err.get("message").getAsString());
    }

    @Test
    void errorEnvelopeWithData() {
        JsonObject data = new JsonObject();
        data.addProperty("detail", "bad");
        JsonObject resp = McpResponses.error(new JsonPrimitive(2),
            JsonRpcErrors.INVALID_PARAMS, "invalid params", data);

        JsonObject err = resp.getAsJsonObject("error");
        assertKeys(err, "code", "message", "data");
        assertEquals(-32602, err.get("code").getAsInt());
        assertEquals("bad", err.getAsJsonObject("data").get("detail").getAsString());
    }

    @Test
    void errorNoIdUsesNullId() {
        JsonObject resp = McpResponses.errorNoId(JsonRpcErrors.PARSE_ERROR, "parse error");
        assertTrue(resp.get("id").isJsonNull());
        assertEquals(-32700, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void errorNoIdCarriesData() {
        JsonObject data = new JsonObject();
        data.addProperty("detail", "extra");
        JsonObject resp = McpResponses.errorNoId(
            JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION, "unsupported", data);
        assertTrue(resp.get("id").isJsonNull());
        JsonObject err = resp.getAsJsonObject("error");
        assertKeys(err, "code", "message", "data");
        assertEquals("extra", err.getAsJsonObject("data").get("detail").getAsString());
    }

    @Test
    void jsonRpcErrorCodeValues() {
        assertEquals(-32700, JsonRpcErrors.PARSE_ERROR);
        assertEquals(-32600, JsonRpcErrors.INVALID_REQUEST);
        assertEquals(-32601, JsonRpcErrors.METHOD_NOT_FOUND);
        assertEquals(-32602, JsonRpcErrors.INVALID_PARAMS);
        assertEquals(-32603, JsonRpcErrors.INTERNAL_ERROR);
        assertEquals(-32020, JsonRpcErrors.HEADER_MISMATCH);
        assertEquals(-32000, JsonRpcErrors.RATE_LIMITED);
        assertEquals(-32021, JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION);
        assertEquals(-32003, JsonRpcErrors.REQUEST_TIMEOUT);
    }

    // ---- initialize / discover results ----

    @Test
    void initializeResultGolden() {
        JsonObject result = McpResponses.initializeResult("1.2.3", "2026-07-28");

        assertKeys(result, "protocolVersion", "capabilities", "serverInfo", "instructions");
        assertEquals("2026-07-28", result.get("protocolVersion").getAsString());

        JsonObject caps = result.getAsJsonObject("capabilities");
        assertKeys(caps, "tools");
        assertKeys(caps.getAsJsonObject("tools"), "listChanged");
        assertFalse(caps.getAsJsonObject("tools").get("listChanged").getAsBoolean());

        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertKeys(serverInfo, "name", "version");
        assertEquals("mcmcp", serverInfo.get("name").getAsString());
        assertEquals("1.2.3", serverInfo.get("version").getAsString());

        assertTrue(result.get("instructions").getAsString().contains("MCMCP"));
    }

    @Test
    void discoverResultGolden() {
        JsonObject result = McpResponses.discoverResult("9.9.9");

        assertKeys(result, "resultType", "ttlMs", "cacheScope", "supportedVersions",
            "capabilities", "_meta", "instructions");
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals(3600000L, result.get("ttlMs").getAsLong());
        assertEquals("public", result.get("cacheScope").getAsString());
        assertEquals(List.of("2026-07-28"),
            result.getAsJsonArray("supportedVersions").asList().stream()
                .map(e -> e.getAsString()).toList());

        JsonObject meta = result.getAsJsonObject("_meta");
        assertKeys(meta, "io.modelcontextprotocol/serverInfo");
        JsonObject serverInfo = meta.getAsJsonObject("io.modelcontextprotocol/serverInfo");
        assertKeys(serverInfo, "name", "version");
        assertEquals("mcmcp", serverInfo.get("name").getAsString());
        assertEquals("9.9.9", serverInfo.get("version").getAsString());
    }

    // ---- tools/list result ----

    @Test
    void toolsListResultShape() {
        JsonArray tools = ToolCatalog.buildToolsListJson();
        JsonObject result = McpResponses.toolsListResult(tools);

        assertKeys(result, "resultType", "ttlMs", "cacheScope", "tools");
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals(3600000L, result.get("ttlMs").getAsLong());
        assertEquals("public", result.get("cacheScope").getAsString());
        assertSame(tools, result.getAsJsonArray("tools"));
    }

    @Test
    void toolsListEntriesHaveExpectedShape() {
        JsonArray tools = ToolCatalog.buildToolsListJson();
        assertEquals(4, tools.size());
        for (var el : tools) {
            JsonObject tool = el.getAsJsonObject();
            assertKeys(tool, "name", "title", "description", "inputSchema", "outputSchema", "annotations");
            assertTrue(tool.get("inputSchema").isJsonObject());
            assertTrue(tool.get("outputSchema").isJsonObject());
            assertKeys(tool.getAsJsonObject("annotations"),
                "readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint");
        }
    }

    // ---- tools/call results ----

    @Test
    void toolSuccessShape() {
        JsonObject structured = new JsonObject();
        structured.addProperty("status", "submitted");
        JsonArray content = new JsonArray();
        content.add(McpResponses.textContent("ok"));

        JsonObject resp = McpResponses.toolSuccess(new JsonPrimitive(5), structured, content);

        assertKeys(resp, "jsonrpc", "id", "result");
        JsonObject result = resp.getAsJsonObject("result");
        assertKeys(result, "resultType", "content", "structuredContent", "isError");
        assertEquals("complete", result.get("resultType").getAsString());
        assertFalse(result.get("isError").getAsBoolean());
        assertSame(structured, result.getAsJsonObject("structuredContent"));
        assertSame(content, result.getAsJsonArray("content"));
    }

    @Test
    void toolErrorShape() {
        ToolError err = new ToolError(ToolErrorCode.RATE_LIMITED, "slow down", true);
        JsonObject resp = McpResponses.toolError(new JsonPrimitive(3), err);

        assertKeys(resp, "jsonrpc", "id", "result");
        JsonObject result = resp.getAsJsonObject("result");
        // Error results must NOT include structuredContent (PRD §8).
        assertKeys(result, "resultType", "content", "isError");
        assertTrue(result.get("isError").getAsBoolean());

        JsonArray content = result.getAsJsonArray("content");
        assertEquals(1, content.size());
        JsonObject text = content.get(0).getAsJsonObject();
        assertKeys(text, "type", "text");
        assertEquals("text", text.get("type").getAsString());

        // The text is itself serialized JSON describing the tool error.
        JsonObject errJson = JsonParser.parseString(text.get("text").getAsString()).getAsJsonObject();
        assertKeys(errJson, "code", "message", "retryable");
        assertEquals("RATE_LIMITED", errJson.get("code").getAsString());
        assertEquals("slow down", errJson.get("message").getAsString());
        assertTrue(errJson.get("retryable").getAsBoolean());
    }

    @Test
    void toolErrorOmitsRetryableWhenNull() {
        ToolError err = ToolError.internal("boom");
        JsonObject resp = McpResponses.toolError(new JsonPrimitive(1), err);
        JsonObject text = resp.getAsJsonObject("result").getAsJsonArray("content")
            .get(0).getAsJsonObject();
        JsonObject errJson = JsonParser.parseString(text.get("text").getAsString()).getAsJsonObject();
        assertKeys(errJson, "code", "message");
        assertEquals("INTERNAL_ERROR", errJson.get("code").getAsString());
    }

    // ---- content block builders ----

    @Test
    void textContentShape() {
        JsonObject c = McpResponses.textContent("hello");
        assertKeys(c, "type", "text");
        assertEquals("text", c.get("type").getAsString());
        assertEquals("hello", c.get("text").getAsString());
    }

    @Test
    void imageContentShape() {
        JsonObject c = McpResponses.imageContent(new byte[]{1, 2, 3});
        assertKeys(c, "type", "mimeType", "data");
        assertEquals("image", c.get("type").getAsString());
        assertEquals("image/png", c.get("mimeType").getAsString());
        assertEquals("AQID", c.get("data").getAsString());
    }

    // ---- structured content builders ----

    @Test
    void commandStructuredShape() {
        JsonObject o = McpResponses.commandStructured("/say hi", "2026-01-01T00:00:00Z");
        assertKeys(o, "status", "command", "submitted_at");
        assertEquals("submitted", o.get("status").getAsString());
        assertEquals("/say hi", o.get("command").getAsString());
        assertEquals("2026-01-01T00:00:00Z", o.get("submitted_at").getAsString());
    }

    @Test
    void screenshotStructuredShape() {
        ScreenshotResult shot = new ScreenshotResult(
            "2026-01-01T00:00:00Z", 640, 480, "image/png", 1920, 1080, new byte[0]);
        JsonObject o = McpResponses.screenshotStructured(shot);
        assertKeys(o, "captured_at", "width", "height", "mime_type", "source_width", "source_height");
        assertEquals(640, o.get("width").getAsInt());
        assertEquals("image/png", o.get("mime_type").getAsString());
        assertEquals(1920, o.get("source_width").getAsInt());
    }

    // ---- toJson helpers ----

    @Test
    void toJsonHandlesAllSupportedTypes() {
        assertTrue(McpResponses.toJson(null).isJsonNull());
        assertEquals("s", McpResponses.toJson("s").getAsString());
        assertEquals(4, McpResponses.toJson(4).getAsInt());
        assertTrue(McpResponses.toJson(true).getAsBoolean());
        assertEquals(2, McpResponses.toJson(List.of(1, 2)).getAsJsonArray().size());

        JsonObject o = McpResponses.toJson(Map.of("a", 1)).getAsJsonObject();
        assertEquals(1, o.get("a").getAsInt());

        assertThrows(IllegalArgumentException.class, () -> McpResponses.toJson(new Object()));
    }
}
