package dev.mcmcp.screenshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * PNG encoder using JDK ImageIO with explicit sRGB metadata.
 * Receives top-down RGBA pixels from a {@link PixelFrame} (producers must
 * normalize orientation; Minecraft's Screenshot.takeScreenshot already
 * delivers top-down rows), optionally downscales, and produces sRGB PNG bytes.
 *
 * <p>This runs on the PNG worker thread, never on the render thread.
 * See IMPLEMENTATION.md §11.4.
 */
public final class PngEncoder {

    private PngEncoder() {}

    /**
     * Encode an RGBA pixel frame to PNG bytes.
     *
     * @param frame     the captured pixel frame (top-left origin, top-down rows)
     * @param maxWidth  maximum output width; downscales proportionally if source is wider
     * @return PNG bytes
     */
    public static byte[] encode(PixelFrame frame, int maxWidth) throws IOException {
        if (frame == null || frame.isClosed())
            throw new IOException("pixel frame is null or closed");
        int srcW = frame.width();
        int srcH = frame.height();
        if (srcW <= 0 || srcH <= 0)
            throw new IOException("invalid frame dimensions: " + srcW + "x" + srcH);

        int outW = Math.min(srcW, maxWidth);
        int outH = (int) Math.max(1, Math.round((double) srcH * outW / srcW));

        byte[] rgba = frame.rgba();

        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        int[] rgbBuf = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();

        if (outW == srcW && outH == srcH) {
            // Direct copy with RGBA→RGB
            for (int y = 0; y < outH; y++) {
                int srcY = y;
                for (int x = 0; x < outW; x++) {
                    int srcIdx = (srcY * srcW + x) * 4;
                    int r = rgba[srcIdx] & 0xFF;
                    int g = rgba[srcIdx + 1] & 0xFF;
                    int b = rgba[srcIdx + 2] & 0xFF;
                    rgbBuf[y * outW + x] = (r << 16) | (g << 8) | b;
                }
            }
        } else {
            // Simple nearest-neighbor downscale
            for (int y = 0; y < outH; y++) {
                int srcY = y * srcH / outH;
                for (int x = 0; x < outW; x++) {
                    int srcX = x * srcW / outW;
                    int srcIdx = (srcY * srcW + srcX) * 4;
                    int r = rgba[srcIdx] & 0xFF;
                    int g = rgba[srcIdx + 1] & 0xFF;
                    int b = rgba[srcIdx + 2] & 0xFF;
                    rgbBuf[y * outW + x] = (r << 16) | (g << 8) | b;
                }
            }
        }

        return writePngWithSrgb(img);
    }

    private static byte[] writePngWithSrgb(BufferedImage img) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("no PNG writer available");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        ImageTypeSpecifier type = new ImageTypeSpecifier(img);
        IIOMetadata meta = writer.getDefaultImageMetadata(type, param);

        // Set sRGB color space in metadata
        IIOMetadataNode root = new IIOMetadataNode("javax_imageio_1.0");
        IIOMetadataNode chroma = new IIOMetadataNode("Chroma");
        IIOMetadataNode colorSpaceType = new IIOMetadataNode("ColorSpaceType");
        colorSpaceType.setAttribute("name", "RGB");
        IIOMetadataNode numChannels = new IIOMetadataNode("NumChannels");
        numChannels.setAttribute("value", "3");
        chroma.appendChild(colorSpaceType);
        chroma.appendChild(numChannels);
        root.appendChild(chroma);
        meta.mergeTree("javax_imageio_1.0", root);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, meta), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
