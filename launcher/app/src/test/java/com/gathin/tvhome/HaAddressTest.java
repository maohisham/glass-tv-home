package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class HaAddressTest {

    @Test
    public void httpLocalHostBecomesWs() {
        assertEquals("ws://homeassistant.local:8123/api/websocket",
                HaAddress.toWebSocketUrl("http://homeassistant.local:8123"));
    }

    @Test
    public void bareHostWithSpacesAndSlashAssumesHttp() {
        assertEquals("ws://192.168.0.10:8123/api/websocket",
                HaAddress.toWebSocketUrl("  192.168.0.10:8123/ "));
    }

    @Test
    public void uppercaseHttpsBecomesWss() {
        assertEquals("wss://ha.example.com/api/websocket",
                HaAddress.toWebSocketUrl("HTTPS://ha.example.com"));
    }

    @Test
    public void existingWebSocketPathIsNotDoubled() {
        assertEquals("ws://x.local:8123/api/websocket",
                HaAddress.toWebSocketUrl("ws://x.local:8123/api/websocket"));
    }

    @Test
    public void publicHostOverPlainHttpIsRefused() {
        assertNull(HaAddress.toWebSocketUrl("http://ha.example.com"));
        assertNull(HaAddress.toWebSocketUrl("ws://8.8.8.8:8123"));
    }

    @Test
    public void emptyNullAndOtherSchemesAreRefused() {
        assertNull(HaAddress.toWebSocketUrl(""));
        assertNull(HaAddress.toWebSocketUrl(null));
        assertNull(HaAddress.toWebSocketUrl("ftp://x"));
    }

    @Test
    public void privateHosts() {
        for (String h : new String[]{"10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.126",
                "169.254.1.1", "127.0.0.1", "localhost", "homeassistant", "ha.local", "ha.lan"}) {
            assertTrue(h, HaAddress.isPrivateHost(h));
        }
    }

    @Test
    public void publicHosts() {
        for (String h : new String[]{"172.32.0.1", "8.8.8.8", "ha.example.com"}) {
            assertFalse(h, HaAddress.isPrivateHost(h));
        }
    }

    @Test
    public void httpBaseDropsPathAndTrailingSlash() {
        assertEquals("https://ha.example.com", HaAddress.toHttpBase("https://ha.example.com/"));
        assertEquals("http://192.168.0.10:8123", HaAddress.toHttpBase("192.168.0.10:8123"));
    }
}
