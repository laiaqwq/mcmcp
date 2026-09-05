package dev.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Builds JSON-RPC 2.0 responses and MCP Tool Result objects.
 * Uses stable field ordering and UTF-8 encoding.
 * See PRD §7, §8 and IMPLEMENTATION.md §9.6.
 */
public final class McpResponses {

    // ---- JSON-RPC envelope ----

    public static JsonObject success(JsonElement id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("result", result);
        return resp;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        return error(id, code, message, null);
    }

    public static JsonObject error(JsonElement id, int code, String message, JsonObject data) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        if (data != null) err.add("data", data);
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("error", err);
        return resp;
    }

    public static JsonObject errorNoId(int code, String message) {
        return error(com.google.gson.JsonNull.INSTANCE, code, message);
    }

    // ---- MCP server/discover result ----

    public static JsonObject discoverResult(String modVersion) {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        result.addProperty("ttlMs", 3600000L);
        result.addProperty("cacheScope", "public");

        JsonArray versions = new JsonArray();
        versions.add("2026-07-28");
        result.add("supportedVersions", versions);

        JsonObject caps = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        caps.add("tools", toolsCap);
        result.add("capabilities", caps);

        JsonObject meta = new JsonObject();
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "mcmcp");
        serverInfo.addProperty("version", modVersion);
        meta.add("io.modelcontextprotocol/serverInfo", serverInfo);
        result.add("_meta", meta);

        result.addProperty("instructions",
            "MCMCP exposes 4 tools for the current Minecraft client. " +
            "Commands are submitted (not confirmed executed); read chat messages for feedback. " +
            "For large-scale world modifications, confirm the plan with the user before executing.");
        return result;
    }

    // ---- MCP tools/list result ----

    public static JsonObject toolsListResult(JsonArray tools) {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        result.addProperty("ttlMs", 3600000L);
        result.addProperty("cacheScope", "public");
        result.add("tools", tools);
        return result;
    }

    // ---- MCP Tool Result (tools/call) ----

    public static JsonObject toolSuccess(JsonElement id, JsonObject structuredContent, JsonArray content) {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        result.add("content", content);
        result.add("structuredContent", structuredContent);
        result.addProperty("isError", false);
        return success(id, result);
    }

    public static JsonObject toolError(JsonElement id, ToolError error) {
        JsonObject errObj = new JsonObject();
        errObj.addProperty("code", error.code().name());
        errObj.addProperty("message", error.message());
        if (error.retryable() != null) {
            errObj.addProperty("retryable", error.retryable());
        }

        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", errObj.toString());
        content.add(textContent);
        result.add("content", content);
        result.addProperty("isError", true);
        // Error results do NOT include structuredContent (PRD §8)
        return success(id, result);
    }

    // ---- Content block builders ----

    public static JsonObject textContent(String text) {
        JsonObject c = new JsonObject();
        c.addProperty("type", "text");
        c.addProperty("text", text);
        return c;
    }

    public static JsonObject imageContent(byte[] pngBytes) {
        String b64 = Base64.getEncoder().encodeToString(pngBytes);
        JsonObject c = new JsonObject();
        c.addProperty("type", "image");
        c.addProperty("mimeType", "image/png");
        c.addProperty("data", b64);
        return c;
    }

    // ---- Structured content builders for each tool ----

    public static JsonObject commandStructured(String command, String submittedAt) {
        JsonObject o = new JsonObject();
        o.addProperty("status", "submitted");
        o.addProperty("command", command);
        o.addProperty("submitted_at", submittedAt);
        return o;
    }

    public static JsonObject screenshotStructured(ScreenshotResult shot) {
        JsonObject o = new JsonObject();
        o.addProperty("captured_at", shot.capturedAt());
        o.addProperty("width", shot.width());
        o.addProperty("height", shot.height());
        o.addProperty("mime_type", shot.mimeType());
        o.addProperty("source_width", shot.sourceWidth());
        o.addProperty("source_height", shot.sourceHeight());
        return o;
    }

    // ---- Helpers to convert DTOs to JSON ----

    public static JsonObject toJsonObject(Map<String, ?> map) {
        JsonObject o = new JsonObject();
        for (var e : map.entrySet()) {
            o.add(e.getKey(), toJson(e.getValue()));
        }
        return o;
    }

    public static JsonElement toJson(Object value) {
        if (value == null) return com.google.gson.JsonNull.INSTANCE;
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Number n) return new JsonPrimitive(n);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof List<?> list) {
            JsonArray arr = new JsonArray();
            for (var item : list) arr.add(toJson(item));
            return arr;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject o = new JsonObject();
            for (var e : map.entrySet()) {
                o.add(String.valueOf(e.getKey()), toJson(e.getValue()));
            }
            return o;
        }
        throw new IllegalArgumentException("cannot serialize: " + value.getClass());
    }

    private McpResponses() {}
}
