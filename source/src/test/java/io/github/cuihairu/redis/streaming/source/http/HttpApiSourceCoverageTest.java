package io.github.cuihairu.redis.streaming.source.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers HttpApiSource fetch/fetchList, accessors, polling loops and close. */
class HttpApiSourceCoverageTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/object", exchange -> respond(exchange, "{\"name\":\"n1\",\"value\":7}"));
        server.createContext("/list", exchange -> respond(exchange, "[{\"name\":\"a\"},{\"name\":\"b\"}]"));
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
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
        public int value;
    }

    @Test
    void fetchParsesObjectAndHandlesErrors() {
        HttpApiSource<Item> source = new HttpApiSource<>(baseUrl + "/object", Item.class);
        try {
            Item item = source.fetch();
            assertNotNull(item);
            assertEquals("n1", item.name);

            assertEquals(baseUrl + "/object", source.getApiUrl());
            assertNotNull(source.getPollInterval());
            assertNotNull(source.toString());

            HttpApiSource<Item> failing = new HttpApiSource<>(baseUrl + "/error", Item.class);
            assertNull(failing.fetch(), "non-200 response yields null");

            HttpApiSource<Item> broken = new HttpApiSource<>("http://127.0.0.1:1/nope", Item.class);
            assertNull(broken.fetch(), "connection failure yields null");
        } finally {
            source.close();
        }
    }

    @Test
    void fetchListParsesArrayAndHandlesErrors() {
        HttpApiSource<Item> source = new HttpApiSource<>(baseUrl + "/list", Item.class);
        try {
            List<Item> items = source.fetchList();
            assertEquals(2, items.size());
            assertEquals("a", items.get(0).name);

            HttpApiSource<Item> failing = new HttpApiSource<>(baseUrl + "/error", Item.class);
            assertTrue(failing.fetchList().isEmpty(), "non-200 response yields empty list");

            HttpApiSource<Item> broken = new HttpApiSource<>("http://127.0.0.1:1/nope", Item.class);
            assertTrue(broken.fetchList().isEmpty(), "connection failure yields empty list");
        } finally {
            source.close();
        }
    }

    @Test
    void fetchSupportsStringClassAndHeaders() {
        Map<String, String> headers = Map.of("X-Test", "1");
        HttpApiSource<String> source = new HttpApiSource<>(baseUrl + "/object", String.class, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(50), headers);
        try {
            String raw = source.fetch();
            assertNotNull(raw);
            assertTrue(raw.contains("n1"));
        } finally {
            source.close();
        }
    }

    @Test
    void pollLoopsDeliverItemsAndSurviveErrors() throws Exception {
        HttpApiSource<Item> source = new HttpApiSource<>(baseUrl + "/object", Item.class, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(50), Map.of());
        HttpApiSource<Item> listSource = new HttpApiSource<>(baseUrl + "/list", Item.class, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(50), Map.of());
        CountDownLatch seen = new CountDownLatch(3);
        try {
            source.poll(item -> seen.countDown());
            listSource.pollList(item -> seen.countDown());
            assertTrue(seen.await(5, TimeUnit.SECONDS), "poll handlers delivered data");

            source.stop();
            listSource.stop();
            assertTrue(!source.isRunning());
        } finally {
            source.close();
            listSource.close();
        }

        HttpApiSource<Item> failing = new HttpApiSource<>("http://127.0.0.1:1/nope", Item.class, new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofMillis(30), Map.of());
        try {
            failing.poll(item -> {
                throw new IllegalStateException("never");
            });
            failing.pollList(item -> {
                throw new IllegalStateException("never");
            });
            Thread.sleep(100);
        } finally {
            failing.close();
        }
        assertDoesNotThrow(failing::close);
    }
}
