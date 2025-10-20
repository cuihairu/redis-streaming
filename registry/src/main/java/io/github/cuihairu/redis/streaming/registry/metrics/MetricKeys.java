package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * 指标键常量
 * 定义所有标准的指标键名称
 */
public final class MetricKeys {

    private MetricKeys() {
        // 防止实例化
    }

    // ==================== 内存相关指标 ====================

    /**
     * 堆内存使用百分比
     */
    public static final String MEMORY_HEAP_USAGE_PERCENT = "memory.heap_usagePercent";

    /**
     * 堆内存已使用量（字节）
     */
    public static final String MEMORY_HEAP_USED = "memory.heap_used";

    /**
     * 堆内存最大值（字节）
     */
    public static final String MEMORY_HEAP_MAX = "memory.heap_max";

    /**
     * 非堆内存使用百分比
     */
    public static final String MEMORY_NON_HEAP_USAGE_PERCENT = "memory.nonHeap_usagePercent";

    /**
     * 非堆内存已使用量（字节）
     */
    public static final String MEMORY_NON_HEAP_USED = "memory.nonHeap_used";

    // ==================== CPU 相关指标 ====================

    /**
     * 进程CPU负载（0~1 小数）。消费端如需百分比，请自行 *100。
     */
    public static final String CPU_PROCESS_LOAD = "cpu.processCpuLoad";

    /**
     * 系统CPU负载（0~1 小数）。消费端如需百分比，请自行 *100。
     */
    public static final String CPU_SYSTEM_LOAD = "cpu.systemCpuLoad";

    /**
     * CPU核心数
     */
    public static final String CPU_AVAILABLE_PROCESSORS = "cpu.availableProcessors";

    // ==================== 磁盘相关指标 ====================

    /**
     * 磁盘使用百分比
     */
    public static final String DISK_USAGE_PERCENT = "disk.usagePercent";

    /**
     * 磁盘总空间（字节）
     */
    public static final String DISK_TOTAL_SPACE = "disk.totalSpace";

    /**
     * 磁盘可用空间（字节）
     */
    public static final String DISK_FREE_SPACE = "disk.freeSpace";

    /**
     * 磁盘已用空间（字节）
     */
    public static final String DISK_USED_SPACE = "disk.usedSpace";

    // ==================== 应用相关指标 ====================

    /**
     * 应用线程数
     */
    public static final String APPLICATION_THREAD_COUNT = "application.threadCount";

    /**
     * 应用启动时间（毫秒时间戳）
     */
    public static final String APPLICATION_START_TIME = "application.startTime";

    /**
     * 应用运行时间（毫秒）
     */
    public static final String APPLICATION_UPTIME = "application.uptime";

    /**
     * 守护线程数
     */
    public static final String APPLICATION_DAEMON_THREAD_COUNT = "application.daemonThreadCount";

    /**
     * 峰值线程数
     */
    public static final String APPLICATION_PEAK_THREAD_COUNT = "application.peakThreadCount";

    // ==================== 健康状态 ====================

    /**
     * 健康状态
     */
    public static final String HEALTHY = "healthy";

    /**
     * 启用状态
     */
    public static final String ENABLED = "enabled";

    // ==================== GC 相关指标 ====================

    /**
     * GC次数
     */
    public static final String GC_COUNT = "gc.count";

    /**
     * GC总耗时（毫秒）
     */
    public static final String GC_TIME = "gc.time";

    // ==================== 网络相关指标 ====================

    /**
     * 连接数
     */
    public static final String NETWORK_CONNECTIONS = "network.connections";

    /**
     * 请求数
     */
    public static final String NETWORK_REQUESTS = "network.requests";

    /**
     * 错误数
     */
    public static final String NETWORK_ERRORS = "network.errors";

    // ==================== 兼容性（旧键）====================
    // 过渡期双写这些旧键，消费端可优先使用上面的扁平/分层统一键；下个小版本将清理这些旧键。

    /**
     * 旧键：进程CPU负载（百分比或小数不定）。请改用 CPU_PROCESS_LOAD（0~1）。
     */
    @Deprecated public static final String LEGACY_CPU_PROCESS_LOAD = "CPU_PROCESS_LOAD";

    /**
     * 旧键：系统CPU负载（百分比或小数不定）。请改用 CPU_SYSTEM_LOAD（0~1）。
     */
    @Deprecated public static final String LEGACY_CPU_SYSTEM_LOAD = "CPU_SYSTEM_LOAD";

    /**
     * 旧键：可用CPU核心数。请改用 CPU_AVAILABLE_PROCESSORS。
     */
    @Deprecated public static final String LEGACY_CPU_AVAILABLE_PROCESSORS = "CPU_AVAILABLE_PROCESSORS";

    /**
     * 旧键：堆内存已使用（字节）。请改用 MEMORY_HEAP_USED。
     */
    @Deprecated public static final String LEGACY_MEMORY_HEAP_USED = "MEMORY_HEAP_USED";

    /**
     * 旧键：堆内存最大（字节）。请改用 MEMORY_HEAP_MAX。
     */
    @Deprecated public static final String LEGACY_MEMORY_HEAP_MAX = "MEMORY_HEAP_MAX";

    /**
     * 旧键：磁盘总/已用/可用（字节）。请改用 DISK_TOTAL_SPACE / DISK_USED_SPACE / DISK_FREE_SPACE。
     */
    @Deprecated public static final String LEGACY_DISK_TOTAL_SPACE = "DISK_TOTAL_SPACE";
    @Deprecated public static final String LEGACY_DISK_USED_SPACE  = "DISK_USED_SPACE";
    @Deprecated public static final String LEGACY_DISK_FREE_SPACE  = "DISK_FREE_SPACE";

    /**
     * 旧键：系统平均负载。仅用于旧版兜底，不建议消费端再依赖。
     */
    @Deprecated public static final String LEGACY_SYSTEM_LOAD_AVERAGE = "SYSTEM_LOAD_AVERAGE";
    @Deprecated public static final String LEGACY_LOAD_AVERAGE = "LOAD_AVERAGE";
}
