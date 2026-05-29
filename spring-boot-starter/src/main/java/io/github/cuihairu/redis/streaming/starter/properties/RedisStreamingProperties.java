package io.github.cuihairu.redis.streaming.starter.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis Streaming framework configuration properties
 * Supports unified configuration for service registration, discovery, and configuration management
 */
@Data
@ConfigurationProperties(prefix = "redis-streaming")
public class RedisStreamingProperties {

    /**
     * Redis connection configuration
     *
     * Note: This is a simplified single-server configuration, suitable only for development and testing environments.
     * For production, it is recommended to use the official redisson-spring-boot-starter, which supports:
     * - Cluster mode
     * - Sentinel mode
     * - Master-Slave mode
     * - SSL/TLS encryption
     * - More advanced configuration options
     *
     * Usage: Add dependency org.redisson:redisson-spring-boot-starter:3.29.0
     * Then configure via spring.redis.redisson.config
     */
    private RedisProperties redis = new RedisProperties();

    /**
     * Service registry configuration
     */
    private RegistryProperties registry = new RegistryProperties();

    /**
     * Service discovery configuration
     */
    private DiscoveryProperties discovery = new DiscoveryProperties();

    /**
     * Configuration service configuration
     */
    private ConfigProperties config = new ConfigProperties();

    /**
     * Load balancer configuration (starter-level)
     */
    private LoadBalancerProperties loadBalancer = new LoadBalancerProperties();

    /**
     * Client invocation configuration (retry/circuit-breaker, etc.)
     */
    private InvokerProperties invoker = new InvokerProperties();

    /**
     * Message queue (MQ) configuration
     */
    private MqProperties mq = new MqProperties();

    /**
     * Rate limiting configuration
     */
    private RateLimitProperties ratelimit = new RateLimitProperties();

    /**
     * Redis single-server configuration (simplified)
     *
     * Only supports single-server mode, suitable for quick development and testing.
     * For production, please use redisson-spring-boot-starter.
     */
    @Data
    public static class RedisProperties {
        /**
         * Redis server address
         */
        private String address = "redis://127.0.0.1:6379";

        /**
         * Connection password
         */
        private String password;

        /**
         * Database index
         */
        private int database = 0;

        /**
         * Connection timeout (milliseconds)
         */
        private int connectTimeout = 3000;

        /**
         * Response timeout (milliseconds)
         */
        private int timeout = 3000;

        /**
         * Connection pool size
         */
        private int connectionPoolSize = 64;

        /**
         * Minimum idle connection count
         */
        private int connectionMinimumIdleSize = 10;
    }

    @Data
    public static class RegistryProperties {
        /**
         * Whether to enable service registry
         */
        private boolean enabled = true;

        /**
         * Heartbeat interval (seconds)
         */
        private int heartbeatInterval = 30;

        /**
         * Heartbeat timeout (seconds)
         */
        private int heartbeatTimeout = 90;

        /**
         * Whether to automatically register this service instance
         */
        private boolean autoRegister = true;

        /**
         * Service instance configuration
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
         * Service name
         */
        private String serviceName = "${spring.application.name}";

        /**
         * Instance ID; if not set, it will be auto-generated
         */
        private String instanceId;

        /**
         * Service host address; if not set, it will be auto-detected
         */
        private String host;

        /**
         * Service port; if not set, server.port will be used
         */
        private Integer port;

        /**
         * Service weight
         */
        private int weight = 1;

        /**
         * Whether the instance is enabled
         */
        private boolean enabled = true;

        /**
         * Protocol type
         */
        private String protocol = "http";

        /**
         * Whether this is an ephemeral instance
         * true: ephemeral instance, relies on client heartbeat, auto-deleted on timeout
         * false: persistent instance, server-side health check, marked only (not deleted)
         */
        private Boolean ephemeral;

        /**
         * Instance metadata
         */
        private java.util.Map<String, String> metadata = new java.util.HashMap<>();
    }

    @Data
    public static class DiscoveryProperties {
        /**
         * Whether to enable service discovery
         */
        private boolean enabled = true;

        /**
         * Whether to discover only healthy instances
         */
        private boolean healthyOnly = true;

        /**
         * Service discovery cache time (seconds)
         */
        private int cacheTime = 30;
    }

    @Data
    public static class ConfigProperties {
        /**
         * Whether to enable configuration service
         */
        private boolean enabled = true;

        /**
         * Default configuration group
         */
        private String defaultGroup = "DEFAULT_GROUP";

        /**
         * Configuration refresh interval (seconds)
         */
        private int refreshInterval = 30;

        /**
         * Whether to enable automatic configuration refresh
         */
        private boolean autoRefresh = true;

        /**
         * Number of configuration history entries to retain
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
         * Strategy: scored | wrr | weighted-random | consistent-hash
         */
        private String strategy = "scored";

        // scored configuration
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
        /** Whether to enable the MQ module (only affects auto-configuration) */
        private boolean enabled = true;

        /** Default partition count (used when a topic is first written to) */
        private int defaultPartitionCount = 1;

        /** Consumer thread pool size */
        private int workerThreads = 8;

        /** Scheduler thread pool size (renewal/rebalancing/moving) */
        private int schedulerThreads = 2;

        /** Maximum number of messages per pull */
        private int consumerBatchCount = 10;

        /** Pull timeout (milliseconds) */
        private long consumerPollTimeoutMs = 1000;

        /** Lease TTL (seconds) */
        private int leaseTtlSeconds = 15;

        /** Rebalancing interval (seconds) */
        private int rebalanceIntervalSec = 5;

        /** Renewal interval (seconds) */
        private int renewIntervalSec = 3;

        /** Pending scan interval (seconds) */
        private int pendingScanIntervalSec = 30;

        /** Idle threshold for identifying orphaned pending messages (milliseconds) */
        private long claimIdleMs = 300000;

        /** Claim batch size */
        private int claimBatchSize = 50;

        /** Backpressure: maximum number of concurrently in-flight messages per consumer instance (0=disabled) */
        private int maxInFlight = 0;

        /** Partition lease upper limit: maximum number of partitions each consumer instance can hold (0=default=workerThreads) */
        private int maxLeasedPartitionsPerConsumer = 0;

        /** Maximum retry attempts */
        private int retryMaxAttempts = 5;

        /** Retry base backoff (milliseconds) */
        private long retryBaseBackoffMs = 1000;

        /** Retry max backoff (milliseconds) */
        private long retryMaxBackoffMs = 60000;

        /** Retry mover batch size */
        private int retryMoverBatch = 100;

        /** Retry mover interval (seconds) */
        private int retryMoverIntervalSec = 1;

        /** Retry mover lock wait time (milliseconds) */
        private long retryLockWaitMs = 100;

        /** Retry mover lock lease duration (milliseconds) */
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
         * Enabled metric types, e.g. [memory, cpu, application, disk, network]
         */
        private java.util.Set<String> enabled = new java.util.HashSet<>(java.util.Set.of("memory","cpu","application","disk","network"));

        /**
         * Metric collection intervals (keyed by metric type), e.g. memory: 30s, cpu: 60s
         */
        private java.util.Map<String, java.time.Duration> intervals = new java.util.HashMap<>();

        /**
         * Default collection interval
         */
        private java.time.Duration defaultInterval = java.time.Duration.ofMinutes(1);

        /**
         * Whether to update immediately on significant changes
         */
        private boolean immediateUpdateOnSignificantChange = true;

        /**
         * Collection timeout
         */
        private java.time.Duration timeout = java.time.Duration.ofSeconds(5);
    }

    @lombok.Data
    public static class RateLimitProperties {
        /** Whether to enable rate limiting (only affects auto-configuration) */
        private boolean enabled = false;
        /** Backend: memory | redis */
        private String backend = "memory";
        /** Window size (milliseconds) */
        private long windowMs = 1000;
        /** Maximum number of requests allowed within the window */
        private int limit = 100;
        /** Redis key prefix (only effective when backend is redis) */
        private String keyPrefix = "streaming:rl";

        /**
         * Named policy collection (multiple rate limiters can be declared simultaneously).
         * When non-empty, a RateLimiterRegistry will be created from this collection for lookup by name.
         */
        private java.util.Map<String, Policy> policies = new java.util.HashMap<>();

        /**
         * Default policy name (used when injecting a single RateLimiter).
         */
        private String defaultName = "default";

        @lombok.Data
        public static class Policy {
            /** Algorithm: sliding | token-bucket | leaky-bucket */
            private String algorithm = "sliding";
            /** Backend: memory | redis (only sliding/token-bucket support redis) */
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
