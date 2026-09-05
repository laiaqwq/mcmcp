package dev.mcmcp.client;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MinecraftClientScheduler}.
 *
 * <p>UNTESTABLE-WITHOUT-CLIENT: every public method dereferences
 * {@code Minecraft.getInstance()} immediately ({@code client.execute(...)}
 * or {@code client.isSameThread()}), so without a running client the calls
 * fail with {@link NullPointerException} before any scheduler logic
 * (deadline bookkeeping, watchdog, cancellation) can run. These tests
 * pin down that boundary explicitly.
 *
 * <p>Refactor that would enable real testing: inject
 * {@code Supplier<Minecraft>} (or a thin {@code ClientThreadExecutor}
 * interface wrapping {@code execute}/{@code isSameThread}) via the
 * constructor — then a test could supply an in-line executor and exercise
 * timeout, cancellation and exception propagation.
 */
class MinecraftClientSchedulerTest {

    @Test
    void submitFailsFastWithoutClient() {
        var scheduler = new MinecraftClientScheduler(1_000_000L);
        assertNull(net.minecraft.client.Minecraft.getInstance(),
            "test precondition: no running client");
        assertThrows(NullPointerException.class,
            () -> scheduler.submit(() -> "x", 1_000_000L));
    }

    @Test
    void submitWithDefaultTimeoutFailsFastWithoutClient() {
        var scheduler = new MinecraftClientScheduler(0L);
        assertThrows(NullPointerException.class,
            () -> scheduler.submit(() -> 42));
    }

    @Test
    void submitAndWaitFailsFastWithoutClient() {
        var scheduler = new MinecraftClientScheduler(1_000_000L);
        assertThrows(NullPointerException.class,
            () -> scheduler.submitAndWait(() -> "x", 1_000_000L));
    }

    @Test
    void runFailsFastWithoutClient() {
        var scheduler = new MinecraftClientScheduler(1_000_000L);
        assertThrows(NullPointerException.class,
            () -> scheduler.run(() -> {}, 1_000_000L));
    }

    @Test
    void constructorAcceptsAnyDefaultTimeout() {
        // <= 0 means "no default timeout"; constructor performs no validation
        // and does not touch the client.
        assertDoesNotThrow(() -> new MinecraftClientScheduler(0L));
        assertDoesNotThrow(() -> new MinecraftClientScheduler(-1L));
        assertDoesNotThrow(() -> new MinecraftClientScheduler(Long.MAX_VALUE));
    }
}
