package dev.mcmcp.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.application.tools.CaptureScreenshotHandler;
import dev.mcmcp.application.tools.ExecuteCommandHandler;
import dev.mcmcp.application.tools.GetChatMessagesHandler;
import dev.mcmcp.application.tools.GetGameStateHandler;
import dev.mcmcp.protocol.JsonRpcErrors;
import dev.mcmcp.protocol.McpRequestValidator;
import dev.mcmcp.protocol.ToolCatalog;
import dev.mcmcp.util.RateLimiter;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherTest {

    private final Fakes.FakeCommandPort commandPort = new Fakes.FakeCommandPort();
    private final Fakes.FakeChatPort chatPort = new Fakes.FakeChatPort();
    private final Fakes.FakeStatePort statePort = new Fakes.FakeStatePort();
    private final Fakes.FakeScreenshotPort screenshotPort = new Fakes.FakeScreenshotPort();
    private final Fakes.FakeLifecyclePort lifecycle = new Fakes.FakeLifecyclePort();

    private ToolDispatcher dispatcher(int globalRps, int screenshotRps) {
        return new ToolDispatcher(
            "0.1.0",
            new ExecuteCommandHandler(commandPort, lifecycle, 5_000_000L),
            new GetChatMessagesHandler(chatPort),
            new GetGameStateHandler(statePort, 5_000_000L),
            new CaptureScreenshotHandler(screenshotPort, 5_000_000L),
            new RateLimiter(globalRps, () -> 0L),
            new RateLimiter(screenshotRps, () -> 0L)
        );
    }

    @Test
    void dispatchExecuteCommandSuccess() {
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.EXECUTE_COMMAND);
        JsonObject args = new JsonObject();
        args.addProperty("command", "/say hi");
        params.add("arguments", args);

        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 1_000L).join();

        assertNull(resp.get("error"), "expected no JSON-RPC error");
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals("submitted", structured.get("status").getAsString());

        JsonArray content = result.getAsJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void dispatchGetChatMessagesSuccess() {
        chatPort.result = new ChatQueryResult(
            List.of(new ChatMessage(
                1L, "s1", ChatMessage.MessageType.CHAT,
                "hello", "2024-01-01T00:00:00Z", 5L
            )),
            null, null, null, false, false, 0L
        );
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.GET_CHAT_MESSAGES);

        JsonObject resp = d.dispatch(new JsonPrimitive(2), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent").has("messages"));
    }

    @Test
    void dispatchGetGameStateSuccess() {
        statePort.results.add(MinecraftPorts.Result.ok(Fakes.clientSnapshot()));
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.GET_GAME_STATE);
        JsonObject args = new JsonObject();
        JsonArray sections = new JsonArray();
        sections.add("client");
        args.add("sections", sections);
        params.add("arguments", args);

        JsonObject resp = d.dispatch(new JsonPrimitive(3), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonObject("structuredContent").has("client"));
    }

    @Test
    void dispatchScreenshotSuccessIncludesImageContent() {
        screenshotPort.results.add(MinecraftPorts.Result.ok(new ScreenshotResult(
            "2024-01-01T00:00:00Z",
            100, 100, "image/png",
            200, 200,
            new byte[] {9, 9, 9}
        )));
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.CAPTURE_SCREENSHOT);

        JsonObject resp = d.dispatch(new JsonPrimitive(4), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
        JsonObject result = resp.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        JsonArray content = result.getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("image", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("image/png", content.get(1).getAsJsonObject().get("mimeType").getAsString());
    }

    @Test
    void dispatchUnknownMethod() {
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject resp = d.dispatch(new JsonPrimitive(1), "unknown/method", new JsonObject(), 0).join();

        assertNotNull(resp.get("error"));
        assertEquals(JsonRpcErrors.METHOD_NOT_FOUND, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void dispatchMissingToolName() {
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, new JsonObject(), 0).join();

        assertNotNull(resp.get("error"));
        assertEquals(JsonRpcErrors.INVALID_PARAMS, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void dispatchUnknownToolName() {
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", "not_a_tool");

        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNotNull(resp.get("error"));
        assertEquals(JsonRpcErrors.INVALID_PARAMS, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void globalRateLimiterRejectsAllTools() {
        ToolDispatcher d = dispatcher(0, 10);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.EXECUTE_COMMAND);
        JsonObject args = new JsonObject();
        args.addProperty("command", "/help");
        params.add("arguments", args);

        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"), "rate limit is a tool result, not a JSON-RPC error");
        JsonObject result = resp.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains(ToolErrorCode.RATE_LIMITED.name()));
    }

    @Test
    void screenshotRateLimiterRejectsOnlyScreenshots() {
        ToolDispatcher d = dispatcher(10, 1);
        // first screenshot consumes the screenshot token
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.CAPTURE_SCREENSHOT);
        d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        // second screenshot is rate-limited as a tool error
        JsonObject resp = d.dispatch(new JsonPrimitive(2), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
        JsonObject result = resp.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains(ToolErrorCode.RATE_LIMITED.name()));

        // another tool is unaffected
        JsonObject commandParams = new JsonObject();
        commandParams.addProperty("name", ToolCatalog.EXECUTE_COMMAND);
        JsonObject args = new JsonObject();
        args.addProperty("command", "/help");
        commandParams.add("arguments", args);
        JsonObject okResp = d.dispatch(new JsonPrimitive(3), McpRequestValidator.METHOD_TOOLS_CALL, commandParams, 0).join();

        assertNull(okResp.get("error"));
        assertFalse(okResp.getAsJsonObject("result").get("isError").getAsBoolean());
    }

    @Test
    void extractsEmptyArgumentsWhenMissing() {
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.GET_CHAT_MESSAGES);
        // no arguments -> handler uses defaults

        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
    }

    @Test
    void mapsToolErrorToToolResult() {
        commandPort.results.add(MinecraftPorts.Result.err(
            dev.mcmcp.domain.error.ToolError.of(ToolErrorCode.COMMAND_REJECTED, "rejected")
        ));
        ToolDispatcher d = dispatcher(10, 1);
        JsonObject params = new JsonObject();
        params.addProperty("name", ToolCatalog.EXECUTE_COMMAND);
        JsonObject args = new JsonObject();
        args.addProperty("command", "/help");
        params.add("arguments", args);

        JsonObject resp = d.dispatch(new JsonPrimitive(1), McpRequestValidator.METHOD_TOOLS_CALL, params, 0).join();

        assertNull(resp.get("error"));
        JsonObject result = resp.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
    }
}
