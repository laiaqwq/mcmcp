package dev.mcmcp.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.tools.GetChatMessagesHandler;
import dev.mcmcp.domain.chat.ChatMessage;
import dev.mcmcp.domain.chat.ChatQueryResult;
import dev.mcmcp.domain.error.ToolErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GetChatMessagesHandlerTest {

    private final Fakes.FakeChatPort chatPort = new Fakes.FakeChatPort();
    private final GetChatMessagesHandler handler = new GetChatMessagesHandler(chatPort);

    @Test
    void successUsesDefaultsAndProducesStructuredOutput() {
        chatPort.result = new ChatQueryResult(
            List.of(new ChatMessage(
                1L, "s1", ChatMessage.MessageType.CHAT,
                "hello", "2024-01-01T00:00:00Z", 5L
            )),
            1L, 10L, 11L, false, false, 0L
        );
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params);

        assertFalse(outcome.isError());
        JsonObject json = JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("messages").size());
        JsonObject msg = json.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(1L, msg.get("id").getAsLong());
        assertEquals("chat", msg.get("type").getAsString());

        assertEquals(1, chatPort.calls.size());
        Fakes.FakeChatPort.Call call = chatPort.calls.get(0);
        assertNull(call.afterId());
        assertEquals(50, call.limit());
        assertNull(call.types());
    }

    @Test
    void passesAllArgumentsToPort() {
        JsonObject params = new JsonObject();
        params.addProperty("after_id", 10L);
        params.addProperty("limit", 5);
        JsonArray types = new JsonArray();
        types.add("chat");
        types.add("system");
        params.add("types", types);

        handler.handle(params);

        Fakes.FakeChatPort.Call call = chatPort.calls.get(0);
        assertEquals(10L, call.afterId());
        assertEquals(5, call.limit());
        assertEquals(List.of(ChatMessage.MessageType.CHAT, ChatMessage.MessageType.SYSTEM), call.types());
    }

    @Test
    void rejectsNegativeAfterId() {
        JsonObject params = new JsonObject();
        params.addProperty("after_id", -1L);

        ToolCallOutcome outcome = handler.handle(params);

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsAfterIdWhenNotANumber() {
        JsonObject params = new JsonObject();
        params.addProperty("after_id", "not-a-number");

        ToolCallOutcome outcome = handler.handle(params);

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("after_id must be a number"));
    }

    @Test
    void rejectsLimitTooLarge() {
        JsonObject params = new JsonObject();
        params.addProperty("limit", 500);

        ToolCallOutcome outcome = handler.handle(params);

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsInvalidMessageType() {
        JsonObject params = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("unknown");
        params.add("types", types);

        ToolCallOutcome outcome = handler.handle(params);

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsTypesWhenNotAnArray() {
        JsonObject params = new JsonObject();
        params.addProperty("types", "chat");

        ToolCallOutcome outcome = handler.handle(params);

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("types must be an array"));
    }
}
