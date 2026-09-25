package io.github.cuihairu.redis.streaming.source.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@link HttpApiSource} branches: the Accept-header default, the stopped
 * guards of the poll loops, the failure guard around list polling and the null-scheduler guard
 * in {@code close()}.
 */
@Timeout(30)
class HttpApiSourceResidualCoverage2Test {

    private HttpServer server;
    private String listUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/list", exchange -> {
            byte[] body = "[\"a\",\"b\"]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        listUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/list";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchListWorksWithAndWithoutExplicitAcceptHeader() {
        HttpApiSource<String> plain = new HttpApiSource<>(listUrl, String.class);
        assertEquals(List.of("a", "b"), plain.fetchList());

        HttpApiSource<String> withAccept = new HttpApiSource<>(
                listUrl, String.class, new ObjectMapper(), Duration.ofSeconds(5),
                Map.of("Accept", "application/json"));
        assertEquals(List.of("a", "b"), withAccept.fetchList());

        plain.close();
        withAccept.close();
    }

    static class FailingListSource extends HttpApiSource<String> {
        FailingListSource(String url) {
            super(url, String.class, new ObjectMapper(), Duration.ofSeconds(5), Map.of());
        }

        @Override
        public List<String> fetchList() {
            throw new IllegalStateException("backend exploded");
        }
    }

    @Test
    void pollTaskReturnsImmediatelyWhenStopped() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return null;
                });
        AtomicInteger handled = new AtomicInteger();
        try (MockedStatic<java.util.concurrent.Executors> executors =
                     mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(any()))
                    .thenReturn(scheduler);
            HttpApiSource<String> source = new HttpApiSource<>(
                    listUrl, String.class, new ObjectMapper(), Duration.ofSeconds(5), Map.of());
            source.poll(v -> handled.incrementAndGet());
            Runnable task = captured.get();

            source.stop();
            task.run();
            assertEquals(0, handled.get());
        }
    }

    @Test
    void pollListTaskReturnsImmediatelyWhenStopped() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return null;
                });
        AtomicInteger handled = new AtomicInteger();
        try (MockedStatic<java.util.concurrent.Executors> executors =
                     mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(any()))
                    .thenReturn(scheduler);
            HttpApiSource<String> source = new HttpApiSource<>(
                    listUrl, String.class, new ObjectMapper(), Duration.ofSeconds(5), Map.of());
            source.pollList(v -> handled.incrementAndGet());
            Runnable task = captured.get();

            source.stop();
            task.run();
            assertEquals(0, handled.get());
        }
    }

    @Test
    void pollListSurvivesFetchListFailure() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicReference<Runnable> captured = new AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(0));
                    return null;
                });
        AtomicInteger handled = new AtomicInteger();
        try (MockedStatic<java.util.concurrent.Executors> executors =
                     mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(any()))
                    .thenReturn(scheduler);
            FailingListSource source = new FailingListSource(listUrl);
            source.pollList(v -> handled.incrementAndGet());
            Runnable task = captured.get();

            task.run();
            assertEquals(0, handled.get());
        }
    }

    @Test
    void closeWithoutSchedulerIsNoOp() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        HttpApiSource<String> source;
        try (MockedStatic<java.util.concurrent.Executors> executors =
                     mockStatic(java.util.concurrent.Executors.class)) {
            executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(any()))
                    .thenReturn(scheduler);
            source = new HttpApiSource<>(
                    listUrl, String.class, new ObjectMapper(), Duration.ofSeconds(5), Map.of());
        }
        java.lang.reflect.Field f = HttpApiSource.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        f.set(source, null);

        source.close();
        source.close();
    }
}
