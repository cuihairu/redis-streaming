package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP protocol health checker
 * Dedicated health checker for TCP protocol
 */
public class TcpHealthChecker implements HealthChecker {
    
    private final int connectTimeoutMs;
    
    public TcpHealthChecker() {
        this(5000); // Default 5 second timeout
    }
    
    public TcpHealthChecker(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        StandardProtocol protocol = (StandardProtocol) serviceInstance.getProtocol();
        if (protocol != StandardProtocol.TCP && protocol != StandardProtocol.UDP) {
            throw new IllegalArgumentException("TcpHealthChecker only supports TCP/UDP protocols");
        }
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serviceInstance.getHost(), serviceInstance.getPort()), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}