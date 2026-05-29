package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * WebSocket protocol health checker
 * Dedicated health checker for WebSocket protocol
 */
public class WebSocketHealthChecker implements HealthChecker {
    
    private final int connectTimeoutMs;
    
    public WebSocketHealthChecker() {
        this(5000); // Default 5 second timeout
    }
    
    public WebSocketHealthChecker(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        StandardProtocol protocol = (StandardProtocol) serviceInstance.getProtocol();
        if (protocol != StandardProtocol.WS && protocol != StandardProtocol.WSS) {
            throw new IllegalArgumentException("WebSocketHealthChecker only supports WS/WSS protocols");
        }
        
        // For WebSocket, first check TCP connectivity
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serviceInstance.getHost(), serviceInstance.getPort()), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}