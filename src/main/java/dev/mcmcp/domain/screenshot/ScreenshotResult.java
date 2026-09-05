package dev.mcmcp.domain.screenshot;

/**
 * Immutable screenshot result: metadata + raw PNG bytes.
 * The PNG bytes are owned by this result and must not be cached after the response is sent.
 */
public record ScreenshotResult(
    String capturedAt,
    int width,
    int height,
    String mimeType,
    int sourceWidth,
    int sourceHeight,
    byte[] pngBytes
) {
    public static final String MIME_TYPE = "image/png";
}
