package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StandardProtocol
 */
class StandardProtocolTest {

    @Test
    void testGetName() {
        assertEquals("http", StandardProtocol.HTTP.getName());
        assertEquals("https", StandardProtocol.HTTPS.getName());
        assertEquals("tcp", StandardProtocol.TCP.getName());
        assertEquals("udp", StandardProtocol.UDP.getName());
        assertEquals("ws", StandardProtocol.WS.getName());
        assertEquals("wss", StandardProtocol.WSS.getName());
        assertEquals("kcp", StandardProtocol.KCP.getName());
        assertEquals("grpc", StandardProtocol.GRPC.getName());
        assertEquals("grpcs", StandardProtocol.GRPCS.getName());
        assertEquals("dubbo", StandardProtocol.DUBBO.getName());
        assertEquals("dubbo2", StandardProtocol.DUBBO2.getName());
    }

    @Test
    void testIsSecure() {
        assertTrue(StandardProtocol.HTTPS.isSecure());
        assertTrue(StandardProtocol.WSS.isSecure());
        assertTrue(StandardProtocol.GRPCS.isSecure());

        assertFalse(StandardProtocol.HTTP.isSecure());
        assertFalse(StandardProtocol.TCP.isSecure());
        assertFalse(StandardProtocol.UDP.isSecure());
        assertFalse(StandardProtocol.WS.isSecure());
        assertFalse(StandardProtocol.KCP.isSecure());
        assertFalse(StandardProtocol.GRPC.isSecure());
        assertFalse(StandardProtocol.DUBBO.isSecure());
        assertFalse(StandardProtocol.DUBBO2.isSecure());
    }

    @Test
    void testGetDefaultPort() {
        assertEquals(80, StandardProtocol.HTTP.getDefaultPort());
        assertEquals(443, StandardProtocol.HTTPS.getDefaultPort());
        assertEquals(80, StandardProtocol.WS.getDefaultPort());
        assertEquals(443, StandardProtocol.WSS.getDefaultPort());

        assertEquals(0, StandardProtocol.TCP.getDefaultPort());
        assertEquals(0, StandardProtocol.UDP.getDefaultPort());
        assertEquals(0, StandardProtocol.KCP.getDefaultPort());
        assertEquals(0, StandardProtocol.GRPC.getDefaultPort());
        assertEquals(0, StandardProtocol.GRPCS.getDefaultPort());
        assertEquals(0, StandardProtocol.DUBBO.getDefaultPort());
        assertEquals(0, StandardProtocol.DUBBO2.getDefaultPort());
    }

    @Test
    void testGetDescription() {
        assertEquals("Hypertext Transfer Protocol", StandardProtocol.HTTP.getDescription());
        assertEquals("Hypertext Transfer Protocol Secure", StandardProtocol.HTTPS.getDescription());
        assertEquals("Transmission Control Protocol", StandardProtocol.TCP.getDescription());
        assertEquals("User Datagram Protocol", StandardProtocol.UDP.getDescription());
        assertEquals("WebSocket Protocol", StandardProtocol.WS.getDescription());
        assertEquals("WebSocket Secure Protocol", StandardProtocol.WSS.getDescription());
        assertEquals("KCP Protocol", StandardProtocol.KCP.getDescription());
        assertEquals("gRPC Protocol", StandardProtocol.GRPC.getDescription());
        assertEquals("gRPC Secure Protocol", StandardProtocol.GRPCS.getDescription());
        assertEquals("Dubbo Protocol", StandardProtocol.DUBBO.getDescription());
        assertEquals("Dubbo 2 Protocol", StandardProtocol.DUBBO2.getDescription());
    }

    @Test
    void testFromName() {
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("http"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.fromName("https"));
        assertEquals(StandardProtocol.TCP, StandardProtocol.fromName("tcp"));

        // Case insensitive
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("HTTP"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.fromName("HTTPS"));
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("HtTp"));
    }

    @Test
    void testFromNameWithInvalidProtocol() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> StandardProtocol.fromName("invalid"));

        assertTrue(exception.getMessage().contains("Unknown protocol"));
        assertTrue(exception.getMessage().contains("invalid"));
    }

    @Test
    void testFromNameWithNull() {
        assertThrows(IllegalArgumentException.class,
                () -> StandardProtocol.fromName(null));
    }

    @Test
    void testHttp() {
        assertEquals(StandardProtocol.HTTP, StandardProtocol.http(false));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.http(true));
    }

    @Test
    void testWs() {
        assertEquals(StandardProtocol.WS, StandardProtocol.ws(false));
        assertEquals(StandardProtocol.WSS, StandardProtocol.ws(true));
    }

    @Test
    void testGrpc() {
        assertEquals(StandardProtocol.GRPC, StandardProtocol.grpc(false));
        assertEquals(StandardProtocol.GRPCS, StandardProtocol.grpc(true));
    }

    @Test
    void testEnumValues() {
        assertEquals(11, StandardProtocol.values().length);

        StandardProtocol[] protocols = StandardProtocol.values();
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.HTTP));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.HTTPS));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.TCP));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.UDP));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.WS));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.WSS));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.KCP));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.GRPC));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.GRPCS));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.DUBBO));
        assertTrue(java.util.Arrays.asList(protocols).contains(StandardProtocol.DUBBO2));
    }

    @Test
    void testValueOf() {
        assertEquals(StandardProtocol.HTTP, StandardProtocol.valueOf("HTTP"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.valueOf("HTTPS"));
        assertEquals(StandardProtocol.TCP, StandardProtocol.valueOf("TCP"));
        assertEquals(StandardProtocol.UDP, StandardProtocol.valueOf("UDP"));
        assertEquals(StandardProtocol.WS, StandardProtocol.valueOf("WS"));
        assertEquals(StandardProtocol.WSS, StandardProtocol.valueOf("WSS"));
        assertEquals(StandardProtocol.KCP, StandardProtocol.valueOf("KCP"));
        assertEquals(StandardProtocol.GRPC, StandardProtocol.valueOf("GRPC"));
        assertEquals(StandardProtocol.GRPCS, StandardProtocol.valueOf("GRPCS"));
        assertEquals(StandardProtocol.DUBBO, StandardProtocol.valueOf("DUBBO"));
        assertEquals(StandardProtocol.DUBBO2, StandardProtocol.valueOf("DUBBO2"));
    }

    @Test
    void testProtocolInterfaceImplementation() {
        Protocol httpProtocol = StandardProtocol.HTTP;
        Protocol httpsProtocol = StandardProtocol.HTTPS;

        assertEquals("http", httpProtocol.getName());
        assertEquals("https", httpsProtocol.getName());
        assertFalse(httpProtocol.isSecure());
        assertTrue(httpsProtocol.isSecure());
        assertEquals(80, httpProtocol.getDefaultPort());
        assertEquals(443, httpsProtocol.getDefaultPort());
        assertEquals("Hypertext Transfer Protocol", httpProtocol.getDescription());
        assertEquals("Hypertext Transfer Protocol Secure", httpsProtocol.getDescription());
    }

    @Test
    void testSecureProtocolsHaveSecurePortDefaults() {
        // All secure protocols should use port 443 or 0
        assertTrue(StandardProtocol.HTTPS.getDefaultPort() == 443);
        assertTrue(StandardProtocol.WSS.getDefaultPort() == 443);
        assertTrue(StandardProtocol.GRPCS.getDefaultPort() == 0);

        // And they should all report isSecure() = true
        assertTrue(StandardProtocol.HTTPS.isSecure());
        assertTrue(StandardProtocol.WSS.isSecure());
        assertTrue(StandardProtocol.GRPCS.isSecure());
    }

    @Test
    void testAllProtocolsHaveUniqueNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (StandardProtocol protocol : StandardProtocol.values()) {
            assertTrue(names.add(protocol.getName()),
                    "Duplicate protocol name: " + protocol.getName());
        }
        assertEquals(StandardProtocol.values().length, names.size());
    }

    @Test
    void testAllProtocolsHaveNonNullProperties() {
        for (StandardProtocol protocol : StandardProtocol.values()) {
            assertNotNull(protocol.getName());
            assertNotNull(protocol.getDescription());
            assertTrue(protocol.getDefaultPort() >= 0);
        }
    }
}
