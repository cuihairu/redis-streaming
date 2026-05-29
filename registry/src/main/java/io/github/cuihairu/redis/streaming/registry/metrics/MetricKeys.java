package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * Metric key constants
 * Defines all standard metric key names
 */
public final class MetricKeys {

    private MetricKeys() {
        // Prevent instantiation
    }

    // ==================== Memory-related metrics ====================

    /**
     * Heap memory usage percentage
     */
    public static final String MEMORY_HEAP_USAGE_PERCENT = "memory.heap_usagePercent";

    /**
     * Heap memory used (bytes)
     */
    public static final String MEMORY_HEAP_USED = "memory.heap_used";

    /**
     * Heap memory max (bytes)
     */
    public static final String MEMORY_HEAP_MAX = "memory.heap_max";

    /**
     * Non-heap memory usage percentage
     */
    public static final String MEMORY_NON_HEAP_USAGE_PERCENT = "memory.nonHeap_usagePercent";

    /**
     * Non-heap memory used (bytes)
     */
    public static final String MEMORY_NON_HEAP_USED = "memory.nonHeap_used";

    // ==================== CPU-related metrics ====================

    /**
     * Process CPU load (0~1 decimal). Multiply by 100 if percentage is needed.
     */
    public static final String CPU_PROCESS_LOAD = "cpu.processCpuLoad";

    /**
     * System CPU load (0~1 decimal). Multiply by 100 if percentage is needed.
     */
    public static final String CPU_SYSTEM_LOAD = "cpu.systemCpuLoad";

    /**
     * CPU core count
     */
    public static final String CPU_AVAILABLE_PROCESSORS = "cpu.availableProcessors";

    // ==================== Disk-related metrics ====================

    /**
     * Disk usage percentage
     */
    public static final String DISK_USAGE_PERCENT = "disk.usagePercent";

    /**
     * Disk total space（Bytes）
     */
    public static final String DISK_TOTAL_SPACE = "disk.totalSpace";

    /**
     * Disk free space（Bytes）
     */
    public static final String DISK_FREE_SPACE = "disk.freeSpace";

    /**
     * Disk used space（Bytes）
     */
    public static final String DISK_USED_SPACE = "disk.usedSpace";

    // ==================== Application-related metrics ====================

    /**
     * Application thread count
     */
    public static final String APPLICATION_THREAD_COUNT = "application.threadCount";

    /**
     * Application start time (milliseconds timestamp)
     */
    public static final String APPLICATION_START_TIME = "application.startTime";

    /**
     * Application uptime (milliseconds)
     */
    public static final String APPLICATION_UPTIME = "application.uptime";

    /**
     * Daemon thread count
     */
    public static final String APPLICATION_DAEMON_THREAD_COUNT = "application.daemonThreadCount";

    /**
     * Peak thread count
     */
    public static final String APPLICATION_PEAK_THREAD_COUNT = "application.peakThreadCount";

    // ==================== Health status ====================

    /**
     * Health status
     */
    public static final String HEALTHY = "healthy";

    /**
     * Enabled status
     */
    public static final String ENABLED = "enabled";

    // ==================== GC-related metrics ====================

    /**
     * GC count
     */
    public static final String GC_COUNT = "gc.count";

    /**
     * GC total time（Milliseconds）
     */
    public static final String GC_TIME = "gc.time";

    // ==================== Network-related metrics ====================

    /**
     * Connection count
     */
    public static final String NETWORK_CONNECTIONS = "network.connections";

    /**
     * Request count
     */
    public static final String NETWORK_REQUESTS = "network.requests";

    /**
     * Error count
     */
    public static final String NETWORK_ERRORS = "network.errors";

    // ==================== Compatibility (legacy keys) ====================
    // During the transition period, these legacy keys are written alongside new keys.
    // Consumers should prefer the flat/hierarchical unified keys above; these legacy keys will be removed in the next minor version.

    /**
     * Legacy key: Process CPU load (percentage or decimal, inconsistent). Use CPU_PROCESS_LOAD (0~1) instead.
     */
    @Deprecated public static final String LEGACY_CPU_PROCESS_LOAD = "CPU_PROCESS_LOAD";

    /**
     * Legacy key: System CPU load (percentage or decimal, inconsistent). Use CPU_SYSTEM_LOAD (0~1) instead.
     */
    @Deprecated public static final String LEGACY_CPU_SYSTEM_LOAD = "CPU_SYSTEM_LOAD";

    /**
     * Legacy key: Available CPU core count. Use CPU_AVAILABLE_PROCESSORS instead.
     */
    @Deprecated public static final String LEGACY_CPU_AVAILABLE_PROCESSORS = "CPU_AVAILABLE_PROCESSORS";

    /**
     * Legacy key: Heap memory used (bytes). Use MEMORY_HEAP_USED instead.
     */
    @Deprecated public static final String LEGACY_MEMORY_HEAP_USED = "MEMORY_HEAP_USED";

    /**
     * Legacy key: Heap memory max (bytes). Use MEMORY_HEAP_MAX instead.
     */
    @Deprecated public static final String LEGACY_MEMORY_HEAP_MAX = "MEMORY_HEAP_MAX";

    /**
     * Legacy key: Disk total/used/free (bytes). Use DISK_TOTAL_SPACE / DISK_USED_SPACE / DISK_FREE_SPACE instead.
     */
    @Deprecated public static final String LEGACY_DISK_TOTAL_SPACE = "DISK_TOTAL_SPACE";
    @Deprecated public static final String LEGACY_DISK_USED_SPACE  = "DISK_USED_SPACE";
    @Deprecated public static final String LEGACY_DISK_FREE_SPACE  = "DISK_FREE_SPACE";

    /**
     * Legacy key: System load average. Only used for legacy fallback; consumers should not depend on this.
     */
    @Deprecated public static final String LEGACY_SYSTEM_LOAD_AVERAGE = "SYSTEM_LOAD_AVERAGE";
    @Deprecated public static final String LEGACY_LOAD_AVERAGE = "LOAD_AVERAGE";
}
