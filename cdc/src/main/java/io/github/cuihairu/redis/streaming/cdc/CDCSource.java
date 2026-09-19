package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;

import java.util.List;
import java.util.Objects;

/**
 * Adapter that bridges a {@link CDCConnector} into the streaming API as a {@link StreamSource}.
 *
 * <p>{@link #run(SourceContext)} drains the connector in bounded passes: it polls events until
 * the connector reports {@code maxIdlePolls} consecutive empty polls (or the context is
 * stopped), sleeping {@code pollIntervalMs} between polls. This makes the source terminate on
 * idle connectors so it can be used with pull-based engines, while still forwarding every
 * captured {@link ChangeEvent}.</p>
 */
public class CDCSource implements StreamSource<ChangeEvent> {

    private static final long serialVersionUID = 1L;

    private final CDCConnector connector;
    private final long pollIntervalMs;
    private final int maxIdlePolls;

    public CDCSource(CDCConnector connector) {
        this(connector, 100L, 3);
    }

    /**
     * @param connector      the connector to drain; must already be started or startable
     * @param pollIntervalMs sleep between polls when no events are returned
     * @param maxIdlePolls   stop the run after this many consecutive empty polls (must be &gt;= 1)
     */
    public CDCSource(CDCConnector connector, long pollIntervalMs, int maxIdlePolls) {
        this.connector = Objects.requireNonNull(connector, "connector");
        if (pollIntervalMs < 0) {
            throw new IllegalArgumentException("pollIntervalMs must be >= 0");
        }
        if (maxIdlePolls < 1) {
            throw new IllegalArgumentException("maxIdlePolls must be >= 1");
        }
        this.pollIntervalMs = pollIntervalMs;
        this.maxIdlePolls = maxIdlePolls;
    }

    @Override
    public void run(SourceContext<ChangeEvent> ctx) throws Exception {
        if (!connector.isRunning()) {
            connector.start().join();
        }
        int idle = 0;
        while (!ctx.isStopped() && idle < maxIdlePolls) {
            List<ChangeEvent> events = connector.poll();
            if (events == null || events.isEmpty()) {
                idle++;
                if (idle < maxIdlePolls && pollIntervalMs > 0) {
                    Thread.sleep(pollIntervalMs);
                }
                continue;
            }
            idle = 0;
            for (ChangeEvent event : events) {
                if (ctx.isStopped()) {
                    return;
                }
                if (event == null) {
                    continue;
                }
                long ts = event.getTimestamp() == null ? System.currentTimeMillis() : event.getTimestamp().toEpochMilli();
                ctx.collectWithTimestamp(event, ts);
            }
        }
    }

    @Override
    public void cancel() {
        if (connector.isRunning()) {
            connector.stop().join();
        }
    }
}
