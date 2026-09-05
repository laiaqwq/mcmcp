package dev.mcmcp.transport.netty;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.McpApplication;
import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.config.McmcpConfig;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.command.CommandResult;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.state.GameStateSnapshot;
import dev.mcmcp.observability.Metrics;
import dev.mcmcp.protocol.JsonRpcErrors;
import dev.mcmcp.protocol.McpRequestValidator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-path tests for {@link ProtocolDispatchHandler}: malformed JSON, JSON-RPC
 * envelope violations, MCP header/_meta mismatches, tool-call errors, cancellation,
 * and the layering between HTTP status codes and JSON-RPC error objects.
 *
 * <p>Design under test: transport-level failures (parse errors, envelope errors,
 * header/_meta mismatches) produce HTTP 400 with a JSON-RPC error body, while
 * application-level failures surfaced by the dispatcher produce HTTP 200 with a
 * JSON-RPC error object (or an MCP Tool Result with isError=true).
 */
class ProtocolErrorPathTest {

    // ---------- Harness ----------

    /** Holds a channel plus the controllable future returned by the command port. */
    private static final class Harness {
        final EmbeddedChannel channel;
        final Metrics metrics;
        volatile CompletableFuture<MinecraftPorts.Result<CommandResult, ToolError>> commandFuture =
            CompletableFuture.completedFuture(
                MinecraftPorts.Result.ok(new CommandResult("submitted", "say hi", "2026-01-01T00:00:00Z")));

        Harness(long requestTimeoutNanos) {
            this.metrics = new Metrics();
            var app = new McpApplication(
                McmcpConfig.defaults(),
                "test",
                (command, generation, deadline) -> commandFuture,
                (afterId, limit, types) -> new ChatQueryResult(List.of(), null, null, null, false, false, 0),
                (sections, deadline) -> CompletableFuture.completedFuture(
                    MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.INTERNAL_ERROR, "no state"))),
                (maxWidth, deadline) -> CompletableFuture.completedFuture(
                    MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.INTERNAL_ERROR, "no shot"))),
                () -> 0L
            );
            this.channel = new EmbeddedChannel(
                new ProtocolDispatchHandler(app.dispatcher(), metrics, requestTimeoutNanos));
        }

        FullHttpResponse exchange(String body) {
            return exchange(body, req -> {});
        }

        FullHttpResponse exchange(String body, Consumer<DefaultFullHttpRequest> customize) {
            var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp",
                body == null
                    ? Unpooled.EMPTY_BUFFER
                    : Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
            customize.accept(request);
            channel.writeInbound(request);
            channel.runPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            return response;
        }
    }

    private static Harness newHarness() {
        return new Harness(TimeUnit.SECONDS.toNanos(5));
    }

    private static JsonObject responseJson(FullHttpResponse response) {
        return JsonParser.parseString(
            response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void assertJsonRpcError(FullHttpResponse response,
                                           HttpResponseStatus status, int code) {
        assertEquals(status, response.status());
        JsonObject json = responseJson(response);
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        JsonObject error = json.getAsJsonObject("error");
        assertNotNull(error, "expected error object in " + json);
        assertEquals(code, error.get("code").getAsInt());
    }

    private static final String VALID_INITIALIZE =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}}";

    private static final String VALID_TOOL_CALL =
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{"
            + "\"name\":\"minecraft_execute_command\","
            + "\"arguments\":{\"command\":\"/say hi\"}}}";

    // ---------- 1. Body / JSON parse errors (HTTP 400 + -32700) ----------

    @Test
    void emptyBodyIsParseError400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(null);
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void malformedJsonIsParseError400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange("{\"jsonrpc\":\"2.0\",\"id\":");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void duplicateKeysAreRejectedAsParseError() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"id\":2,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void batchArrayIsRejectedAsParseError() {
        // Batch requests are not supported: StrictJsonReader requires a single
        // top-level object, so a JSON array fails at the parse stage.
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void scalarTopLevelIsParseError() {
        var h = newHarness();
        FullHttpResponse r = h.exchange("\"ping\"");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void trailingGarbageIsParseError() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"} garbage");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.PARSE_ERROR);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- 2. Envelope errors (HTTP 400 + -32600/-32601/-32602) ----------

    @Test
    void missingJsonrpcFieldIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange("{\"id\":1,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void wrongJsonrpcVersionIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void numericJsonrpcFieldIsRejected() {
        // Per JSON-RPC 2.0 the jsonrpc member must be the string "2.0";
        // a JSON number 2.0 is invalid even though getAsString() would match.
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void missingMethodIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange("{\"jsonrpc\":\"2.0\",\"id\":1}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void nonStringMethodIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":42}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void unknownMethodIsMethodNotFound400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.METHOD_NOT_FOUND);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- id handling ----------

    @Test
    void missingIdOnRequestIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void nullIdIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void fractionalIdIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void booleanIdIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void objectIdIsInvalidRequest() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_REQUEST);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void stringIdIsAcceptedAndEchoed() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":\"req-abc\",\"method\":\"ping\"}");
        assertEquals(HttpResponseStatus.OK, r.status());
        JsonObject json = responseJson(r);
        assertEquals("req-abc", json.get("id").getAsString());
        assertNotNull(json.getAsJsonObject("result"));
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void negativeIntegerIdIsAccepted() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":-42,\"method\":\"ping\"}");
        assertEquals(HttpResponseStatus.OK, r.status());
        assertEquals(-42, responseJson(r).get("id").getAsInt());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- Notifications ----------

    @Test
    void unknownNotificationWithoutIdIsAccepted() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{}}");
        assertEquals(HttpResponseStatus.ACCEPTED, r.status());
        assertEquals(0, r.content().readableBytes());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void notificationWithIdIsDispatchedAndFailsMethodNotFound() {
        // A notifications/* method carrying an id is not treated as a notification;
        // it is dispatched and lands on the default branch → -32601 over HTTP 200.
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"notifications/cancelled\"}");
        assertJsonRpcError(r, HttpResponseStatus.OK, JsonRpcErrors.METHOD_NOT_FOUND);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- params shape ----------

    @Test
    void nonObjectParamsIsInvalidParams400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":[1,2]}");
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- MCP-Protocol-Version header ----------

    @Test
    void unsupportedProtocolVersionHeaderIsRejected() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_INITIALIZE,
            req -> req.headers().set("MCP-Protocol-Version", "1999-01-01"));
        assertEquals(HttpResponseStatus.BAD_REQUEST, r.status());
        JsonObject json = responseJson(r);
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION, error.get("code").getAsInt());
        // The handler builds a `data` object with supportedVersions; it must
        // reach the wire even when the request id is unknown (pre-parse
        // rejection via errorNoId).
        assertTrue(error.has("data"), "error.data must carry supportedVersions");
        var versions = error.getAsJsonObject("data").getAsJsonArray("supportedVersions");
        assertEquals(List.of(McpRequestValidator.PROTOCOL_VERSION),
            versions.asList().stream().map(e -> e.getAsString()).toList());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void supportedProtocolVersionHeaderIsAccepted() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_INITIALIZE,
            req -> req.headers().set("MCP-Protocol-Version", "2025-06-18"));
        assertEquals(HttpResponseStatus.OK, r.status());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- Mcp-Method header ----------

    @Test
    void mismatchedMcpMethodHeaderIsHeaderMismatch() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_INITIALIZE,
            req -> req.headers().set("Mcp-Method", "tools/call"));
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.HEADER_MISMATCH);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void matchingMcpMethodHeaderIsAccepted() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_INITIALIZE,
            req -> req.headers().set("Mcp-Method", "initialize"));
        assertEquals(HttpResponseStatus.OK, r.status());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- _meta extension ----------

    @Test
    void metaWithUnsupportedProtocolVersionIsRejected() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"},"
            + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"1999-01-01\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST,
            JsonRpcErrors.UNSUPPORTED_PROTOCOL_VERSION);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void metaProtocolVersionMustBeString() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"},"
            + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":123}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void metaProtocolVersionWithoutClientCapabilitiesIsRejected() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"},"
            + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void nonObjectMetaIsIgnored() {
        // _meta validation only triggers for object-valued _meta containing the
        // protocolVersion key; a scalar _meta passes through untouched.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"},"
            + "\"_meta\":\"not-an-object\"}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertEquals(HttpResponseStatus.OK, r.status());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- tools/call ----------

    @Test
    void unknownToolNameIsInvalidParamsOver200() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
            + "\"name\":\"minecraft_nuke_world\",\"arguments\":{}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.OK, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void missingToolNameIsInvalidParamsOver200() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
            + "\"arguments\":{}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.OK, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void mcpNameHeaderMismatchIsHeaderMismatch400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL,
            req -> req.headers().set("Mcp-Name", "minecraft_get_game_state"));
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.HEADER_MISMATCH);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void invalidMcpNameEncodingIsHeaderMismatch400() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL,
            req -> req.headers().set("Mcp-Name", "@base64:!!!not-base64!!!"));
        assertJsonRpcError(r, HttpResponseStatus.BAD_REQUEST, JsonRpcErrors.HEADER_MISMATCH);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void matchingMcpNameHeaderIsAccepted() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL,
            req -> req.headers().set("Mcp-Name", "minecraft_execute_command"));
        assertEquals(HttpResponseStatus.OK, r.status());
        JsonObject json = responseJson(r);
        assertNotNull(json.getAsJsonObject("result"));
        assertFalse(json.getAsJsonObject("result").get("isError").getAsBoolean());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void toolArgumentValidationErrorIsToolResultErrorOver200() {
        // Business-level validation failure: isError=true Tool Result, HTTP 200,
        // and NOT a JSON-RPC error object.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
            + "\"name\":\"minecraft_execute_command\","
            + "\"arguments\":{\"command\":\"no leading slash\"}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertEquals(HttpResponseStatus.OK, r.status());
        JsonObject json = responseJson(r);
        assertNull(json.get("error"));
        JsonObject result = json.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- initialize version negotiation ----------

    @Test
    void initializeWithoutProtocolVersionIsInvalidParamsOver200() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertJsonRpcError(r, HttpResponseStatus.OK, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void initializeWithUnsupportedVersionNegotiatesCompatibility() {
        // Unknown protocolVersion in params is not an error: the server
        // negotiates down to the compatibility version.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2099-12-31\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"c\",\"version\":\"1\"}}}";
        var h = newHarness();
        FullHttpResponse r = h.exchange(body);
        assertEquals(HttpResponseStatus.OK, r.status());
        JsonObject json = responseJson(r);
        assertEquals("2025-11-25",
            json.getAsJsonObject("result").get("protocolVersion").getAsString());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void toolsListWithNonNullCursorIsInvalidParamsOver200() {
        var h = newHarness();
        FullHttpResponse r = h.exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\","
                + "\"params\":{\"cursor\":\"abc\"}}");
        assertJsonRpcError(r, HttpResponseStatus.OK, JsonRpcErrors.INVALID_PARAMS);
        r.release();
        h.channel.finishAndReleaseAll();
    }

    // ---------- Cancellation / disconnect ----------

    @Test
    void inFlightRequestProducesNoResponseUntilFutureCompletes() {
        var h = newHarness();
        h.commandFuture = new CompletableFuture<>(); // never completes on its own
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL);
        assertNull(r, "no response should be written while the tool call is in flight");

        h.commandFuture.complete(
            MinecraftPorts.Result.ok(new CommandResult("submitted", "say hi", "t")));
        h.channel.runPendingTasks();
        r = h.channel.readOutbound();
        assertNotNull(r);
        assertEquals(HttpResponseStatus.OK, r.status());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void clientDisconnectSuppressesLateResponse() {
        var h = newHarness();
        h.commandFuture = new CompletableFuture<>();
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL);
        assertNull(r);

        // Client disconnects mid-request → cancellation token fires via closeFuture.
        h.channel.close();
        h.commandFuture.complete(
            MinecraftPorts.Result.ok(new CommandResult("submitted", "say hi", "t")));
        h.channel.runPendingTasks();
        assertNull(h.channel.readOutbound(),
            "a response must not be written after the channel closed");
        assertEquals(1, h.metrics.lateCompletionCount());
        h.channel.finishAndReleaseAll();
    }

    @Test
    void portFailureMapsToToolErrorOver200() {
        // ExecuteCommandHandler converts a failed port future into a Tool
        // Result with isError=true (application-level failure), so the wire
        // response is HTTP 200 — not a transport-level 500.
        var h = newHarness();
        h.commandFuture = CompletableFuture.failedFuture(new RuntimeException("boom"));
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL);
        assertNotNull(r);
        assertEquals(HttpResponseStatus.OK, r.status());
        JsonObject json = responseJson(r);
        assertNull(json.get("error"));
        assertTrue(json.getAsJsonObject("result").get("isError").getAsBoolean());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void inFlightRequestTimesOutWithJsonRpcError() throws InterruptedException {
        // The transport layer schedules a timeout on the channel EventLoop;
        // an over-deadline in-flight request gets a JSON-RPC timeout error.
        var h = new Harness(TimeUnit.MILLISECONDS.toNanos(50));
        h.commandFuture = new CompletableFuture<>(); // never completes on its own
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL);
        assertNull(r, "no response while the tool call is in flight");

        Thread.sleep(120); // let the 50ms scheduled task expire
        h.channel.runPendingTasks();
        r = h.channel.readOutbound();
        assertNotNull(r, "timeout must synthesize a JSON-RPC error response");
        assertJsonRpcError(r, HttpResponseStatus.REQUEST_TIMEOUT,
            JsonRpcErrors.REQUEST_TIMEOUT);
        assertEquals(1, h.metrics.timeoutCount());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void lateCompletionAfterTimeoutIsSuppressedAndCounted() throws InterruptedException {
        var h = new Harness(TimeUnit.MILLISECONDS.toNanos(50));
        h.commandFuture = new CompletableFuture<>();
        FullHttpResponse r = h.exchange(VALID_TOOL_CALL);
        assertNull(r);

        Thread.sleep(120);
        h.channel.runPendingTasks();
        r = h.channel.readOutbound();
        assertNotNull(r);
        assertJsonRpcError(r, HttpResponseStatus.REQUEST_TIMEOUT,
            JsonRpcErrors.REQUEST_TIMEOUT);
        assertEquals(1, h.metrics.timeoutCount());
        r.release();

        // The port future completing after the timeout must not produce a
        // second response and must be counted as a late completion.
        h.commandFuture.complete(
            MinecraftPorts.Result.ok(new CommandResult("submitted", "say hi", "t")));
        h.channel.runPendingTasks();
        assertNull(h.channel.readOutbound(),
            "late completion after timeout must be suppressed");
        assertEquals(1, h.metrics.lateCompletionCount());
        assertEquals(1, h.metrics.timeoutCount(), "timeout must not be double-counted");
        h.channel.finishAndReleaseAll();
    }

    // ---------- HTTP-layer layering ----------

    @Test
    void nonPostMethodStillReachesProtocolLayer() {
        // HTTP method/path/Accept/Content-Type enforcement lives in
        // HttpSecurityGate/HeaderGateHandler, not in ProtocolDispatchHandler.
        // A GET with a valid JSON-RPC body is still processed here.
        var h = newHarness();
        var request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp",
            Unpooled.copiedBuffer(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",
                StandardCharsets.UTF_8));
        h.channel.writeInbound(request);
        h.channel.runPendingTasks();
        FullHttpResponse r = h.channel.readOutbound();
        assertNotNull(r);
        assertEquals(HttpResponseStatus.OK, r.status());
        r.release();
        h.channel.finishAndReleaseAll();
    }

    @Test
    void errorResponsesCarryJsonContentTypeAndNoStore() {
        var h = newHarness();
        FullHttpResponse r = h.exchange("not json");
        assertEquals(HttpResponseStatus.BAD_REQUEST, r.status());
        assertEquals("application/json; charset=utf-8",
            r.headers().get("Content-Type"));
        assertEquals("no-store", r.headers().get("Cache-Control"));
        r.release();
        h.channel.finishAndReleaseAll();
    }
}
