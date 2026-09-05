package dev.mcmcp.screenshot;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PngEncoderTest {

    @Test
    void encodeProducesValidPng() throws IOException {
        int w = 4, h = 3;
        byte[] rgba = new byte[w * h * 4];
        // Fill with red
        for (int i = 0; i < w * h; i++) {
            rgba[i * 4] = (byte) 255;     // R
            rgba[i * 4 + 1] = 0;          // G
            rgba[i * 4 + 2] = 0;          // B
            rgba[i * 4 + 3] = (byte) 255; // A
        }
        var frame = new PixelFrame(rgba, w, h, "2026-01-01T00:00:00Z");
        byte[] png = PngEncoder.encode(frame, 3840);
        assertNotNull(png);
        assertTrue(png.length > 0);

        // Verify it's a valid PNG
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        // Top-left pixel should be red (after vertical flip, bottom-left was red too)
        int rgb = img.getRGB(0, 0);
        assertEquals(255, (rgb >> 16) & 0xFF); // R
        assertEquals(0, (rgb >> 8) & 0xFF);    // G
        assertEquals(0, rgb & 0xFF);           // B

        frame.close();
    }

    @Test
    void encodeDownscales() throws IOException {
        int w = 100, h = 50;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h * 4; i++) rgba[i] = (byte) 128;
        var frame = new PixelFrame(rgba, w, h, "2026-01-01T00:00:00Z");
        byte[] png = PngEncoder.encode(frame, 50);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(50, img.getWidth());
        assertEquals(25, img.getHeight());
        frame.close();
    }

    @Test
    void encodeRejectsClosedFrame() {
        var frame = new PixelFrame(new byte[16], 2, 2, "t");
        frame.close();
        assertThrows(IOException.class, () -> PngEncoder.encode(frame, 3840));
    }

    @Test
    void encodeRejectsZeroDimensions() {
        var frame = new PixelFrame(new byte[0], 0, 0, "t");
        assertThrows(IOException.class, () -> PngEncoder.encode(frame, 3840));
        frame.close();
    }
}
