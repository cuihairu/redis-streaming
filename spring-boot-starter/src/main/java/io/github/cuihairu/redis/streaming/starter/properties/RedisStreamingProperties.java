package io.github.cuihairu.redis.streaming.starter.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Streaming框架配置属性
 * 支持服务注册发现和配置管理的统一配置
 */
@Data
@ConfigurationProperties(prefix = "redis-streaming")
public class RedisStreamingProperties {

    /**
     * Redis连接配置
     *
     * 注意：这是简化的单机配置，仅适合开发和测试环境。
     * 生产环境建议使用官方 redisson-spring-boot-starter，支持：
     * - 集群模式（Cluster）
     * - 哨兵模式（Sentinel）
     * - 主从模式（Master-Slave）
     * - SSL/TLS 加密
     * - 更多高级配置
     *
     * 使用方式：添加依赖 org.redisson:redisson-spring-boot-starter:3.29.0
     * 然后通过 spring.redis.redisson.config 配置
     */
    private RedisProperties redis = new RedisProperties();

    /**
     * 服务注册配置
     */
    private RegistryProperties registry = new RegistryProperties();

    /**
     * 服务发现配置
     */
    private DiscoveryProperties discovery = new DiscoveryProperties();

    /**
     * 配置服务配置
     */
    private ConfigProperties config = new ConfigProperties();

    /**
     * 负载均衡配置（starter级）
     */
    private LoadBalancerProperties loadBalancer = new LoadBalancerProperties();

    /**
     * 客户端调用配置（重试/熔断等）
     */
    private InvokerProperties invoker = new InvokerProperties();

    /**
     * Redis单机配置（简化版）
     *
     * 仅支持单机模式，适合快速开发和测试。
     * 生产环境请使用 redisson-spring-boot-starter。
     */
    @Data
    public static class RedisProperties {
        /**
         * Redis服务器地址
         */
        private String address = "redis://127.0.0.1:6379";

        /**
         * 连接密码
         */
        private String password;

        /**
         * 数据库索引
         */
        private int database = 0;

        /**
         * 连接超时时间(毫秒)
         */
        private int connectTimeout = 3000;

        /**
         * 响应超时时间(毫秒)
         */
        private int timeout = 3000;

        /**
         * 连接池大小
         */
        private int connectionPoolSize = 64;

        /**
         * 最小空闲连接数
         */
        private int connectionMinimumIdleSize = 10;
    }

    @Data
    public static class RegistryProperties {
        /**
         * 是否启用服务注册
         */
        private boolean enabled = true;

        /**
         * 心跳间隔时间(秒)
         */
        private int heartbeatInterval = 30;

        /**
         * 心跳超时时间(秒)
         */
        private int heartbeatTimeout = 90;

        /**
         * 自动注册本服务实例
         */
        private boolean autoRegister = true;

        /**
         * 服务实例配置
         */
        private InstanceProperties instance = new InstanceProperties();

        /**
         * Provider metrics configuration
         */
        private MetricsProperties metrics = new MetricsProperties();
    }

    @Data
    public static class InstanceProperties {
        /**
         * 服务名称
         */
        private String serviceName = "${spring.application.name}";

        /**
         * 实例ID，不设置将自动生成
         */
        private String instanceId;

        /**
         * 服务主机地址，不设置将自动获取
         */
        private String host;

        /**
         * 服务端口，不设置将使用server.port
         */
        private Integer port;

        /**
         * 服务权重
         */
        private int weight = 1;

        /**
         * 是否启用实例
         */
        private boolean enabled = true;

        /**
         * 协议类型
         */
        private String protocol = "http";

        /**
         * 是否为临时实例
         * true: 临时实例，依赖客户端心跳，超时自动删除
         * false: 永久实例，服务端健康检查，只标记不删除
         */
        private Boolean ephemeral;

        /**
         * 实例元数据
         */
        private java.util.Map<String, String> metadata = new java.util.HashMap<>();
    }

    @Data
    public static class DiscoveryProperties {
        /**
         * 是否启用服务发现
         */
        private boolean enabled = true;

        /**
         * 是否只发现健康的实例
         */
        private boolean healthyOnly = true;

        /**
         * 服务发现缓存时间(秒)
         */
        private int cacheTime = 30;
    }

    @Data
    public static class ConfigProperties {
        /**
         * 是否启用配置服务
         */
        private boolean enabled = true;

        /**
         * 默认配置组
         */
        private String defaultGroup = "DEFAULT_GROUP";

        /**
         * 配置刷新间隔(秒)
         */
        private int refreshInterval = 30;

        /**
         * 是否启用配置自动刷新
         */
        private boolean autoRefresh = true;

        /**
         * 配置历史保留数量
         */
        private int historySize = 10;
    }

    @Data
    public static class LoadBalancerProperties {
        /**
         * 策略：scored | wrr | weighted-random | consistent-hash
         */
        private String strategy = "scored";

        // scored 配置
        private String preferredRegion;
        private String preferredZone;
        private double cpuWeight = 1.0;
        private double latencyWeight = 1.0;
        private double memoryWeight = 0.0;
        private double inflightWeight = 0.0;
        private double queueWeight = 0.0;
        private double errorRateWeight = 0.0;
        private double targetLatencyMs = 50.0;
        private double maxCpuPercent = -1;
        private double maxLatencyMs = -1;
        private double maxMemoryPercent = -1;
        private double maxInflight = -1;
        private double maxQueue = -1;
        private double maxErrorRatePercent = -1;
    }

    @Data
    public static class InvokerProperties {
        // retry
        private int maxAttempts = 3;
        private long initialDelayMs = 20;
        private double backoffFactor = 2.0;
        private long maxDelayMs = 200;
        private long jitterMs = 20;
    }

    @Data
    public static class MetricsProperties {
        /**
         * 启用的指标类型，如 [memory, cpu, application, disk, network]
         */
        private java.util.Set<String> enabled = new java.util.HashSet<>(java.util.Set.of("memory","cpu","application","disk","network"));

        /**
         * 指标收集间隔（键为指标类型），例如：memory: 30s, cpu: 60s
         */
        private java.util.Map<String, java.time.Duration> intervals = new java.util.HashMap<>();

        /**
         * 默认收集间隔
         */
        private java.time.Duration defaultInterval = java.time.Duration.ofMinutes(1);

        /**
         * 显著变化时是否立即更新
         */
        private boolean immediateUpdateOnSignificantChange = true;

        /**
         * 收集超时时间
         */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(5);
    }
}
