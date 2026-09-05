package dev.mcmcp.transport.netty;

import dev.mcmcp.observability.Metrics;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InFlightGateHandler}'s concurrency cap and counter release.
 *
 * <p>The handler increments a shared counter on {@code channelRead} of a
 * {@link FullHttpRequest} and decrements it on {@code channelReadComplete} or
 * {@code channelInactive}. Because {@code EmbeddedChannel.writeInbound} fires
 * both read and readComplete, tests that need requests to stay in flight drive
 * {@code pipeline().fireChannelRead} directly.
 */
class InFlightGateHandlerTest {

    private static FullHttpRequest newRequest() {
        // NOTE: FullHttpRequest delegates its ref count to the content buffer, so a
        // real buffer is required — Unpooled.EMPTY_BUFFER is an immortal singleton
        // whose release() is a no-op.
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp",
            Unpooled.copiedBuffer("{}", StandardCharsets.UTF_8));
    }

    @Test
    void requestWithinLimitPassesThrough() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(8, counter, new Metrics()));

        assertTrue(channel.writeInbound(newRequest()));
        Object msg = channel.readInbound();
        assertNotNull(msg, "request under the limit should be forwarded");
        // writeInbound fires readComplete too, so the slot is released
        assertEquals(0, counter.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void counterIncrementsWhileRequestInFlight() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(8, counter, new Metrics()));

        channel.pipeline().fireChannelRead(newRequest());
        assertEquals(1, counter.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void requestOverLimitIsRejectedWith503() {
        AtomicInteger counter = new AtomicInteger();
        Metrics metrics = new Metrics();
        int max = LoopbackMcpServer.MAX_INFLIGHT;

        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(max, counter, metrics));
        counter.set(max); // simulate max concurrent requests elsewhere

        FullHttpRequest overLimit = newRequest();
        assertFalse(channel.writeInbound(overLimit), "over-limit request must not be forwarded");

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        assertEquals(0, response.content().readableBytes());
        assertEquals("0", response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL));
        response.release();

        assertEquals(0, overLimit.refCnt(), "rejected request must be released");
        assertEquals(max, counter.get(), "rejection must roll back the increment");
        assertEquals(1, metrics.rejectedCount());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void exactlyAtLimitIsAccepted() {
        AtomicInteger counter = new AtomicInteger(LoopbackMcpServer.MAX_INFLIGHT - 1);
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(LoopbackMcpServer.MAX_INFLIGHT, counter, new Metrics()));

        assertTrue(channel.writeInbound(newRequest()));
        assertNotNull(channel.readInbound());
        assertEquals(LoopbackMcpServer.MAX_INFLIGHT - 1, counter.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void sharedCounterAcrossChannelsEnforcesGlobalCap() {
        AtomicInteger counter = new AtomicInteger();
        Metrics metrics = new Metrics();
        int max = 4;

        EmbeddedChannel[] channels = new EmbeddedChannel[max];
        for (int i = 0; i < max; i++) {
            channels[i] = new EmbeddedChannel(new InFlightGateHandler(max, counter, metrics));
            // fireChannelRead only (no readComplete) keeps the request in flight
            channels[i].pipeline().fireChannelRead(newRequest());
        }
        assertEquals(max, counter.get());

        EmbeddedChannel overflow = new EmbeddedChannel(
            new InFlightGateHandler(max, counter, metrics));
        assertFalse(overflow.writeInbound(newRequest()));
        FullHttpResponse response = overflow.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        response.release();
        assertEquals(1, metrics.rejectedCount());
        overflow.finishAndReleaseAll();

        for (EmbeddedChannel ch : channels) {
            ch.pipeline().fireChannelReadComplete();
        }
        assertEquals(0, counter.get());
        for (EmbeddedChannel ch : channels) {
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void readCompleteReleasesTheSlot() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(1, counter, new Metrics()));

        channel.pipeline().fireChannelRead(newRequest());
        assertEquals(1, counter.get());
        channel.pipeline().fireChannelReadComplete();
        assertEquals(0, counter.get());

        // A subsequent request is admitted now that the slot is free
        assertTrue(channel.writeInbound(newRequest()));
        assertNotNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void channelInactiveReleasesTheSlot() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(1, counter, new Metrics()));

        channel.pipeline().fireChannelRead(newRequest());
        assertEquals(1, counter.get());

        channel.close();
        assertEquals(0, counter.get(), "inactive channel must release its in-flight slot");
    }

    @Test
    void nonRequestMessagesDoNotTouchCounter() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(0, counter, new Metrics()));

        assertTrue(channel.writeInbound("not an http request"));
        assertEquals("not an http request", channel.readInbound());
        assertEquals(0, counter.get());
        channel.finishAndReleaseAll();
    }

    @Test
    void counterIsNotDoubleReleased() {
        AtomicInteger counter = new AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(
            new InFlightGateHandler(1, counter, new Metrics()));

        channel.pipeline().fireChannelRead(newRequest());
        assertEquals(1, counter.get());
        channel.pipeline().fireChannelReadComplete();
        channel.pipeline().fireChannelReadComplete();
        channel.close();
        assertEquals(0, counter.get(), "counter must never go negative");
    }
}
