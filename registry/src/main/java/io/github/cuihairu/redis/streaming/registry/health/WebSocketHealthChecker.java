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

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final int connectTimeoutMs;

    public WebSocketHealthChecker() {
        this(DEFAULT_TIMEOUT_MS); // Default 5 second timeout
    }

    public WebSocketHealthChecker(int connectTimeoutMs) {
        // 0 would make socket.connect(addr, 0) wait forever (B-31); negative is invalid too
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_TIMEOUT_MS;
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