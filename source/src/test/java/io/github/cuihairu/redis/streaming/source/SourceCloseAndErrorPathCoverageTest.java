package io.github.cuihairu.redis.streaming.source;

import io.github.cuihairu.redis.streaming.source.http.HttpApiSource;
import io.github.cuihairu.redis.streaming.source.redis.RedisListSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers source close()/poll-lambda error branches via interrupts and throwing handlers. */
class SourceCloseAndErrorPathCoverageTest {

    @Test
    void redisListSourceCloseHandlesInterrupt() {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(0)).thenThrow(new IndexOutOfBoundsException("empty"));
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getList(anyString())).thenReturn(list);

        RedisListSource<String> source = new RedisListSource<>(redisson, "q", String.class);
        source.consume(v -> { });
        Thread.currentThread().interrupt();
        assertDoesNotThrow(source::close);
        assertTrue(Thread.interrupted());
    }

    @Test
    void httpApiSourceCloseHandlesInterrupt() {
        HttpApiSource<String> source = new HttpApiSource<>("http://127.0.0.1:1/nope", String.class);
        Thread.currentThread().interrupt();
        assertDoesNotThrow(source::close);
        assertTrue(Thread.interrupted());
    }

    @Test
    void httpApiPollCatchesHandlerExceptions() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/o", exchange -> {
            byte[] body = "\"payload\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/l", exchange -> {
            byte[] body = "[\"a\",\"b\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            HttpApiSource<String> single = new HttpApiSource<>(base + "/o", String.class,
                    new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(40), Map.of());
            HttpApiSource<String> list = new HttpApiSource<>(base + "/l", String.class,
                    new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(40), Map.of());
            CountDownLatch seen = new CountDownLatch(3); // 1 single + 2 list items
            try {
                single.poll(v -> {
                    seen.countDown();
                    throw new IllegalStateException("handler bug");
                });
                list.pollList(v -> {
                    seen.countDown();
                    throw new IllegalStateException("handler bug");
                });
                assertTrue(seen.await(5, TimeUnit.SECONDS), "handlers ran and failures were swallowed");
            } finally {
                single.close();
                list.close();
            }
        } finally {
            server.stop(0);
        }
    }
}
