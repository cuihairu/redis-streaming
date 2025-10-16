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
     * 消息队列（MQ）配置
     */
    private MqProperties mq = new MqProperties();

    /**
     * 速率限制配置
     */
    private RateLimitProperties ratelimit = new RateLimitProperties();

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

        /**
         * Redis key prefix for config center (controls BaseRedisConfig)
         */
        private String keyPrefix = io.github.cuihairu.redis.streaming.config.BaseRedisConfig.DEFAULT_KEY_PREFIX;

        /**
         * Whether to enable key prefix
         */
        private boolean enableKeyPrefix = true;
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
    public static class MqProperties {
        /** 是否启用 MQ 模块（仅影响自动装配） */
        private boolean enabled = true;

        /** 默认分区数（topic 首次写入时使用） */
        private int defaultPartitionCount = 1;

        /** 消费线程池大小 */
        private int workerThreads = 8;

        /** 调度线程池大小（续约/再均衡/搬运） */
        private int schedulerThreads = 2;

        /** 每次拉取最大条数 */
        private int consumerBatchCount = 10;

        /** 拉取超时（毫秒） */
        private long consumerPollTimeoutMs = 1000;

        /** 租约 TTL（秒） */
        private int leaseTtlSeconds = 15;

        /** 再均衡间隔（秒） */
        private int rebalanceIntervalSec = 5;

        /** 续约间隔（秒） */
        private int renewIntervalSec = 3;

        /** pending 扫描间隔（秒） */
        private int pendingScanIntervalSec = 30;

        /** 认定孤儿 pending 的 idle 阈值（毫秒） */
        private long claimIdleMs = 300000;

        /** claim 批大小 */
        private int claimBatchSize = 50;

        /** 最大重试次数 */
        private int retryMaxAttempts = 5;

        /** 重试基准退避（毫秒） */
        private long retryBaseBackoffMs = 1000;

        /** 重试最大退避（毫秒） */
        private long retryMaxBackoffMs = 60000;

        /** 重试搬运批大小 */
        private int retryMoverBatch = 100;

        /** 重试搬运周期（秒） */
        private int retryMoverIntervalSec = 1;

        /** 重试搬运锁等待（毫秒） */
        private long retryLockWaitMs = 100;

        /** 重试搬运锁租期（毫秒） */
        private long retryLockLeaseMs = 500;

        /** Key prefixes (MQ control keys & stream keys) */
        private String keyPrefix = "streaming:mq";
        private String streamKeyPrefix = "stream:topic";

        /** Naming conventions */
        private String consumerNamePrefix = "consumer-";
        private String dlqConsumerSuffix = "-dlq";
        private String defaultConsumerGroup = "default-group";
        private String defaultDlqGroup = "dlq-group";

        /** Broker configuration */
        private BrokerProperties broker = new BrokerProperties();

        /** Retention configuration (Streams) */
        private int retentionMaxLenPerPartition = 100_000; // default bounded backlog
        private long retentionMs = 0L; // disabled by default
        private int trimIntervalSec = 60; // background trim cadence

        /** Deletion policy on ACK: none | immediate | all-groups-ack */
        private String ackDeletePolicy = "none";
        /** TTL for ack-set keys when using all-groups-ack (seconds) */
        private int acksetTtlSec = 86400;

        /** DLQ retention overrides (0 means disabled/use defaults) */
        private int dlqRetentionMaxLen = 0;
        private long dlqRetentionMs = 0L;
    }

    @Data
    public static class BrokerProperties {
        /** Broker type: redis | jdbc */
        private String type = "redis";
        /** JDBC settings (used when type=jdbc). DataSource bean can also be provided by user. */
        private JdbcProperties jdbc = new JdbcProperties();
    }

    @Data
    public static class JdbcProperties {
        /** Fallback JDBC driver class (if user provides DataSource, this is ignored). */
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        private String url;
        private String username;
        private String password;
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

    @lombok.Data
    public static class RateLimitProperties {
        /** 是否启用限速（仅影响自动装配） */
        private boolean enabled = false;
        /** 后端：memory | redis */
        private String backend = "memory";
        /** 窗口大小（毫秒） */
        private long windowMs = 1000;
        /** 窗口内允许的最大请求数 */
        private int limit = 100;
        /** Redis Key 前缀（仅后端为 redis 时有效） */
        private String keyPrefix = "streaming:rl";

        /**
         * 命名策略集合（可同时声明多个不同的限速器）。
         * 当不为空时，将根据该集合创建一个 RateLimiterRegistry 供按名称获取。
         */
        private java.util.Map<String, Policy> policies = new java.util.HashMap<>();

        /**
         * 默认策略名称（当注入单个 RateLimiter 时使用）。
         */
        private String defaultName = "default";

        @lombok.Data
        public static class Policy {
            /** 算法：sliding | token-bucket | leaky-bucket */
            private String algorithm = "sliding";
            /** 后端：memory | redis（仅 sliding/token-bucket 支持 redis） */
            private String backend = "memory";
            // sliding params
            private long windowMs = 1000;
            private int limit = 100;
            // token/leaky params
            private double capacity = 100.0;
            private double ratePerSecond = 100.0;
            // redis key prefix (sliding/token)
            private String keyPrefix = "streaming:rl";
        }
    }
}
