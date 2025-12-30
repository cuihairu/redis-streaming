package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.prometheus.client.exporter.HTTPServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Prometheus HTTP server for exposing metrics.
 * Starts an HTTP server on the specified port to expose /metrics endpoint.
 */
@Slf4j
public class PrometheusExporter implements AutoCloseable {

    private final HTTPServer server;
    private final int port;

    /**
     * Create and start a Prometheus exporter on default port 9090.
     *
     * @throws IOException if the server fails to start
     */
    public PrometheusExporter() throws IOException {
        this(9090);
    }

    /**
     * Create and start a Prometheus exporter on the specified port.
     *
     * @param port the port to listen on
     * @throws IOException if the server fails to start
     */
    public PrometheusExporter(int port) throws IOException {
        this.server = new HTTPServer(port);
        this.port = this.server.getPort();
        log.info("Prometheus exporter started on port {}", this.port);
        log.info("Metrics available at http://localhost:{}/metrics", this.port);
    }

    /**
     * Get the port the exporter is running on.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Stop the Prometheus exporter.
     */
    public void stop() {
        if (server != null) {
            server.close();
            log.info("Prometheus exporter stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Get the metrics endpoint URL.
     *
     * @return the full URL to the metrics endpoint
     */
    public String getMetricsUrl() {
        return "http://localhost:" + port + "/metrics";
    }
}
