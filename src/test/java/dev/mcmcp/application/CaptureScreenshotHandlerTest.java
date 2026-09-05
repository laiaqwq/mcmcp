package dev.mcmcp.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.tools.CaptureScreenshotHandler;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptureScreenshotHandlerTest {

    private final Fakes.FakeScreenshotPort screenshotPort = new Fakes.FakeScreenshotPort();
    private final CaptureScreenshotHandler handler = new CaptureScreenshotHandler(screenshotPort, 5_000_000L);

    @Test
    void successProducesStructuredOutputWithImage() {
        JsonObject params = new JsonObject();
        params.addProperty("max_width", 1024);

        ToolCallOutcome outcome = handler.handle(params, 7_777L).join();

        assertFalse(outcome.isError());
        assertTrue(outcome.hasImage());
        assertArrayEquals(new byte[] {1, 2, 3}, outcome.imagePngBytes());

        JsonObject json = JsonParser.parseString(outcome.structuredContentJson()).getAsJsonObject();
        assertTrue(json.has("captured_at"));
        assertEquals(800, json.get("width").getAsInt());
        assertEquals(600, json.get("height").getAsInt());
        assertEquals("image/png", json.get("mime_type").getAsString());
        assertEquals(1920, json.get("source_width").getAsInt());
        assertEquals(1080, json.get("source_height").getAsInt());

        assertEquals(1, screenshotPort.calls.size());
        Fakes.FakeScreenshotPort.Call call = screenshotPort.calls.get(0);
        assertEquals(1024, call.maxWidth());
        assertEquals(7_777L, call.deadline());
    }

    @Test
    void usesDefaultMaxWidthAndDeadline() {
        JsonObject params = new JsonObject();

        handler.handle(params, 0).join();

        assertEquals(3840, screenshotPort.calls.get(0).maxWidth());
        assertEquals(5_000_000L, screenshotPort.calls.get(0).deadline());
    }

    @Test
    void rejectsMaxWidthOutOfRange() {
        JsonObject params = new JsonObject();
        params.addProperty("max_width", 5000);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsMaxWidthBelowOne() {
        JsonObject params = new JsonObject();
        params.addProperty("max_width", 0);

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
    }

    @Test
    void rejectsMaxWidthWhenWrongType() {
        JsonObject params = new JsonObject();
        params.add("max_width", new JsonArray());

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, outcome.error().code());
        assertTrue(outcome.error().message().contains("max_width must be a number"));
    }

    @Test
    void propagatesPortErrorResult() {
        screenshotPort.results.add(MinecraftPorts.Result.err(
            ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE, "not ready")
        ));
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.SCREENSHOT_UNAVAILABLE, outcome.error().code());
    }

    @Test
    void mapsPortExceptionToToolError() {
        screenshotPort.futures.add(CompletableFuture.failedFuture(new RuntimeException("boom")));
        JsonObject params = new JsonObject();

        ToolCallOutcome outcome = handler.handle(params, 0).join();

        assertTrue(outcome.isError());
        assertEquals(ToolErrorCode.INTERNAL_ERROR, outcome.error().code());
        assertTrue(outcome.error().message().contains("screenshot port failed"));
    }
}
