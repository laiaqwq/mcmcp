package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MinecraftScreenshotAdapter}.
 *
 * <p>The BUSY fast-path runs before any Minecraft access, so the concurrency
 * gate is testable headless by holding the private {@code exclusiveLock}
 * permit. Everything past {@code scheduler.submit} requires a running client
 * ({@code Minecraft.getInstance().execute} → NPE), which is documented by
 * {@link #uncontendedCaptureFailsFastWithoutClientAndReleasesPermit()}.
 */
class MinecraftScreenshotAdapterTest {

    private static Semaphore lockOf(MinecraftScreenshotAdapter adapter) throws Exception {
        Field f = MinecraftScreenshotAdapter.class.getDeclaredField("exclusiveLock");
        f.setAccessible(true);
        return (Semaphore) f.get(adapter);
    }

    @Test
    void captureWhileAnotherInFlightReturnsBusy() throws Exception {
        var adapter = new MinecraftScreenshotAdapter(new MinecraftClientScheduler(0L));

        Semaphore lock = lockOf(adapter);
        assertTrue(lock.tryAcquire(), "test setup: hold the exclusive permit");
        try {
            CompletableFuture<MinecraftPorts.Result<ScreenshotResult, ToolError>> future =
                adapter.capture(1024, 5_000_000L);

            assertTrue(future.isDone(), "BUSY rejection must complete immediately");
            MinecraftPorts.Result<ScreenshotResult, ToolError> result = future.join();
            assertFalse(result.isSuccess());
            assertEquals(ToolErrorCode.BUSY, result.error().code());
            assertEquals("a screenshot is already in progress", result.error().message());
        } finally {
            lock.release();
        }
    }

    @Test
    void busyRejectionDoesNotConsumeThePermit() throws Exception {
        var adapter = new MinecraftScreenshotAdapter(new MinecraftClientScheduler(0L));
        Semaphore lock = lockOf(adapter);
        lock.acquire();
        try {
            adapter.capture(512, 0L).join();
            adapter.capture(512, 0L).join();
        } finally {
            // both calls were BUSY rejections; exactly our one held permit is outstanding
            assertEquals(0, lock.availablePermits());
            lock.release();
        }
        assertEquals(1, lock.availablePermits());
    }

    @Test
    void uncontendedCaptureFailsFastWithoutClientAndReleasesPermit() throws Exception {
        // With the lock free, capture() proceeds to scheduler.submit, which
        // dereferences Minecraft.getInstance().execute — NPE headless. The
        // adapter must release the acquired permit before the exception
        // propagates.
        var adapter = new MinecraftScreenshotAdapter(new MinecraftClientScheduler(0L));
        Semaphore lock = lockOf(adapter);
        assertNull(net.minecraft.client.Minecraft.getInstance(),
            "test precondition: no running client");
        assertEquals(1, lock.availablePermits(), "permit must be free before capture");

        assertThrows(NullPointerException.class, () -> adapter.capture(1024, 0L));

        assertEquals(1, lock.availablePermits(),
            "permit must be released after a synchronous submit failure");
    }

    @Test
    void shutdownIsNoOpAndSafe() {
        var adapter = new MinecraftScreenshotAdapter(new MinecraftClientScheduler(0L));
        assertDoesNotThrow(adapter::shutdown);
    }
}
