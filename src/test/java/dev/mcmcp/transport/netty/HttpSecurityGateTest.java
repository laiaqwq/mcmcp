package dev.mcmcp.transport.netty;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpSecurityGateTest {

    private final HttpSecurityGate gate = new HttpSecurityGate(25585);

    @Test
    void validHostLoopback() {
        assertTrue(gate.isValidHost("127.0.0.1:25585"));
    }

    @Test
    void validHostLocalhost() {
        assertTrue(gate.isValidHost("localhost:25585"));
    }

    @Test
    void validHostCaseInsensitive() {
        assertTrue(gate.isValidHost("LOCALHOST:25585"));
    }

    @Test
    void rejectsWrongPort() {
        assertFalse(gate.isValidHost("127.0.0.1:8080"));
    }

    @Test
    void rejectsMissingPort() {
        assertFalse(gate.isValidHost("127.0.0.1"));
    }

    @Test
    void rejectsIpv6() {
        assertFalse(gate.isValidHost("[::1]:25585"));
    }

    @Test
    void rejectsUserinfo() {
        assertFalse(gate.isValidHost("user@127.0.0.1:25585"));
    }

    @Test
    void rejectsTrailingDot() {
        assertFalse(gate.isValidHost("localhost.:25585"));
    }

    @Test
    void rejectsLanIp() {
        assertFalse(gate.isValidHost("192.168.1.1:25585"));
    }

    @Test
    void validContentType() {
        assertTrue(gate.isValidContentType("application/json"));
        assertTrue(gate.isValidContentType("application/json; charset=utf-8"));
        assertTrue(gate.isValidContentType("application/json;charset=UTF-8"));
    }

    @Test
    void invalidContentType() {
        assertFalse(gate.isValidContentType("text/plain"));
        assertFalse(gate.isValidContentType("application/xml"));
    }

    @Test
    void validAccept() {
        assertTrue(gate.isValidAccept("application/json, text/event-stream"));
        assertTrue(gate.isValidAccept("application/json;q=1, text/event-stream;q=1"));
        assertTrue(gate.isValidAccept("application/json, text/event-stream, text/plain"));
    }

    @Test
    void invalidAcceptMissingSse() {
        assertFalse(gate.isValidAccept("application/json"));
    }

    @Test
    void invalidAcceptQZero() {
        assertFalse(gate.isValidAccept("application/json;q=0, text/event-stream"));
    }

    @Test
    void validAcceptWildcard() {
        assertTrue(gate.isValidAccept("*/*"));
    }
}
