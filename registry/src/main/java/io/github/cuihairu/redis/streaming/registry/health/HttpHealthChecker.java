package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP/HTTPS protocol health checker
 * Dedicated health checker for HTTP/HTTPS protocols
 */
public class HttpHealthChecker implements HealthChecker {
    
    private final HttpClient httpClient;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String healthEndpoint;
    
    public HttpHealthChecker() {
        this(5000, 5000, "/health");
    }
    
    public HttpHealthChecker(int connectTimeoutMs, int readTimeoutMs, String healthEndpoint) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.healthEndpoint = healthEndpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        StandardProtocol protocol = (StandardProtocol) serviceInstance.getProtocol();
        if (protocol != StandardProtocol.HTTP && protocol != StandardProtocol.HTTPS) {
            throw new IllegalArgumentException("HttpHealthChecker only supports HTTP/HTTPS protocols");
        }
        
        try {
            URI uri = serviceInstance.getUri();
            String checkUrl = uri.toString() + healthEndpoint;
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(checkUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            // If health check endpoint is unavailable, fall back to TCP check
            return checkTcpConnectivity(serviceInstance);
        }
    }
    
    /**
     * TCP connectivity check
     */
    private boolean checkTcpConnectivity(ServiceInstance serviceInstance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serviceInstance.getHost(), serviceInstance.getPort()), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}