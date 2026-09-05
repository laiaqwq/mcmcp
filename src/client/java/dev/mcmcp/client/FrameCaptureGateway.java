package dev.mcmcp.client;

import dev.mcmcp.screenshot.PixelFrame;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * Captures a pixel frame from the main render target on the render thread.
 * Uses Blaze3D/RenderTarget abstraction (no bare OpenGL). See IMPLEMENTATION.md §11.4.
 *
 * <p>This runs on the render thread. The pixel data is copied into a mod-owned byte array
 * and the GPU resource is released immediately.
 */
public final class FrameCaptureGateway {

    private FrameCaptureGateway() {}

    /**
     * Capture the current main framebuffer as RGBA bytes.
     * Must be called on the render thread.
     */
    public static PixelFrame capture(String capturedAt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;

        RenderTarget fb = mc.gameRenderer.mainRenderTarget();
        if (fb == null || fb.width <= 0 || fb.height <= 0) return null;

        int width = fb.width;
        int height = fb.height;

        // Use Minecraft's Screenshot.takeScreenshot which handles GPU readback
        // via the Blaze3D abstraction (works for both OpenGL and Vulkan backends).
        // The callback receives a NativeImage; we copy pixels into our own buffer.
        AtomicReference<NativeImage> imageRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Screenshot.takeScreenshot(fb, nativeImage -> {
                imageRef.set(nativeImage);
                latch.countDown();
            });

            // The callback may be synchronous or asynchronous depending on the backend.
            // Wait briefly for completion.
            if (!latch.await(java.util.concurrent.TimeUnit.SECONDS.toNanos(2),
                java.util.concurrent.TimeUnit.NANOSECONDS)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        NativeImage nativeImage = imageRef.get();
        if (nativeImage == null) return null;

        try (nativeImage) {
            int imgWidth = nativeImage.getWidth();
            int imgHeight = nativeImage.getHeight();
            if (imgWidth <= 0 || imgHeight <= 0) return null;

            int[] pixels = nativeImage.makePixelArray();
            byte[] rgba = new byte[imgWidth * imgHeight * 4];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                rgba[i * 4] = (byte) ((p >> 16) & 0xFF); // R
                rgba[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
                rgba[i * 4 + 2] = (byte) (p & 0xFF);         // B
                rgba[i * 4 + 3] = (byte) ((p >> 24) & 0xFF); // A
            }
            return new PixelFrame(rgba, imgWidth, imgHeight, capturedAt);
        } catch (Exception e) {
            return null;
        }
    }
}
