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
 * 标准健康检查器实现
 * 提供基于协议的健康检查功能
 */
public class StandardHealthChecker implements HealthChecker {
    
    private final HttpClient httpClient;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    
    public StandardHealthChecker() {
        this(5000, 5000); // 默认5秒超时
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
                // 对于其他协议，默认检查端口连通性
                return checkTcp(serviceInstance);
        }
    }
    
    /**
     * HTTP/HTTPS健康检查
     */
    private boolean checkHttp(ServiceInstance serviceInstance) throws Exception {
        try {
            URI uri = serviceInstance.getUri();
            String checkUrl = uri.toString() + "/health"; // 默认检查/health端点
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(checkUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (Exception e) {
            // 如果/health端点不可用，回退到TCP检查
            return checkTcp(serviceInstance);
        }
    }
    
    /**
     * TCP健康检查
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
     * WebSocket健康检查
     */
    private boolean checkWebSocket(ServiceInstance serviceInstance) {
        // 对于WebSocket，先检查TCP连通性
        return checkTcp(serviceInstance);
    }
}