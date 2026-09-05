package dev.mcmcp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McmcpConfigTest {

    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> McmcpConfig.defaults().validate());
    }

    @Test
    void rejectsInvalidPort() {
        var config = new McmcpConfig(true, 80, 1000, 5000, 20, 1);
        assertThrows(McmcpConfig.ConfigException.class, config::validate);
    }

    @Test
    void rejectsPortTooHigh() {
        var config = new McmcpConfig(true, 70000, 1000, 5000, 20, 1);
        assertThrows(McmcpConfig.ConfigException.class, config::validate);
    }

    @Test
    void rejectsSmallChatBuffer() {
        var config = new McmcpConfig(true, 25585, 50, 5000, 20, 1);
        assertThrows(McmcpConfig.ConfigException.class, config::validate);
    }

    @Test
    void rejectsLargeChatBuffer() {
        var config = new McmcpConfig(true, 25585, 20000, 5000, 20, 1);
        assertThrows(McmcpConfig.ConfigException.class, config::validate);
    }

    @Test
    void rejectsZeroRps() {
        var config = new McmcpConfig(true, 25585, 1000, 5000, 0, 1);
        assertThrows(McmcpConfig.ConfigException.class, config::validate);
    }
}
