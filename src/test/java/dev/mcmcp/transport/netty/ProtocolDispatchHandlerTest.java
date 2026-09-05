package dev.mcmcp.transport.netty;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.application.McpApplication;
import dev.mcmcp.config.McmcpConfig;
import dev.mcmcp.observability.Metrics;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolDispatchHandlerTest {

    @Test
    void standardInitializeDoesNotRequireExtensionHeaders() {
        String body = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-11-25",
              "capabilities":{},
              "clientInfo":{"name":"test-client","version":"1.0"}
            }}
            """;

        FullHttpResponse response = exchange(body);
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject json = responseJson(response);
        assertEquals("2025-11-25",
            json.getAsJsonObject("result").get("protocolVersion").getAsString());
        assertEquals("mcmcp",
            json.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());
        response.release();
    }

    @Test
    void initializedNotificationReturnsAcceptedWithoutBody() {
        FullHttpResponse response = exchange(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
        assertEquals(HttpResponseStatus.ACCEPTED, response.status());
        assertEquals(0, response.content().readableBytes());
        response.release();
    }

    @Test
    void standardToolsListDoesNotRequireExtensionHeadersOrMeta() {
        FullHttpResponse response = exchange(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonObject json = responseJson(response);
        assertEquals(4, json.getAsJsonObject("result").getAsJsonArray("tools").size());
        response.release();
    }

    private static FullHttpResponse exchange(String body) {
        var app = new McpApplication(
            McmcpConfig.defaults(),
            "test",
            (command, generation, deadline) -> CompletableFuture.completedFuture(null),
            (afterId, limit, types) -> null,
            (sections, deadline) -> CompletableFuture.completedFuture(null),
            (maxWidth, deadline) -> CompletableFuture.completedFuture(null),
            () -> 0L
        );
        EmbeddedChannel channel = new EmbeddedChannel(new ProtocolDispatchHandler(
            app.dispatcher(), new Metrics(), TimeUnit.SECONDS.toNanos(5)));
        var request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/mcp",
            Unpooled.copiedBuffer(body, StandardCharsets.UTF_8)
        );
        channel.writeInbound(request);
        channel.runPendingTasks();
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        channel.finishAndReleaseAll();
        return response;
    }

    private static JsonObject responseJson(FullHttpResponse response) {
        return JsonParser.parseString(response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
