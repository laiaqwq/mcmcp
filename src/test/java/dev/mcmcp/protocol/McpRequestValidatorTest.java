package dev.mcmcp.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpRequestValidatorTest {

    @Test
    void isValidProtocolVersion() {
        assertTrue(McpRequestValidator.isValidProtocolVersion("2026-07-28"));
        assertFalse(McpRequestValidator.isValidProtocolVersion("2025-11-25"));
        assertFalse(McpRequestValidator.isValidProtocolVersion(null));
        // Whitespace is trimmed per HTTP header parsing rules
        assertTrue(McpRequestValidator.isValidProtocolVersion(" 2026-07-28 "));
    }

    @Test
    void methodMatchesHeader() {
        assertTrue(McpRequestValidator.methodMatchesHeader("tools/call", "tools/call"));
        assertFalse(McpRequestValidator.methodMatchesHeader("tools/list", "tools/call"));
        assertFalse(McpRequestValidator.methodMatchesHeader(null, "tools/call"));
    }

    @Test
    void decodeNameHeaderAscii() {
        assertEquals("minecraft_execute_command",
            McpRequestValidator.decodeNameHeader("minecraft_execute_command"));
    }

    @Test
    void decodeNameHeaderBase64() {
        String encoded = java.util.Base64.getEncoder()
            .encodeToString("minecraft_execute_command".getBytes());
        assertEquals("minecraft_execute_command",
            McpRequestValidator.decodeNameHeader("@base64:" + encoded));
    }

    @Test
    void decodeNameHeaderRejectsNonAscii() {
        assertNull(McpRequestValidator.decodeNameHeader("minecraft_\u00e9"));
    }

    @Test
    void validateEnvelopeValid() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "tools/list");
        var result = McpRequestValidator.validateEnvelope(body);
        assertTrue(result.valid());
        assertEquals("tools/list", result.method());
    }

    @Test
    void validateEnvelopeMissingJsonrpc() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("id", 1);
        body.addProperty("method", "tools/list");
        var result = McpRequestValidator.validateEnvelope(body);
        assertFalse(result.valid());
        assertEquals(JsonRpcErrors.INVALID_REQUEST, result.errorCode());
    }

    @Test
    void validateEnvelopeUnknownMethod() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "unknown/method");
        var result = McpRequestValidator.validateEnvelope(body);
        assertFalse(result.valid());
        assertEquals(JsonRpcErrors.METHOD_NOT_FOUND, result.errorCode());
    }
}
