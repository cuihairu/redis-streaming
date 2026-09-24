package io.github.cuihairu.redis.streaming.registry.health;

import com.sun.net.httpserver.HttpServer;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers HTTP/Standard/WebSocket health checker success, fallback and failure
 * branches with local HttpServer and ServerSocket endpoints.
 */
class ProtocolHealthCheckersCoverageTest {

    private static ServiceInstance instance(StandardProtocol protocol, int port) {
        return DefaultServiceInstance.builder()
                .serviceName("proto-cov").instanceId("i-" + port).host("127.0.0.1").port(port)
                .protocol(protocol).metadata(java.util.Map.of()).healthy(true).build();
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HttpServer healthServer() throws Exception {
        return healthServer(200);
    }

    private static HttpServer healthServer(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void httpHealthCheckerRejectsNonHttpProtocol() {
        HttpHealthChecker checker = new HttpHealthChecker(200, 200, "/health");
        assertThrows(IllegalArgumentException.class,
                () -> checker.check(instance(StandardProtocol.TCP, 1)));
    }

    @Test
    void httpHealthCheckerSucceedsAgainstHealthEndpoint() throws Exception {
        HttpServer server = healthServer();
        try {
            HttpHealthChecker checker = new HttpHealthChecker(500, 500, "/health");
            assertTrue(checker.check(instance(StandardProtocol.HTTP, server.getAddress().getPort())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpHealthCheckerFallsBackToTcpConnectivity() throws Exception {
        try (ServerSocket notHttp = new ServerSocket(0)) {
            HttpHealthChecker checker = new HttpHealthChecker(200, 200, "/health");
            // HTTP probe fails (no HTTP response) but TCP connect succeeds -> true
            assertTrue(checker.check(instance(StandardProtocol.HTTP, notHttp.getLocalPort())));
        }
    }

    @Test
    void httpHealthCheckerFallsBackWhenServerErrors() throws Exception {
        HttpServer server = healthServer(500);
        try {
            HttpHealthChecker checker = new HttpHealthChecker(500, 500, "/health");
            // 5xx is answered without an exception -> unhealthy (TCP fallback is exception-only)
            assertFalse(checker.check(instance(StandardProtocol.HTTP, server.getAddress().getPort())));

            StandardHealthChecker standard = new StandardHealthChecker(500, 500);
            assertFalse(standard.check(instance(StandardProtocol.HTTP, server.getAddress().getPort())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpHealthCheckerReturnsFalseWhenEverythingFails() throws Exception {
        HttpHealthChecker checker = new HttpHealthChecker(150, 150, "/health");
        assertFalse(checker.check(instance(StandardProtocol.HTTP, closedPort())));
    }

    @Test
    void standardHealthCheckerCoversAllProtocolBranches() throws Exception {
        StandardHealthChecker checker = new StandardHealthChecker(300, 300);
        HttpServer server = healthServer();
        try (ServerSocket open = new ServerSocket(0)) {
            assertTrue(checker.check(instance(StandardProtocol.HTTP, server.getAddress().getPort())));
            assertTrue(checker.check(instance(StandardProtocol.HTTPS, server.getAddress().getPort())));
            assertTrue(checker.check(instance(StandardProtocol.TCP, open.getLocalPort())));
            assertTrue(checker.check(instance(StandardProtocol.WS, open.getLocalPort())));
            assertTrue(checker.check(instance(StandardProtocol.WSS, open.getLocalPort())));
            assertTrue(checker.check(instance(StandardProtocol.GRPC, open.getLocalPort())));
            assertFalse(checker.check(instance(StandardProtocol.TCP, closedPort())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webSocketHealthCheckerCoversSuccessFailureAndProtocolGuard() throws Exception {
        WebSocketHealthChecker checker = new WebSocketHealthChecker(300);
        assertThrows(IllegalArgumentException.class,
                () -> checker.check(instance(StandardProtocol.TCP, 1)));
        try (ServerSocket open = new ServerSocket(0)) {
            assertTrue(checker.check(instance(StandardProtocol.WS, open.getLocalPort())));
            assertTrue(checker.check(instance(StandardProtocol.WSS, open.getLocalPort())));
        }
        assertFalse(checker.check(instance(StandardProtocol.WS, closedPort())));
    }
}
