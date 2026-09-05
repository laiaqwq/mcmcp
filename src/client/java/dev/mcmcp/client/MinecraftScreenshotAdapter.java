package dev.mcmcp.client;

import dev.mcmcp.application.MinecraftPorts;
import dev.mcmcp.domain.error.ToolError;
import dev.mcmcp.domain.error.ToolErrorCode;
import dev.mcmcp.domain.screenshot.ScreenshotResult;
import dev.mcmcp.observability.McmcpLogger;
import dev.mcmcp.screenshot.PixelFrame;
import dev.mcmcp.screenshot.PngEncoder;
import dev.mcmcp.util.TimeUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * Screenshot port implementation. Captures the framebuffer on the render thread
 * using Minecraft's async GPU readback, then encodes PNG on a worker thread.
 * Concurrency limited to 1 via semaphore.
 * See PRD §7.4 and IMPLEMENTATION.md §11.4.
 */
public final class MinecraftScreenshotAdapter implements MinecraftPorts.ScreenshotPort {

    private final MinecraftClientScheduler scheduler;
    private final Semaphore exclusiveLock = new Semaphore(1, true);

    public MinecraftScreenshotAdapter(MinecraftClientScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public CompletableFuture<MinecraftPorts.Result<ScreenshotResult, ToolError>> capture(
        int maxWidth, long deadlineNanos
    ) {
        if (!exclusiveLock.tryAcquire()) {
            return CompletableFuture.completedFuture(
                MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.BUSY,
                    "a screenshot is already in progress")));
        }

        // Submit the GPU capture on the render thread. The callback from
        // Screenshot.takeScreenshot fires asynchronously on a future render tick
        // once the GPU copy completes. We use a CompletableFuture to bridge
        // the async callback into the result.
        CompletableFuture<MinecraftPorts.Result<ScreenshotResult, ToolError>> captureFuture =
            new CompletableFuture<>();

        scheduler.submit(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return MinecraftPorts.Result.<Object, ToolError>err(
                    ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE, "minecraft client not available"));
            }

            RenderTarget fb = mc.gameRenderer.mainRenderTarget();
            if (fb == null || fb.width <= 0 || fb.height <= 0) {
                return MinecraftPorts.Result.<Object, ToolError>err(
                    ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE,
                        "framebuffer or render context unavailable"));
            }

            String capturedAt = TimeUtil.nowUtc();

            // Screenshot.takeScreenshot queues an async GPU readback.
            // The consumer fires when the readback is complete.
            Screenshot.takeScreenshot(fb, nativeImage -> {
                try {
                    if (nativeImage == null) {
                        captureFuture.complete(MinecraftPorts.Result.err(
                            ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE,
                                "screenshot readback returned null")));
                        return;
                    }

                    try (nativeImage) {
                        int imgWidth = nativeImage.getWidth();
                        int imgHeight = nativeImage.getHeight();
                        if (imgWidth <= 0 || imgHeight <= 0) {
                            captureFuture.complete(MinecraftPorts.Result.err(
                                ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE,
                                    "screenshot has invalid dimensions")));
                            return;
                        }

                        int[] pixels = nativeImage.makePixelArray();
                        byte[] rgba = new byte[imgWidth * imgHeight * 4];
                        for (int i = 0; i < pixels.length; i++) {
                            int p = pixels[i];
                            rgba[i * 4] = (byte) ((p >> 16) & 0xFF); // R
                            rgba[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
                            rgba[i * 4 + 2] = (byte) (p & 0xFF);         // B
                            rgba[i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
                        }

                        PixelFrame frame = new PixelFrame(rgba, imgWidth, imgHeight, capturedAt);

                        // Encode PNG off the render thread
                        CompletableFuture.runAsync(() -> {
                            try {
                                byte[] png = PngEncoder.encode(frame, maxWidth);
                                int outWidth = Math.min(frame.width(), maxWidth);
                                int outHeight = (int) Math.max(1, Math.round(
                                    (double) frame.height() * outWidth / frame.width()));
                                var result = new ScreenshotResult(
                                    frame.capturedAt(),
                                    outWidth, outHeight,
                                    ScreenshotResult.MIME_TYPE,
                                    frame.width(), frame.height(),
                                    png
                                );
                                captureFuture.complete(MinecraftPorts.Result.ok(result));
                            } catch (Exception e) {
                                McmcpLogger.error("png_encode_error", "error", e.getMessage());
                                captureFuture.complete(MinecraftPorts.Result.err(
                                    ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE,
                                        "PNG encoding failed")));
                            } finally {
                                frame.close();
                                exclusiveLock.release();
                            }
                        });
                    }
                } catch (Exception e) {
                    McmcpLogger.error("screenshot_callback_error", "error", e.getMessage());
                    captureFuture.complete(MinecraftPorts.Result.err(
                        ToolError.of(ToolErrorCode.SCREENSHOT_UNAVAILABLE,
                            "screenshot callback error: " + e.getMessage())));
                    exclusiveLock.release();
                }
            });

            // Return a sentinel — the actual result comes via captureFuture
            return MinecraftPorts.Result.ok(new Object());
        }, deadlineNanos).handle((submitResult, throwable) -> {
            if (throwable != null) {
                exclusiveLock.release();
                if (throwable instanceof TimeoutException) {
                    return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.TIMEOUT,
                        "screenshot submission timed out"));
                }
                return MinecraftPorts.Result.err(ToolError.internal("internal error"));
            }
            // The submit succeeded; now wait for the async capture to complete.
            // The capture callback fires on a future render tick, so we can't block here.
            // Instead, return the captureFuture's result when it completes.
            return null; // placeholder — we'll chain below
        });

        // Chain: wait for the async capture to complete with a timeout
        long timeoutNanos = deadlineNanos > 0 ? deadlineNanos :
            TimeUnit.MILLISECONDS.toNanos(10000); // 10s default
        long deadline = System.nanoTime() + timeoutNanos;

        return captureFuture.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS)
            .handle((result, throwable) -> {
                if (throwable != null) {
                    exclusiveLock.release();
                    if (throwable instanceof TimeoutException) {
                        return MinecraftPorts.Result.err(ToolError.of(ToolErrorCode.TIMEOUT,
                            "screenshot capture timed out"));
                    }
                    return MinecraftPorts.Result.err(ToolError.internal("internal error"));
                }
                return result;
            });
    }

    public void shutdown() {}
}
