package dev.mcmcp.screenshot;

/**
 * Owned pixel frame captured from the render thread. Implements AutoCloseable
 * to ensure the byte buffer is released on every path (success, error, cancellation).
 * See IMPLEMENTATION.md §11.4.
 */
public final class PixelFrame implements AutoCloseable {
    private final byte[] rgba;
    private final int width;
    private final int height;
    private final String capturedAt;
    private boolean closed = false;

    public PixelFrame(byte[] rgba, int width, int height, String capturedAt) {
        this.rgba = rgba;
        this.width = width;
        this.height = height;
        this.capturedAt = capturedAt;
    }

    public byte[] rgba() { return rgba; }
    public int width() { return width; }
    public int height() { return height; }
    public String capturedAt() { return capturedAt; }

    @Override
    public void close() {
        closed = true;
        // Clear the array to help GC
        java.util.Arrays.fill(rgba, (byte) 0);
    }

    public boolean isClosed() { return closed; }
}
