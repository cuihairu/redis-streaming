package io.github.cuihairu.redis.streaming.registry.health;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for B-31: a zero or negative health check timeout used to pass
 * straight through into the protocol checkers — the HTTP checker then threw
 * IllegalArgumentException from HttpClient.connectTimeout(Duration.ZERO) at
 * construction, while the TCP/WebSocket checkers passed 0 into
 * {@code socket.connect(addr, 0)}, which JDK defines as "infinite timeout": the
 * probe thread froze forever on an unreachable host.
 */
class HealthCheckerTimeoutNormalizationTest {

    private static int field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    @Test
    void tcpNonPositiveTimeoutFallsBackToDefault() throws Exception {
        assertEquals(5000, field(new TcpHealthChecker(0), "connectTimeoutMs"));
        assertEquals(5000, field(new TcpHealthChecker(-100), "connectTimeoutMs"));
        assertEquals(1234, field(new TcpHealthChecker(1234), "connectTimeoutMs"));
        assertEquals(5000, field(new TcpHealthChecker(), "connectTimeoutMs"));
    }

    @Test
    void webSocketNonPositiveTimeoutFallsBackToDefault() throws Exception {
        assertEquals(5000, field(new WebSocketHealthChecker(0), "connectTimeoutMs"));
        assertEquals(5000, field(new WebSocketHealthChecker(-5), "connectTimeoutMs"));
        assertEquals(800, field(new WebSocketHealthChecker(800), "connectTimeoutMs"));
    }

    @Test
    void httpNonPositiveTimeoutsFallBackToDefault() throws Exception {
        Object zeroed = new HttpHealthChecker(0, 0, "/health");
        assertEquals(5000, field(zeroed, "connectTimeoutMs"));
        assertEquals(5000, field(zeroed, "readTimeoutMs"));

        Object negative = new HttpHealthChecker(-1, -2, "/health");
        assertEquals(5000, field(negative, "connectTimeoutMs"));
        assertEquals(5000, field(negative, "readTimeoutMs"));

        Object custom = new HttpHealthChecker(1500, 2500, "/health");
        assertEquals(1500, field(custom, "connectTimeoutMs"));
        assertEquals(2500, field(custom, "readTimeoutMs"));
    }
}
