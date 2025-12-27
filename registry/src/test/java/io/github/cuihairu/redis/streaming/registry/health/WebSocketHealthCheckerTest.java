package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketHealthCheckerTest {

    @Test
    public void testUnsupportedProtocolThrows() {
        WebSocketHealthChecker checker = new WebSocketHealthChecker(200);
        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        assertThrows(IllegalArgumentException.class, () -> checker.check(instance));
    }

    @Test
    public void testWsConnectivitySuccess() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            WebSocketHealthChecker checker = new WebSocketHealthChecker(500);
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.WS);
            assertTrue(checker.check(instance));
        }
    }
}

