package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

public class TcpHealthCheckerTest {

    @Test
    public void testUnsupportedProtocolThrows() {
        TcpHealthChecker checker = new TcpHealthChecker(200);
        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.HTTP);
        assertThrows(IllegalArgumentException.class, () -> checker.check(instance));
    }

    @Test
    public void testTcpConnectivitySuccess() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            TcpHealthChecker checker = new TcpHealthChecker(500);
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.TCP);
            assertTrue(checker.check(instance));
        }
    }
}

