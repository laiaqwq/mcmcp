package dev.mcmcp.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.tools.GetGameStateHandler;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GetGameStateHandlerTest {

    private final Fakes.FakeStatePort statePort = new Fakes.FakeStatePort();
    private final GetGameStateHandler handler = new GetGameStateHandler(statePort, 5_000_000L);

    @Test
    void defaultSectionsQueriesAllAndProducesOutput() {
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 8_888L).join();

        assertFalse(outcome.isError());
        JsonObject json = JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        assertTrue(json.has("captured_at"));
        assertTrue(json.has("ready"));

        assertEquals(1, statePort.calls.size());
        Fakes.FakeStatePort.Call call = statePort.calls.get(0);
        assertEquals(Set.of("client", "connection", "player", "world", "debug", "target"), Set.copyOf(call.sections()));
        assertEquals(8_888L, call.deadline());
    }

    @Test
    void specificSectionsAreForwarded() {
        statePort.results.add(MinecraftPorts.Result.ok(Fakes.clientSnapshot()));
        JsonObject params = new JsonObject();
        JsonArray sections = new JsonArray();
        sections.add("client");
        params.add("sections", sections);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertFalse(outcome.isError());
        JsonObject json = JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        assertTrue(json.has("client"));

        assertEquals(List.of("client"), statePort.calls.get(0).sections());
    }

    @Test
    void usesDefaultDeadlineWhenRequestDeadlineIsZero() {
        JsonObject params = new JsonObject();

        handler.handle(params, 0).join();

        assertEquals(5_000_000L, statePort.calls.get(0).deadline());
    }

    @Test
    void rejectsUnknownSection() {
        JsonObject params = new JsonObject();
        JsonArray sections = new JsonArray();
        sections.add("inventory");
        params.add("sections", sections);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsDuplicateSection() {
        JsonObject params = new JsonObject();
        JsonArray sections = new JsonArray();
        sections.add("client");
        sections.add("client");
        params.add("sections", sections);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsSectionsWhenNotAnArray() {
        JsonObject params = new JsonObject();
        params.addProperty("sections", "client");

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("sections must be an array"));
    }

    @Test
    void propagatesPortErrorResult() {
        statePort.results.add(MinecraftPorts.Result.err(
            ToolError.of(ToolErrorCode.GAME_NOT_READY, "not ready")
        ));
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.GAME_NOT_READY, outcome.error().code());
    }

    @Test
    void mapsPortExceptionToToolError() {
        statePort.futures.add(CompletableFuture.failedFuture(new RuntimeException("boom")));
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INTERNAL_ERROR, outcome.error().code());
        assertTrue(outcome.error().message().contains("state port failed"));
    }
}
