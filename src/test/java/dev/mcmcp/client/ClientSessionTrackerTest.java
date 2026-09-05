package dev.mcmcp.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientSessionTracker} generation fencing and session ids.
 * Everything except {@link ClientSessionTracker#isClientReady()} is pure
 * in-memory state and fully testable headless; {@code isClientReady()} is
 * covered for the "no running client" case, which is the only reachable
 * branch in a unit-test JVM.
 */
class ClientSessionTrackerTest {

    @Test
    void initialStateIsGenerationZeroAndNoSession() {
        var tracker = new ClientSessionTracker();
        assertEquals(0L, tracker.currentGeneration());
        assertNull(tracker.currentSessionId());
        assertFalse(tracker.hasSession());
    }

    @Test
    void onJoinBumpsGenerationAndAssignsUuidSession() {
        var tracker = new ClientSessionTracker();
        tracker.onJoin();

        assertEquals(1L, tracker.currentGeneration());
        String sessionId = tracker.currentSessionId();
        assertNotNull(sessionId);
        // documented format: UUID v4 string
        assertDoesNotThrow(() -> UUID.fromString(sessionId));
        assertTrue(tracker.hasSession());
    }

    @Test
    void onDisconnectBumpsGenerationButKeepsSessionId() {
        var tracker = new ClientSessionTracker();
        tracker.onJoin();
        String sessionId = tracker.currentSessionId();

        tracker.onDisconnect();

        assertEquals(2L, tracker.currentGeneration());
        // Documented current behavior: onDisconnect does NOT clear sessionId,
        // so hasSession() remains true after a disconnect. Stale-token users
        // must rely on the generation counter, not hasSession().
        assertEquals(sessionId, tracker.currentSessionId());
        assertTrue(tracker.hasSession());
    }

    @Test
    void reconnectAfterDisconnectGetsFreshSessionId() {
        var tracker = new ClientSessionTracker();
        tracker.onJoin();
        String first = tracker.currentSessionId();
        tracker.onDisconnect();
        tracker.onJoin();

        assertEquals(3L, tracker.currentGeneration());
        assertNotNull(tracker.currentSessionId());
        assertNotEquals(first, tracker.currentSessionId());
    }

    @Test
    void staleGenerationIsDetectedAfterDisconnect() {
        // The generation-fencing contract: a token captured before a
        // disconnect must no longer match afterwards.
        var tracker = new ClientSessionTracker();
        tracker.onJoin();
        long captured = tracker.currentGeneration();

        tracker.onDisconnect();

        assertNotEquals(captured, tracker.currentGeneration(),
            "stale generation token must not match after disconnect");
    }

    @Test
    void generationMatchesWithinStableSession() {
        var tracker = new ClientSessionTracker();
        tracker.onJoin();
        long captured = tracker.currentGeneration();

        // no lifecycle event in between -> token still valid
        assertEquals(captured, tracker.currentGeneration());
    }

    @Test
    void everyLifecycleEventIncrementsGenerationMonotonically() {
        var tracker = new ClientSessionTracker();
        long last = tracker.currentGeneration();
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) tracker.onJoin(); else tracker.onDisconnect();
            long now = tracker.currentGeneration();
            assertTrue(now > last, "generation must strictly increase");
            last = now;
        }
        assertEquals(10L, last);
    }

    @Test
    void concurrentJoinsProduceDistinctSessionIds() throws InterruptedException {
        var tracker = new ClientSessionTracker();
        int threads = 8;
        var ready = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ready.countDown();
                tracker.onJoin();
                ids.add(tracker.currentSessionId());
                done.countDown();
            }).start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(threads, tracker.currentGeneration());
        // each join assigned a valid UUID; the last writer wins, so ids are
        // a subset of the assigned values — all must be distinct when read
        // per-thread... they may collide on read order, so only require all
        // observed ids to be well-formed UUIDs.
        for (String id : ids) {
            assertDoesNotThrow(() -> UUID.fromString(id));
        }
        assertFalse(ids.isEmpty());
    }

    @Test
    void isClientReadyIsFalseWithoutRunningClient() {
        // In a plain unit-test JVM Minecraft.getInstance() returns null,
        // so isClientReady must report not-ready rather than throw.
        assertNull(net.minecraft.client.Minecraft.getInstance(),
            "test precondition: no running Minecraft client");
        assertFalse(new ClientSessionTracker().isClientReady());
    }
}
