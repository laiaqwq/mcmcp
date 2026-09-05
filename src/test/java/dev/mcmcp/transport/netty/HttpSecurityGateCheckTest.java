package dev.mcmcp.transport.netty;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link HttpSecurityGate#check} end-to-end with real request objects.
 * Complements {@link HttpSecurityGateTest}, which covers only the parsing helpers.
 */
class HttpSecurityGateCheckTest {

    private static final int PORT = 25585;
    private final HttpSecurityGate gate = new HttpSecurityGate(PORT);

    private static FullHttpRequest request(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }

    private static FullHttpRequest validRequest() {
        FullHttpRequest req = request(HttpMethod.POST, "/mcp");
        req.headers().set("Host", "127.0.0.1:" + PORT);
        req.headers().set("Content-Type", "application/json");
        req.headers().set("Accept", "application/json, text/event-stream");
        return req;
    }

    private static HttpSecurityGate.CheckResult check(HttpSecurityGate gate, FullHttpRequest req) {
        try {
            return gate.check(req);
        } finally {
            req.release();
        }
    }

    private static HttpResponseStatus statusOf(HttpSecurityGate gate, FullHttpRequest req) {
        var result = check(gate, req);
        assertFalse(result.passed(), "expected rejection");
        return result.status();
    }

    @Test
    void acceptsFullyValidRequest() {
        var result = check(gate, validRequest());
        assertTrue(result.passed());
        assertNull(result.status());
    }

    @Test
    void acceptsLocalhostHost() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "localhost:" + PORT);
        assertTrue(check(gate, req).passed());
    }

    @Test
    void acceptsContentTypeWithUtf8Charset() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Type", "application/json; charset=utf-8");
        assertTrue(check(gate, req).passed());
    }

    @Test
    void acceptsContentLengthWithinLimit() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Length", "1024");
        assertTrue(check(gate, req).passed());
    }

    @Test
    void acceptsIdentityContentEncoding() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Encoding", "identity");
        assertTrue(check(gate, req).passed());
    }

    @Test
    void rejectsWrongMethod() {
        FullHttpRequest req = validRequest();
        req.setMethod(HttpMethod.GET);
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, statusOf(gate, req));
    }

    @Test
    void rejectsPutMethod() {
        FullHttpRequest req = validRequest();
        req.setMethod(HttpMethod.PUT);
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, statusOf(gate, req));
    }

    @Test
    void rejectsWrongPath() {
        FullHttpRequest req = validRequest();
        req.setUri("/other");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void rejectsTrailingSlashPath() {
        FullHttpRequest req = validRequest();
        req.setUri("/mcp/");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void rejectsRootPath() {
        FullHttpRequest req = validRequest();
        req.setUri("/");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void pathCheckTakesPrecedenceOverMethod() {
        // GET on wrong path -> 404, not 405 (path is checked first)
        FullHttpRequest req = request(HttpMethod.GET, "/other");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void rejectsQueryStringOnPath() {
        FullHttpRequest req = validRequest();
        req.setUri("/mcp?foo=bar");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void rejectsEmptyQueryStringOnPath() {
        FullHttpRequest req = validRequest();
        req.setUri("/mcp?");
        assertEquals(HttpResponseStatus.NOT_FOUND, statusOf(gate, req));
    }

    @Test
    void rejectsMissingHost() {
        FullHttpRequest req = validRequest();
        req.headers().remove("Host");
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsDuplicateHostHeaders() {
        FullHttpRequest req = validRequest();
        req.headers().add("Host", "127.0.0.1:" + PORT);
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsNonLoopbackHost() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "example.com:" + PORT);
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsLanHost() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "192.168.1.50:" + PORT);
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsHostWithWrongPort() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "127.0.0.1:8080");
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsHostWithoutPort() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "127.0.0.1");
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsIpv6Host() {
        FullHttpRequest req = validRequest();
        req.headers().set("Host", "[::1]:" + PORT);
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsOriginHeader() {
        FullHttpRequest req = validRequest();
        req.headers().set("Origin", "http://example.com");
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsNullOriginHeader() {
        FullHttpRequest req = validRequest();
        // Presence of the header is what matters, not its value
        req.headers().set("Origin", "null");
        assertEquals(HttpResponseStatus.FORBIDDEN, statusOf(gate, req));
    }

    @Test
    void rejectsMissingContentType() {
        FullHttpRequest req = validRequest();
        req.headers().remove("Content-Type");
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(gate, req));
    }

    @Test
    void rejectsWrongContentType() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Type", "text/plain");
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(gate, req));
    }

    @Test
    void rejectsNonUtf8Charset() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Type", "application/json; charset=iso-8859-1");
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(gate, req));
    }

    @Test
    void rejectsMissingAccept() {
        FullHttpRequest req = validRequest();
        req.headers().remove("Accept");
        assertEquals(HttpResponseStatus.NOT_ACCEPTABLE, statusOf(gate, req));
    }

    @Test
    void rejectsAcceptMissingEventStream() {
        FullHttpRequest req = validRequest();
        req.headers().set("Accept", "application/json");
        assertEquals(HttpResponseStatus.NOT_ACCEPTABLE, statusOf(gate, req));
    }

    @Test
    void rejectsAcceptWithQZero() {
        FullHttpRequest req = validRequest();
        req.headers().set("Accept", "application/json;q=0, text/event-stream;q=0");
        assertEquals(HttpResponseStatus.NOT_ACCEPTABLE, statusOf(gate, req));
    }

    @Test
    void acceptsWildcardAccept() {
        FullHttpRequest req = validRequest();
        req.headers().set("Accept", "*/*");
        assertTrue(check(gate, req).passed());
    }

    @Test
    void rejectsGzipContentEncoding() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Encoding", "gzip");
        assertEquals(HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE, statusOf(gate, req));
    }

    @Test
    void rejectsOversizedContentLength() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Length", String.valueOf(HttpSecurityGate.MAX_BODY_BYTES + 1));
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, statusOf(gate, req));
    }

    @Test
    void acceptsContentLengthAtLimit() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Length", String.valueOf(HttpSecurityGate.MAX_BODY_BYTES));
        assertTrue(check(gate, req).passed());
    }

    @Test
    void rejectsMalformedContentLength() {
        FullHttpRequest req = validRequest();
        req.headers().set("Content-Length", "not-a-number");
        assertEquals(HttpResponseStatus.BAD_REQUEST, statusOf(gate, req));
    }

    @Test
    void checkResultCarriesMessage() {
        var result = check(gate, request(HttpMethod.GET, "/"));
        assertFalse(result.passed());
        assertNotNull(result.message());
        assertEquals(HttpResponseStatus.NOT_FOUND, result.status());
    }
}
