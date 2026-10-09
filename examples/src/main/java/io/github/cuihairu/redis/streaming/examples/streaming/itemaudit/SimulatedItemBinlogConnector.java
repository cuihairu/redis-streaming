package io.github.cuihairu.redis.streaming.examples.streaming.itemaudit;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.impl.AbstractCDCConnector;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulated binlog connector: replays a scripted list of {@link ChangeEvent}s through the
 * real {@link io.github.cuihairu.redis.streaming.cdc.CDCConnector} contract, so the audit
 * pipeline is developed against the exact seam a production deployment uses. Swapping this
 * class for {@code MySQLBinlogCDCConnector} (same interface, real binlog reader) is the
 * only change needed to run against a live MySQL — no gate code is touched.
 *
 * <p>The feed is bounded and idles once drained, which lets {@code CDCSource}'s idle-poll
 * termination end the in-memory demo naturally.
 */
public class SimulatedItemBinlogConnector extends AbstractCDCConnector {

    private final ArrayDeque<ChangeEvent> pending;
    private final int batchSize;

    public SimulatedItemBinlogConnector(List<ChangeEvent> scripted) {
        this(scripted, 16);
    }

    public SimulatedItemBinlogConnector(List<ChangeEvent> scripted, int batchSize) {
        super(CDCConfigurationBuilder.forDatabasePolling("simulated-item-binlog").build());
        this.pending = new ArrayDeque<>(scripted);
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public String getName() {
        return "simulated-item-binlog";
    }

    @Override
    protected void doStart() {
        // nothing to open: the script is in memory
    }

    @Override
    protected void doStop() {
        // nothing to close
    }

    @Override
    protected List<ChangeEvent> doPoll() {
        List<ChangeEvent> batch = new ArrayList<>(Math.min(batchSize, pending.size()));
        while (batch.size() < batchSize && !pending.isEmpty()) {
            batch.add(pending.poll());
        }
        return batch;
    }

    @Override
    protected void doCommit(String position) {
        this.currentPosition = position;
    }

    @Override
    protected void doResetToPosition(String position) {
        // a replayed demo restarts from the head of the script
    }
}
