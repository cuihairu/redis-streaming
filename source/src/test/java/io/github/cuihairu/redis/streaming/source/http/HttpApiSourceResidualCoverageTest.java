package io.github.cuihairu.redis.streaming.source.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for HttpApiSource: close() scheduler shutdown edge paths and polling
 * handler failure branches for both poll() and pollList().
 */
class HttpApiSourceResidualCoverageTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/object", exchange -> respond(exchange, "{\"name\":\"n1\"}"));
        server.createContext("/list", exchange -> respond(exchange, "[{\"name\":\"a\"},{\"name\":\"b\"}]"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static class Item {
        public String name;
    }

    private static void setScheduler(HttpApiSource<?> src, ScheduledExecutorService scheduler) throws Exception {
        Field f = HttpApiSource.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        f.set(src, scheduler);
    }

    @Test
    void closeForcesShutdownNowWhenTerminationTimesOut() throws Exception {
        HttpApiSource<String> src = new HttpApiSource<>(baseUrl + "/object", String.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);
        setScheduler(src, scheduler);
        assertDoesNotThrow(src::close);
        verify(scheduler).shutdown();
        verify(scheduler).shutdownNow();
    }

    @Test
    void closeReinterruptsWhenAwaitIsInterrupted() throws Exception {
        HttpApiSource<String> src = new HttpApiSource<>(baseUrl + "/object", String.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());
        setScheduler(src, scheduler);
        assertDoesNotThrow(src::close);
        verify(scheduler).shutdownNow();
    }

    @Test
    void pollHandlerFailuresAreSwallowed() throws Exception {
        HttpApiSource<Item> src = new HttpApiSource<>(
                baseUrl + "/object", Item.class, new com.fasterxml.jackson.databind.ObjectMapper(),
                Duration.ofMillis(50), Map.of());
        CountDownLatch called = new CountDownLatch(1);
        src.poll(item -> {
            called.countDown();
            throw new IllegalStateException("handler boom");
        });
        assertTrue(called.await(4, TimeUnit.SECONDS));
        Thread.sleep(150); // allow at least one more failing cycle
        src.close();
    }

    @Test
    void pollListItemFailuresAreSwallowed() throws Exception {
        HttpApiSource<Item> src = new HttpApiSource<>(
                baseUrl + "/list", Item.class, new com.fasterxml.jackson.databind.ObjectMapper(),
                Duration.ofMillis(50), Map.of());
        AtomicInteger seen = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(2);
        src.pollList(item -> {
            seen.incrementAndGet();
            called.countDown();
            throw new IllegalStateException("item boom");
        });
        assertTrue(called.await(4, TimeUnit.SECONDS));
        src.close();
    }
}
