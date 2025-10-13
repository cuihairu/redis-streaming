package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Protocol和StandardProtocol的单元测试
 */
public class ProtocolTest {

    @Test
    public void testStandardProtocol() {
        // 测试HTTP协议
        StandardProtocol http = StandardProtocol.HTTP;
        assertEquals("http", http.getName());
        assertFalse(http.isSecure());
        assertEquals(80, http.getDefaultPort());
        assertEquals("Hypertext Transfer Protocol", http.getDescription());
        
        // 测试HTTPS协议
        StandardProtocol https = StandardProtocol.HTTPS;
        assertEquals("https", https.getName());
        assertTrue(https.isSecure());
        assertEquals(443, https.getDefaultPort());
        assertEquals("Hypertext Transfer Protocol Secure", https.getDescription());
        
        // 测试TCP协议
        StandardProtocol tcp = StandardProtocol.TCP;
        assertEquals("tcp", tcp.getName());
        assertFalse(tcp.isSecure());
        assertEquals(0, tcp.getDefaultPort());
        assertEquals("Transmission Control Protocol", tcp.getDescription());
        
        // 测试UDP协议
        StandardProtocol udp = StandardProtocol.UDP;
        assertEquals("udp", udp.getName());
        assertFalse(udp.isSecure());
        assertEquals(0, udp.getDefaultPort());
        assertEquals("User Datagram Protocol", udp.getDescription());
        
        // 测试WebSocket协议
        StandardProtocol ws = StandardProtocol.WS;
        assertEquals("ws", ws.getName());
        assertFalse(ws.isSecure());
        assertEquals(80, ws.getDefaultPort());
        assertEquals("WebSocket Protocol", ws.getDescription());
        
        // 测试安全WebSocket协议
        StandardProtocol wss = StandardProtocol.WSS;
        assertEquals("wss", wss.getName());
        assertTrue(wss.isSecure());
        assertEquals(443, wss.getDefaultPort());
        assertEquals("WebSocket Secure Protocol", wss.getDescription());
        
        // 测试KCP协议
        StandardProtocol kcp = StandardProtocol.KCP;
        assertEquals("kcp", kcp.getName());
        assertFalse(kcp.isSecure());
        assertEquals(0, kcp.getDefaultPort());
        assertEquals("KCP Protocol", kcp.getDescription());
        
        // 测试gRPC协议
        StandardProtocol grpc = StandardProtocol.GRPC;
        assertEquals("grpc", grpc.getName());
        assertFalse(grpc.isSecure());
        assertEquals(0, grpc.getDefaultPort());
        assertEquals("gRPC Protocol", grpc.getDescription());
        
        // 测试安全gRPC协议
        StandardProtocol grpcs = StandardProtocol.GRPCS;
        assertEquals("grpcs", grpcs.getName());
        assertTrue(grpcs.isSecure());
        assertEquals(0, grpcs.getDefaultPort());
        assertEquals("gRPC Secure Protocol", grpcs.getDescription());
        
        // 测试Dubbo协议
        StandardProtocol dubbo = StandardProtocol.DUBBO;
        assertEquals("dubbo", dubbo.getName());
        assertFalse(dubbo.isSecure());
        assertEquals(0, dubbo.getDefaultPort());
        assertEquals("Dubbo Protocol", dubbo.getDescription());
        
        // 测试Dubbo2协议
        StandardProtocol dubbo2 = StandardProtocol.DUBBO2;
        assertEquals("dubbo2", dubbo2.getName());
        assertFalse(dubbo2.isSecure());
        assertEquals(0, dubbo2.getDefaultPort());
        assertEquals("Dubbo 2 Protocol", dubbo2.getDescription());
    }

    @Test
    public void testStandardProtocolFromName() {
        // 测试根据名称获取协议
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
        
        // 测试大小写不敏感
        assertEquals(StandardProtocol.HTTP, StandardProtocol.fromName("HTTP"));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.fromName("HTTPS"));
        assertEquals(StandardProtocol.WS, StandardProtocol.fromName("WS"));
        assertEquals(StandardProtocol.WSS, StandardProtocol.fromName("WSS"));
        
        // 测试未知协议
        assertThrows(IllegalArgumentException.class, () -> {
            StandardProtocol.fromName("unknown");
        });
    }

    @Test
    public void testStandardProtocolHttp() {
        // 测试根据安全属性获取HTTP协议
        assertEquals(StandardProtocol.HTTP, StandardProtocol.http(false));
        assertEquals(StandardProtocol.HTTPS, StandardProtocol.http(true));
    }
    
    @Test
    public void testStandardProtocolWs() {
        // 测试根据安全属性获取WebSocket协议
        assertEquals(StandardProtocol.WS, StandardProtocol.ws(false));
        assertEquals(StandardProtocol.WSS, StandardProtocol.ws(true));
    }
    
    @Test
    public void testStandardProtocolGrpc() {
        // 测试根据安全属性获取gRPC协议
        assertEquals(StandardProtocol.GRPC, StandardProtocol.grpc(false));
        assertEquals(StandardProtocol.GRPCS, StandardProtocol.grpc(true));
    }
}