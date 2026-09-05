package dev.mcmcp.transport.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipeline-level tests for {@link HeaderGateHandler} using {@link EmbeddedChannel}.
 * Verifies that rejected requests produce the gate's error status and are not
 * forwarded, while accepted requests pass through untouched.
 */
class HeaderGateHandlerTest {

    private static final int PORT = 25585;

    private EmbeddedChannel newChannel() {
        return new EmbeddedChannel(new HeaderGateHandler(new HttpSecurityGate(PORT)));
    }

    private static HttpRequest validRequest() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.headers().set("Host", "127.0.0.1:" + PORT);
        req.headers().set("Content-Type", "application/json");
        req.headers().set("Accept", "application/json, text/event-stream");
        return req;
    }

    @Test
    void validRequestPassesThroughUnrejected() {
        EmbeddedChannel channel = newChannel();
        HttpRequest req = validRequest();

        assertTrue(channel.writeInbound(req));
        Object passed = channel.readInbound();
        assertSame(req, passed, "accepted request should be forwarded as-is");
        assertNull(channel.readOutbound(), "no response should be written for a valid request");

        channel.finishAndReleaseAll();
    }

    @Test
    void rejectedRequestProducesResponseAndIsNotForwarded() {
        EmbeddedChannel channel = newChannel();
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        req.headers().set("Host", "127.0.0.1:" + PORT);

        assertFalse(channel.writeInbound(req), "rejected request should not be forwarded");

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
        assertEquals(0, response.content().readableBytes());
        assertEquals("0", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        response.release();

        assertNull(channel.readInbound(), "rejected request must not reach the next handler");
        channel.finishAndReleaseAll();
    }

    @Test
    void eachGateStatusIsPropagated() {
        // Spot-check that several distinct gate rejections surface their own status.
        assertEquals(HttpResponseStatus.NOT_FOUND, rejectionStatus(req -> req.setUri("/bad")));
        assertEquals(HttpResponseStatus.FORBIDDEN, rejectionStatus(req -> req.headers().set("Origin", "http://evil")));
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
            rejectionStatus(req -> req.headers().set("Content-Type", "text/plain")));
        assertEquals(HttpResponseStatus.NOT_ACCEPTABLE,
            rejectionStatus(req -> req.headers().set("Accept", "application/json")));
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
            rejectionStatus(req -> req.headers().set("Content-Length",
                String.valueOf(HttpSecurityGate.MAX_BODY_BYTES + 1))));
    }

    private static HttpResponseStatus rejectionStatus(java.util.function.Consumer<HttpRequest> mutate) {
        EmbeddedChannel channel = new EmbeddedChannel(new HeaderGateHandler(new HttpSecurityGate(PORT)));
        HttpRequest req = validRequest();
        mutate.accept(req);
        channel.writeInbound(req);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        HttpResponseStatus status = response.status();
        response.release();
        channel.finishAndReleaseAll();
        return status;
    }

    @Test
    void channelIsClosedAfterRejection() {
        EmbeddedChannel channel = newChannel();
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        req.headers().set("Host", "127.0.0.1:" + PORT);

        channel.writeInbound(req);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        response.release();

        channel.runPendingTasks();
        assertFalse(channel.isOpen(), "handler closes the channel after an error response");
    }

    @Test
    void badDecoderResultYieldsBadRequest() {
        EmbeddedChannel channel = newChannel();
        var req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        req.setDecoderResult(io.netty.handler.codec.DecoderResult.failure(new RuntimeException("corrupt")));

        assertFalse(channel.writeInbound(req));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void subsequentRequestsAfterRejectionAreNotForwarded() {
        EmbeddedChannel channel = newChannel();
        var bad = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mcp");
        bad.headers().set("Host", "127.0.0.1:" + PORT);
        channel.writeInbound(bad);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        response.release();
        channel.runPendingTasks();

        // Channel is closed after rejection; further inbound writes are refused.
        assertFalse(channel.isOpen());
        assertThrows(java.nio.channels.ClosedChannelException.class,
            () -> channel.writeInbound(validRequest()));
        assertNull(channel.readInbound());
    }
}
