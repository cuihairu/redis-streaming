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
 * Standard health checker implementation
 * Provides protocol-based health checking functionality
 */
public class StandardHealthChecker implements HealthChecker {
    
    private final HttpClient httpClient;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    
    public StandardHealthChecker() {
        this(5000, 5000); // Default 5 second timeout
    }
    
    public StandardHealthChecker(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        StandardProtocol protocol = (StandardProtocol) serviceInstance.getProtocol();
        
        switch (protocol) {
            case HTTP:
            case HTTPS:
                return checkHttp(serviceInstance);
            case TCP:
                return checkTcp(serviceInstance);
            case WS:
            case WSS:
                return checkWebSocket(serviceInstance);
            default:
                // For other protocols, default to checking port connectivity
                return checkTcp(serviceInstance);
        }
    }
    
    /**
     * HTTP/HTTPS health check
     */
    private boolean checkHttp(ServiceInstance serviceInstance) throws Exception {
        try {
            URI uri = serviceInstance.getUri();
            String checkUrl = uri.toString() + "/health"; // Default check /health endpoint
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(checkUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            // If /health endpoint is unavailable, fall back to TCP check
            return checkTcp(serviceInstance);
        }
    }
    
    /**
     * TCP health check
     */
    private boolean checkTcp(ServiceInstance serviceInstance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serviceInstance.getHost(), serviceInstance.getPort()), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * WebSocket health check
     */
    private boolean checkWebSocket(ServiceInstance serviceInstance) {
        // For WebSocket, first check TCP connectivity
        return checkTcp(serviceInstance);
    }
}