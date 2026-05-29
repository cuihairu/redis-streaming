package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Protocol and StandardProtocol
 */
public class ProtocolTest {

    @Test
    public void testStandardProtocol() {
        // Test HTTP protocol
        StandardProtocol http = StandardProtocol.HTTP;
        assertEquals("http", http.getName());
        assertFalse(http.isSecure());
        assertEquals(80, http.getDefaultPort());
        assertEquals("Hypertext Transfer Protocol", http.getDescription());
        
        // Test HTTPS protocol
        StandardProtocol https = StandardProtocol.HTTPS;
        assertEquals("https", https.getName());
        assertTrue(https.isSecure());
        assertEquals(443, https.getDefaultPort());
        assertEquals("Hypertext Transfer Protocol Secure", https.getDescription());
        
        // Test TCP protocol
        StandardProtocol tcp = StandardProtocol.TCP;
        assertEquals("tcp", tcp.getName());
        assertFalse(tcp.isSecure());
        assertEquals(0, tcp.getDefaultPort());
        assertEquals("Transmission Control Protocol", tcp.getDescription());
        
        // Test UDP protocol
        StandardProtocol udp = StandardProtocol.UDP;
        assertEquals("udp", udp.getName());
        assertFalse(udp.isSecure());
        assertEquals(0, udp.getDefaultPort());
        assertEquals("User Datagram Protocol", udp.getDescription());
        
        // Test WebSocket protocol
        StandardProtocol ws = StandardProtocol.WS;
        assertEquals("ws", ws.getName());
        assertFalse(ws.isSecure());
        assertEquals(80, ws.getDefaultPort());
        assertEquals("WebSocket Protocol", ws.getDescription());
        
        // Test secure WebSocket protocol
        StandardProtocol wss = StandardProtocol.WSS;
        assertEquals("wss", wss.getName());
        assertTrue(wss.isSecure());
        assertEquals(443, wss.getDefaultPort());
        assertEquals("WebSocket Secure Protocol", wss.getDescription());
        
        // Test KCP protocol
        StandardProtocol kcp = StandardProtocol.KCP;
        assertEquals("kcp", kcp.getName());
        assertFalse(kcp.isSecure());
        assertEquals(0, kcp.getDefaultPort());
        assertEquals("KCP Protocol", kcp.getDescription());
        
        // Test gRPC protocol
        StandardProtocol grpc = StandardProtocol.GRPC;
        assertEquals("grpc", grpc.getName());
        assertFalse(grpc.isSecure());
        assertEquals(0, grpc.getDefaultPort());
        assertEquals("gRPC Protocol", grpc.getDescription());
        
        // Test secure gRPC protocol
        StandardProtocol grpcs = StandardProtocol.GRPCS;
        assertEquals("grpcs", grpcs.getName());
        assertTrue(grpcs.isSecure());
        assertEquals(0, grpcs.getDefaultPort());
        assertEquals("gRPC Secure Protocol", grpcs.getDescription());
        
        // Test Dubbo protocol
        StandardProtocol dubbo = StandardProtocol.DUBBO;
        assertEquals("dubbo", dubbo.getName());
        assertFalse(dubbo.isSecure());
        assertEquals(0, dubbo.getDefaultPort());
        assertEquals("Dubbo Protocol", dubbo.getDescription());
        
        // Test Dubbo2 protocol
        StandardProtocol dubbo2 = StandardProtocol.DUBBO2;
        assertEquals("dubbo2", dubbo2.getName());
        assertFalse(dubbo2.isSecure());
        assertEquals(0, dubbo2.getDefaultPort());
        assertEquals("Dubbo 2 Protocol", dubbo2.getDescription());
    }

    @Test
    public void testStandardProtocolFromName() {
        // Test getting protocol by name
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("http"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.fromName("https"));
        assertEquals(StandardProtocol.TCP, StandardProtocol.fromName("tcp"));
        assertEquals(StandardProtocol.UDP, StandardProtocol.fromName("udp"));
        assertEquals(StandardProtocol.WS, StandardProtocol.fromName("ws"));
        assertEquals(StandardProtocol.WSS, StandardProtocol.fromName("wss"));
        assertEquals(StandardProtocol.KCP, StandardProtocol.fromName("kcp"));
        assertEquals(StandardProtocol.GRPC, StandardProtocol.fromName("grpc"));
        assertEquals(StandardProtocol.GRPCS, StandardProtocol.fromName("grpcs"));
        assertEquals(StandardProtocol.DUBBO, StandardProtocol.fromName("dubbo"));
        assertEquals(StandardProtocol.DUBBO2, StandardProtocol.fromName("dubbo2"));
        
        // Test case-insensitive lookup
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("HTTP"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.fromName("HTTPS"));
        assertEquals(StandardProtocol.WS, StandardProtocol.fromName("WS"));
        assertEquals(StandardProtocol.WSS, StandardProtocol.fromName("WSS"));
        
        // Test unknown protocol
        assertThrows(IllegalArgumentException.class, () -> {
            StandardProtocol.fromName("unknown");
        });
    }

    @Test
    public void testStandardProtocolHttp() {
        // Test getting HTTP protocol by security flag
        assertEquals(StandardProtocol.HTTP, StandardProtocol.http(false));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.http(true));
    }
    
    @Test
    public void testStandardProtocolWs() {
        // Test getting WebSocket protocol by security flag
        assertEquals(StandardProtocol.WS, StandardProtocol.ws(false));
        assertEquals(StandardProtocol.WSS, StandardProtocol.ws(true));
    }
    
    @Test
    public void testStandardProtocolGrpc() {
        // Test getting gRPC protocol by security flag
        assertEquals(StandardProtocol.GRPC, StandardProtocol.grpc(false));
        assertEquals(StandardProtocol.GRPCS, StandardProtocol.grpc(true));
    }
}