package dev.mcmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Validates MCP 2026-07-28 request headers and JSON-RPC envelope.
 * See PRD §5.2 and IMPLEMENTATION.md §9.3.
 *
 * <p>Validation order:
 * <ol>
 *   <li>JSON-RPC envelope (jsonrpc=2.0, id, method)</li>
 *   <li>MCP-Protocol-Version header</li>
 *   <li>params._meta (protocolVersion, clientCapabilities)</li>
 *   <li>Mcp-Method header matches body.method</li>
 *   <li>For tools/call: Mcp-Name header matches params.name</li>
 * </ol>
 */
public final class McpRequestValidator {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of(PROTOCOL_VERSION);

    public static final String METHOD_DISCOVER = "server/discover";
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    public static final Set<String> SUPPORTED_METHODS = Set.of(
        METHOD_DISCOVER, METHOD_TOOLS_LIST, METHOD_TOOLS_CALL
    );

    /**
     * Result of validating a request.
     */
    public record ValidationResult(
        boolean valid,
        int errorCode,
        String errorMessage,
        JsonObject errorData,
        JsonElement id,
        String method,
        JsonObject params
    ) {
        public static ValidationResult ok(JsonElement id, String method, JsonObject params) {
            return new ValidationResult(true, 0, null, null, id, method, params);
        }

        public static ValidationResult fail(int code, String msg) {
            return new ValidationResult(false, code, msg, null, null, null, null);
        }

        public static ValidationResult fail(int code, String msg, JsonObject data) {
            return new ValidationResult(false, code, msg, data, null, null, null);
        }
    }

    /**
     * Validate the JSON-RPC envelope.
     */
    public static ValidationResult validateEnvelope(JsonObject body) {
        if (!body.has("jsonrpc") || !body.get("jsonrpc").isJsonPrimitive()
            || !"2.0".equals(body.get("jsonrpc").getAsString()))
            return ValidationResult.fail(JsonRpcErrors.INVALID_REQUEST, "jsonrpc must be \"2.0\"");

        JsonElement idEl = body.get("id");
        if (idEl == null)
            return ValidationResult.fail(JsonRpcErrors.INVALID_REQUEST, "missing id");
        try {
            StrictJsonReader.validateRpcId(idEl);
        } catch (Exception e) {
            return ValidationResult.fail(JsonRpcErrors.INVALID_REQUEST, "invalid id: " + e.getMessage());
        }

        JsonElement methodEl = body.get("method");
        if (methodEl == null || !methodEl.isJsonPrimitive() || !methodEl.getAsJsonPrimitive().isString())
            return ValidationResult.fail(JsonRpcErrors.INVALID_REQUEST, "missing or invalid method");
        String method = methodEl.getAsString();
        if (!SUPPORTED_METHODS.contains(method))
            return ValidationResult.fail(JsonRpcErrors.METHOD_NOT_FOUND, "method not found: " + method);

        JsonObject params = null;
        if (body.has("params")) {
            JsonElement paramsEl = body.get("params");
            if (!paramsEl.isJsonObject())
                return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS, "params must be object");
            params = paramsEl.getAsJsonObject();
        }

        return ValidationResult.ok(idEl, method, params);
    }

    /**
     * Validate MCP-Protocol-Version header.
     */
    public static boolean isValidProtocolVersion(String header) {
        return header != null && SUPPORTED_VERSIONS.contains(header.trim());
    }

    /**
     * Validate params._meta for protocolVersion and clientCapabilities.
     */
    public static ValidationResult validateMeta(JsonObject params) {
        if (params == null)
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS, "missing params");

        JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject()
            ? params.getAsJsonObject("_meta") : null;
        if (meta == null)
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS,
                "params._meta is required");

        if (!meta.has("io.modelcontextprotocol/protocolVersion"))
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS,
                "params._meta.protocolVersion is required");
        JsonElement pvEl = meta.get("io.modelcontextprotocol/protocolVersion");
        if (!pvEl.isJsonPrimitive() || !pvEl.getAsJsonPrimitive().isString())
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS,
                "params._meta.protocolVersion must be string");
        String pv = pvEl.getAsString();
        if (!SUPPORTED_VERSIONS.contains(pv)) {
            JsonObject data = new JsonObject();
            JsonArray versions = new JsonArray();
            versions.add(PROTOCOL_VERSION);
            data.add("supportedVersions", versions);
            return new ValidationResult(false,
                JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION,
                "unsupported protocol version: " + pv, data, null, null, null);
        }

        if (!meta.has("io.modelcontextprotocol/clientCapabilities"))
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS,
                "params._meta.clientCapabilities is required");

        return ValidationResult.ok(null, null, params);
    }

    /**
     * Validate that Mcp-Method header matches the body method.
     */
    public static boolean methodMatchesHeader(String header, String bodyMethod) {
        return header != null && header.trim().equals(bodyMethod);
    }

    /**
     * Validate that Mcp-Name header matches params.name for tools/call.
     * Handles MCP Base64 sentinel encoding: if header starts with the sentinel prefix,
     * decode and compare.
     */
    public static ValidationResult validateToolName(String mcpNameHeader, JsonObject params) {
        if (mcpNameHeader == null || mcpNameHeader.isBlank())
            return ValidationResult.fail(JsonRpcErrors.HEADER_MISMATCH, "Mcp-Name header required for tools/call");

        String headerName = decodeNameHeader(mcpNameHeader);
        if (headerName == null)
            return ValidationResult.fail(JsonRpcErrors.HEADER_MISMATCH, "invalid Mcp-Name encoding");

        if (!params.has("name") || !params.get("name").isJsonPrimitive())
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS, "params.name is required");
        String bodyName = params.get("name").getAsString();
        if (!headerName.equals(bodyName))
            return ValidationResult.fail(JsonRpcErrors.HEADER_MISMATCH,
                "Mcp-Name header does not match params.name");
        if (!ToolCatalog.exists(bodyName))
            return ValidationResult.fail(JsonRpcErrors.INVALID_PARAMS,
                "unknown tool: " + bodyName);
        return ValidationResult.ok(null, null, params);
    }

    /**
     * Decode Mcp-Name header. MCP spec: if the name contains characters outside the
     * safe ASCII set, it is Base64-encoded with a sentinel prefix. Our 4 tool names
     * are all safe ASCII, so no encoding is expected.
     */
    static String decodeNameHeader(String header) {
        String trimmed = header.trim();
        // MCP sentinel: starts with "=?" style marker. For our safe-ASCII names, no encoding.
        // If header looks like Base64 sentinel, attempt decode.
        if (trimmed.startsWith("@base64:")) {
            String b64 = trimmed.substring("@base64:".length());
            try {
                byte[] decoded = Base64.getDecoder().decode(b64);
                return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        // Validate safe ASCII
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x21 || c > 0x7E) return null;
        }
        return trimmed;
    }

    private McpRequestValidator() {}
}
