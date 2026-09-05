package dev.mcmcp.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.tools.ExecuteCommandHandler;
import dev.mcmcp.domain.error.ToolErrorCode;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecuteCommandHandlerTest {

    private final Fakes.FakeCommandPort commandPort = new Fakes.FakeCommandPort();
    private final Fakes.FakeLifecyclePort lifecycle = new Fakes.FakeLifecyclePort();
    private final ExecuteCommandHandler handler = new ExecuteCommandHandler(commandPort, lifecycle, 5_000_000L);

    @Test
    void successProducesStructuredOutputAndForwardsArguments() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say hello");

        ToolCallOutcome outcome = handler.handle(params, 9_999L).join();

        assertFalse(outcome.isError(), "expected success");
        JsonObject json = JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        assertEquals("submitted", json.get("status").getAsString());
        assertEquals("/say hello", json.get("command").getAsString());
        assertTrue(json.has("submitted_at"));

        assertEquals(1, commandPort.calls.size());
        Fakes.FakeCommandPort.Call call = commandPort.calls.get(0);
        assertEquals("say hello", call.command());
        assertEquals(42L, call.generation());
        assertEquals(9_999L, call.deadline());
    }

    @Test
    void usesDefaultDeadlineWhenRequestDeadlineIsZero() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/time set day");

        handler.handle(params, 0).join();

        assertEquals(5_000_000L, commandPort.calls.get(0).deadline());
    }

    @Test
    void rejectsEmptyCommand() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsMissingLeadingSlash() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "say hello");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsTrailingWhitespace() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say hello ");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("whitespace"));
    }

    @Test
    void acceptsCommandAt256CharacterBoundary() {
        String core = "a".repeat(256);
        JsonObject params = new JsonObject();
        params.addProperty("command", "/" + core);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertFalse(outcome.isError());
        assertEquals(256, commandPort.calls.get(0).command().length());
    }

    @Test
    void rejectsCommandOver256CharacterBoundary() {
        String core = "a".repeat(257);
        JsonObject params = new JsonObject();
        params.addProperty("command", "/" + core);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("1–256"));
    }

    @Test
    void rejectsControlCharacters() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say line one\nline two");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsSectionSign() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say §bad");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsUnpairedSurrogate() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say \uD800");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("surrogate"));
    }

    @Test
    void acceptsValidSurrogatePair() {
        String emoji = "\uD83D\uDE00";
        JsonObject params = new JsonObject();
        params.addProperty("command", "/say " + emoji);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertFalse(outcome.isError(), "valid surrogate pair should be accepted");
        assertEquals("say " + emoji, commandPort.calls.get(0).command());
    }

    @Test
    void rejectsDoubleSlashCommand() {
        JsonObject params = new JsonObject();
        params.addProperty("command", "//say hello");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("//"));
    }

    @Test
    void propagatesPortErrorResult() {
        commandPort.results.add(MinecraftPorts.Result.err(
            dev.mcmcp.domain.error.ToolError.of(ToolErrorCode.PLAYER_NOT_AVAILABLE, "no player")
        ));
        JsonObject params = new JsonObject();
        params.addProperty("command", "/gamemode creative");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.PLAYER_NOT_AVAILABLE, outcome.error().code());
    }

    @Test
    void mapsPortExceptionToToolError() {
        commandPort.futures.add(CompletableFuture.failedFuture(new RuntimeException("boom")));
        JsonObject params = new JsonObject();
        params.addProperty("command", "/seed");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INTERNAL_ERROR, outcome.error().code());
        assertTrue(outcome.error().message().contains("command port failed"));
    }

    @Test
    void rejectsMissingCommand() {
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("required"));
    }

    @Test
    void rejectsWrongTypeCommand() {
        JsonObject params = new JsonObject();
        params.add("command", new JsonArray());

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("string"));
    }
}
