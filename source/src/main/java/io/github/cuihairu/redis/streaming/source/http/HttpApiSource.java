package io.github.cuihairu.redis.streaming.source.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HTTP API Source for polling REST APIs periodically.
 * Supports JSON response deserialization and custom headers.
 *
 * @param <T> the type of elements to read
 */
@Slf4j
public class HttpApiSource<T> implements AutoCloseable {

    private final String apiUrl;
    private final ObjectMapper objectMapper;
    private final Class<T> valueClass;
    private final Map<String, String> headers;
    private final Duration pollInterval;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * Create an HTTP API source with default settings.
     *
     * @param apiUrl     the REST API URL
     * @param valueClass the class type of values
     */
    public HttpApiSource(String apiUrl, Class<T> valueClass) {
        this(apiUrl, valueClass, new ObjectMapper(), Duration.ofSeconds(10), Map.of());
    }

    /**
     * Create an HTTP API source with custom settings.
     *
     * @param apiUrl       the REST API URL
     * @param valueClass   the class type of values
     * @param objectMapper the JSON object mapper
     * @param pollInterval polling interval
     * @param headers      custom HTTP headers
     */
    public HttpApiSource(
            String apiUrl,
            Class<T> valueClass,
            ObjectMapper objectMapper,
            Duration pollInterval,
            Map<String, String> headers) {
        Objects.requireNonNull(apiUrl, "API URL cannot be null");
        Objects.requireNonNull(valueClass, "Value class cannot be null");
        Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null");
        Objects.requireNonNull(pollInterval, "Poll interval cannot be null");
        Objects.requireNonNull(headers, "Headers cannot be null");

        this.apiUrl = apiUrl;
        this.valueClass = valueClass;
        this.objectMapper = objectMapper;
        this.pollInterval = pollInterval;
        this.headers = headers;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("http-api-poller-" + apiUrl);
            t.setDaemon(true);
            return t;
        });

        log.info("Created HTTP API source for URL: {}", apiUrl);
    }

    /**
     * Fetch data once from the API.
     *
     * @return the fetched data, or null if error
     */
    public T fetch() {
        try {
            URI uri = URI.create(apiUrl);
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            // Set headers
            headers.forEach(conn::setRequestProperty);
            if (!headers.containsKey("Accept")) {
                conn.setRequestProperty("Accept", "application/json");
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String jsonResponse = response.toString();
                log.debug("Fetched from {}: {} bytes", apiUrl, jsonResponse.length());

                if (valueClass == String.class) {
                    @SuppressWarnings("unchecked")
                    T result = (T) jsonResponse;
                    return result;
                }

                return objectMapper.readValue(jsonResponse, valueClass);

            } else {
                log.error("HTTP request failed with code: {}", responseCode);
                return null;
            }

        } catch (Exception e) {
            log.error("Failed to fetch from HTTP API: {}", apiUrl, e);
            return null;
        }
    }

    /**
     * Fetch a list of items from the API.
     *
     * @return list of items
     */
    public List<T> fetchList() {
        try {
            URI uri = URI.create(apiUrl);
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            headers.forEach(conn::setRequestProperty);
            if (!headers.containsKey("Accept")) {
                conn.setRequestProperty("Accept", "application/json");
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String jsonResponse = response.toString();
                log.debug("Fetched list from {}: {} bytes", apiUrl, jsonResponse.length());

                return objectMapper.readValue(
                        jsonResponse,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, valueClass)
                );

            } else {
                log.error("HTTP request failed with code: {}", responseCode);
                return new ArrayList<>();
            }

        } catch (Exception e) {
            log.error("Failed to fetch list from HTTP API: {}", apiUrl, e);
            return new ArrayList<>();
        }
    }

    /**
     * Start polling the API periodically.
     *
     * @param handler the data handler
     */
    public void poll(Consumer<T> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");

        running = true;
        log.info("Starting HTTP API polling for: {}", apiUrl);

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }

            try {
                T data = fetch();
                if (data != null) {
                    handler.accept(data);
                }
            } catch (Exception e) {
                log.error("Error in polling handler", e);
            }
        }, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Start polling for a list of items.
     *
     * @param handler the data handler (called for each item)
     */
    public void pollList(Consumer<T> handler) {
        Objects.requireNonNull(handler, "Handler cannot be null");

        running = true;
        log.info("Starting HTTP API list polling for: {}", apiUrl);

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }

            try {
                List<T> items = fetchList();
                items.forEach(item -> {
                    try {
                        handler.accept(item);
                    } catch (Exception e) {
                        log.error("Error processing item", e);
                    }
                });
            } catch (Exception e) {
                log.error("Error in list polling handler", e);
            }
        }, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stop polling.
     */
    public void stop() {
        running = false;
        log.info("Stopping HTTP API polling for: {}", apiUrl);
    }

    /**
     * Get the API URL.
     *
     * @return the API URL
     */
    public String getApiUrl() {
        return apiUrl;
    }

    /**
     * Get the poll interval.
     *
     * @return the poll interval
     */
    public Duration getPollInterval() {
        return pollInterval;
    }

    /**
     * Check if polling is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Closed HTTP API source for: {}", apiUrl);
        }
    }
}
