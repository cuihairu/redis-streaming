package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * WebSocket协议健康检查器
 * 专门用于WebSocket协议的健康检查
 */
public class WebSocketHealthChecker implements HealthChecker {
    
    private final int connectTimeoutMs;
    
    public WebSocketHealthChecker() {
        this(5000); // 默认5秒超时
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
        
        // 对于WebSocket，先检查TCP连通性
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(serviceInstance.getHost(), serviceInstance.getPort()), connectTimeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}