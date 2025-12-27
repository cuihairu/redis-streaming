package io.github.cuihairu.redis.streaming.registry.health;

import com.sun.net.httpserver.HttpServer;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

public class HttpHealthCheckerTest {

    @Test
    public void testUnsupportedProtocolThrows() {
        HttpHealthChecker checker = new HttpHealthChecker(200, 200, "/health");
        TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", 12345, StandardProtocol.TCP);
        assertThrows(IllegalArgumentException.class, () -> checker.check(instance));
    }

    @Test
    public void testHttp200IsHealthy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpHealthChecker checker = new HttpHealthChecker(500, 500, "/health");
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.HTTP);
            assertTrue(checker.check(instance));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFallbackToTcpConnectivityOnUriError() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            HttpHealthChecker checker = new HttpHealthChecker(200, 200, " /health"); // invalid URI (space) to force fallback
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.HTTP);
            assertTrue(checker.check(instance));
        }
    }
}

