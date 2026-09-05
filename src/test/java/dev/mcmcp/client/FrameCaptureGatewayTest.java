package dev.mcmcp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FrameCaptureGateway}.
 *
 * <p>Only the "no client" branch is reachable headless: with
 * {@code Minecraft.getInstance() == null} capture must return {@code null}
 * rather than throw. The GPU readback path ({@code mainRenderTarget},
 * {@code Screenshot.takeScreenshot}, {@code NativeImage}) requires a live
 * render thread and cannot be exercised in a unit test.
 */
class FrameCaptureGatewayTest {

    @Test
    void captureReturnsNullWithoutRunningClient() {
        assertNull(net.minecraft.client.Minecraft.getInstance(),
            "test precondition: no running client");
        assertNull(FrameCaptureGateway.capture("2026-01-01T00:00:00Z"));
    }

    @Test
    void captureAcceptsAnyTimestampStringWithoutClient() {
        // capturedAt is only stored on the returned frame; with no client the
        // early return makes any value (including null) safe.
        assertNull(FrameCaptureGateway.capture(null));
    }
}
