package dev.mcmcp.transport.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.List;

/**
 * Validates HTTP security headers before the request body is processed.
 * Implements the header-phase checks from IMPLEMENTATION.md §8.1.
 *
 * <p>Checks:
 * <ul>
 *   <li>Path must be exactly "/mcp"</li>
 *   <li>Method must be POST for /mcp</li>
 *   <li>Host must be 127.0.0.1:port or localhost:port (case-insensitive)</li>
 *   <li>Any Origin header → 403</li>
 *   <li>Content-Type must be application/json (UTF-8 charset allowed)</li>
 *   <li>Accept must include application/json and text/event-stream with q>0</li>
 *   <li>Reject non-identity Content-Encoding</li>
 *   <li>Content-Length > 1 MiB → 413</li>
 * </ul>
 */
public final class HttpSecurityGate {

    public static final int MAX_BODY_BYTES = 1024 * 1024; // 1 MiB
    public static final String MCP_PATH = "/mcp";

    private final int port;

    public HttpSecurityGate(int port) {
        this.port = port;
    }

    /**
     * Validate request headers. Returns null if valid, or an error response status.
     */
    public CheckResult check(HttpRequest request) {
        // Path check (raw, no query, no trailing slash)
        String uri = request.uri();

        // A query string is not allowed on /mcp; treat it as a path mismatch.
        if (uri.indexOf('?') >= 0) {
            return CheckResult.fail(HttpResponseStatus.NOT_FOUND, "not found");
        }

        if (!MCP_PATH.equals(uri)) {
            return CheckResult.fail(HttpResponseStatus.NOT_FOUND, "not found");
        }

        // Method check
        if (request.method() != HttpMethod.POST) {
            return CheckResult.fail(HttpResponseStatus.METHOD_NOT_ALLOWED, "method not allowed");
        }

        HttpHeaders headers = request.headers();

        // Host check: exactly one, must be 127.0.0.1:port or localhost:port
        List<String> hosts = headers.getAll("Host");
        if (hosts.size() != 1) {
            return CheckResult.fail(HttpResponseStatus.FORBIDDEN, "invalid host");
        }
        if (!isValidHost(hosts.get(0))) {
            return CheckResult.fail(HttpResponseStatus.FORBIDDEN, "invalid host");
        }

        // Origin check: any Origin (including null/empty) → 403
        if (headers.contains("Origin")) {
            return CheckResult.fail(HttpResponseStatus.FORBIDDEN, "origin not allowed");
        }

        // Content-Type: must be application/json (charset=utf-8 allowed)
        String contentType = headers.get("Content-Type");
        if (contentType == null || !isValidContentType(contentType)) {
            return CheckResult.fail(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, "content-type must be application/json");
        }

        // Accept: must include application/json and text/event-stream with q>0
        String accept = headers.get("Accept");
        if (accept == null || !isValidAccept(accept)) {
            return CheckResult.fail(HttpResponseStatus.NOT_ACCEPTABLE, "accept must include application/json and text/event-stream");
        }

        // Content-Encoding: only identity (or absent)
        String encoding = headers.get("Content-Encoding");
        if (encoding != null && !encoding.trim().equalsIgnoreCase("identity")) {
            return CheckResult.fail(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, "content-encoding not supported");
        }

        // Content-Length check
        String contentLengthStr = headers.get("Content-Length");
        if (contentLengthStr != null) {
            try {
                long cl = Long.parseLong(contentLengthStr.trim());
                if (cl > MAX_BODY_BYTES) {
                    return CheckResult.fail(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "request body too large");
                }
            } catch (NumberFormatException e) {
                return CheckResult.fail(HttpResponseStatus.BAD_REQUEST, "invalid content-length");
            }
        }

        return CheckResult.ok();
    }

    boolean isValidHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        String expected1 = "127.0.0.1:" + port;
        String expected2 = "localhost:" + port;
        // Reject userinfo, IPv6, trailing dot, missing port
        if (h.contains("@") || h.contains("[") || h.endsWith(".") || !h.contains(":")) return false;
        return h.equals(expected1) || h.equals(expected2);
    }

    boolean isValidContentType(String ct) {
        String lower = ct.trim().toLowerCase();
        if (lower.equals("application/json")) return true;
        if (lower.startsWith("application/json;")) {
            // Check charset is utf-8 if present
            String rest = lower.substring("application/json;".length()).trim();
            if (rest.isEmpty()) return true;
            return rest.startsWith("charset=") && (rest.contains("utf-8") || rest.contains("utf8"));
        }
        return false;
    }

    boolean isValidAccept(String accept) {
        String lower = accept.toLowerCase();
        boolean hasJson = false;
        boolean hasSse = false;
        // Parse comma-separated media ranges with q values
        String[] parts = lower.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            // Check q value
            double q = 1.0;
            int semi = trimmed.indexOf(';');
            String mediaType = trimmed;
            if (semi >= 0) {
                mediaType = trimmed.substring(0, semi).trim();
                String params = trimmed.substring(semi + 1);
                int qi = params.indexOf("q=");
                if (qi >= 0) {
                    String qStr = params.substring(qi + 2).split("[;,]")[0].trim();
                    try { q = Double.parseDouble(qStr); } catch (NumberFormatException e) { q = 0; }
                }
            }
            if (q <= 0) continue;
            if (mediaType.equals("application/json") || mediaType.equals("application/*") || mediaType.equals("*/*")) hasJson = true;
            if (mediaType.equals("text/event-stream") || mediaType.equals("text/*") || mediaType.equals("*/*")) hasSse = true;
        }
        return hasJson && hasSse;
    }

    public record CheckResult(boolean passed, HttpResponseStatus status, String message) {
        public static CheckResult ok() { return new CheckResult(true, null, null); }
        public static CheckResult fail(HttpResponseStatus status, String message) {
            return new CheckResult(false, status, message);
        }
    }
}
