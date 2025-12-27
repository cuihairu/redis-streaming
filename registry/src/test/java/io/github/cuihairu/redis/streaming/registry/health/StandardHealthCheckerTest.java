package io.github.cuihairu.redis.streaming.registry.health;

import com.sun.net.httpserver.HttpServer;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

public class StandardHealthCheckerTest {

    @Test
    public void testHttpUsesHealthEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(204, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            StandardHealthChecker checker = new StandardHealthChecker(500, 500);
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.HTTP);
            assertTrue(checker.check(instance));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFallsBackToTcpWhenHttpCheckFails() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            StandardHealthChecker checker = new StandardHealthChecker(200, 200);

            ServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.HTTP) {
                @Override
                public URI getUri() {
                    throw new IllegalStateException("boom");
                }
            };

            assertTrue(checker.check(instance));
        }
    }

    @Test
    public void testWsUsesTcpConnectivity() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int port = serverSocket.getLocalPort();
            StandardHealthChecker checker = new StandardHealthChecker(500, 500);
            TestServiceInstance instance = new TestServiceInstance("svc", "1", "127.0.0.1", port, StandardProtocol.WS);
            assertTrue(checker.check(instance));
        }
    }
}

