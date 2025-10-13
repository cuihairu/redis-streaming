package io.github.cuihairu.redis.streaming.metrics;

/**
 * Utility class for timing code execution and recording metrics.
 */
public class MetricTimer implements AutoCloseable {

    private final String name;
    private final MetricCollector collector;
    private final long startTime;

    private MetricTimer(String name, MetricCollector collector) {
        this.name = name;
        this.collector = collector;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Start a new timer
     *
     * @param name The name of the timer
     * @param collector The collector to record the metric to
     * @return A new MetricTimer instance
     */
    public static MetricTimer start(String name, MetricCollector collector) {
        return new MetricTimer(name, collector);
    }

    /**
     * Stop the timer and record the duration
     *
     * @return The duration in milliseconds
     */
    public long stop() {
        long duration = System.currentTimeMillis() - startTime;
        if (collector != null) {
            collector.recordTimer(name, duration);
        }
        return duration;
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * Time a runnable and record the duration
     *
     * @param name The name of the timer
     * @param collector The collector to record to
     * @param runnable The code to time
     */
    public static void time(String name, MetricCollector collector, Runnable runnable) {
        try (MetricTimer timer = start(name, collector)) {
            runnable.run();
        }
    }

    /**
     * Time a callable and record the duration, returning the result
     *
     * @param name The name of the timer
     * @param collector The collector to record to
     * @param callable The code to time
     * @param <T> The return type
     * @return The result of the callable
     * @throws Exception if the callable throws
     */
    public static <T> T time(String name, MetricCollector collector,
                             java.util.concurrent.Callable<T> callable) throws Exception {
        try (MetricTimer timer = start(name, collector)) {
            return callable.call();
        }
    }
}
