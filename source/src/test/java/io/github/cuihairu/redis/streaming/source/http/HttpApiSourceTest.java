package io.github.cuihairu.redis.streaming.source.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiSourceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchReturnsStringAndSetsDefaultAcceptHeader() throws Exception {
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();

        startServer(exchange -> {
            requestHeaders.set(exchange.getRequestHeaders());
            respond(exchange, 200, "{\"ok\":true}");
        });

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        HttpApiSource<String> source = new HttpApiSource<>(url, String.class, new ObjectMapper(), Duration.ofSeconds(1), Map.of());
        try {
            String body = source.fetch();
            assertEquals("{\"ok\":true}", body);
        } finally {
            source.close();
        }

        assertNotNull(requestHeaders.get());
        assertEquals("application/json", requestHeaders.get().getFirst("Accept"));
    }

    @Test
    void fetchDeserializesPojo() throws Exception {
        record Event(int x) {}

        startServer(exchange -> respond(exchange, 200, "{\"x\":7}"));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";

        HttpApiSource<Event> source = new HttpApiSource<>(url, Event.class);
        try {
            Event event = source.fetch();
            assertNotNull(event);
            assertEquals(7, event.x());
        } finally {
            source.close();
        }
    }

    @Test
    void fetchListDeserializesArray() throws Exception {
        record Event(int x) {}

        startServer(exchange -> respond(exchange, 200, "[{\"x\":1},{\"x\":2}]"));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";

        HttpApiSource<Event> source = new HttpApiSource<>(url, Event.class);
        try {
            List<Event> events = source.fetchList();
            assertEquals(2, events.size());
            assertEquals(1, events.get(0).x());
            assertEquals(2, events.get(1).x());
        } finally {
            source.close();
        }
    }

    @Test
    void non200ResponseReturnsNullOrEmpty() throws Exception {
        startServer(exchange -> respond(exchange, 500, "boom"));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";

        HttpApiSource<String> source = new HttpApiSource<>(url, String.class);
        try {
            assertNull(source.fetch());
            assertTrue(source.fetchList().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void customHeadersAreSent() throws Exception {
        AtomicReference<Headers> requestHeaders = new AtomicReference<>();

        startServer(exchange -> {
            requestHeaders.set(exchange.getRequestHeaders());
            respond(exchange, 200, "\"ok\"");
        });

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        HttpApiSource<String> source = new HttpApiSource<>(
                url,
                String.class,
                new ObjectMapper(),
                Duration.ofSeconds(1),
                Map.of("X-Test", "1", "Accept", "text/plain"));
        try {
            assertEquals("\"ok\"", source.fetch());
        } finally {
            source.close();
        }

        assertNotNull(requestHeaders.get());
        assertEquals("1", requestHeaders.get().getFirst("X-Test"));
        assertEquals("text/plain", requestHeaders.get().getFirst("Accept"));
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

