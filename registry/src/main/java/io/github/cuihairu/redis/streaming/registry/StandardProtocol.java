package io.github.cuihairu.redis.streaming.registry;

/**
 * 标准协议枚举
 * 定义常用的网络协议
 */
public enum StandardProtocol implements Protocol {
    HTTP("http", false, 80, "Hypertext Transfer Protocol"),
    HTTPS("https", true, 443, "Hypertext Transfer Protocol Secure"),
    TCP("tcp", false, 0, "Transmission Control Protocol"),
    UDP("udp", false, 0, "User Datagram Protocol"),
    WS("ws", false, 80, "WebSocket Protocol"),
    WSS("wss", true, 443, "WebSocket Secure Protocol"),
    KCP("kcp", false, 0, "KCP Protocol"),
    GRPC("grpc", false, 0, "gRPC Protocol"),
    GRPCS("grpcs", true, 0, "gRPC Secure Protocol"),
    DUBBO("dubbo", false, 0, "Dubbo Protocol"),
    DUBBO2("dubbo2", false, 0, "Dubbo 2 Protocol");
    
    private final String name;
    private final boolean secure;
    private final int defaultPort;
    private final String description;
    
    StandardProtocol(String name, boolean secure, int defaultPort, String description) {
        this.name = name;
        this.secure = secure;
        this.defaultPort = defaultPort;
        this.description = description;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public boolean isSecure() {
        return secure;
    }
    
    @Override
    public int getDefaultPort() {
        return defaultPort;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    /**
     * 根据协议名称获取协议枚举
     */
    public static StandardProtocol fromName(String name) {
        for (StandardProtocol protocol : values()) {
            if (protocol.getName().equalsIgnoreCase(name)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown protocol: " + name);
    }
    
    /**
     * 根据是否安全获取HTTP协议
     */
    public static StandardProtocol http(boolean secure) {
        return secure ? HTTPS : HTTP;
    }
    
    /**
     * 根据是否安全获取WebSocket协议
     */
    public static StandardProtocol ws(boolean secure) {
        return secure ? WSS : WS;
    }
    
    /**
     * 根据是否安全获取gRPC协议
     */
    public static StandardProtocol grpc(boolean secure) {
        return secure ? GRPCS : GRPC;
    }
}