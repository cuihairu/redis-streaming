package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CDC-M3: the scheduled-polling task checked {@code eventListener == null}, drained the
 * batch via poll(), and only then re-read the (now null) listener inside notifyEvent —
 * a concurrent {@code setEventListener(null)} in that window made the connector take the
 * events out of the queue and silently drop them.
 *
 * A batch that has already been drained must be delivered to the listener that was
 * registered when the drain started (captured once); deregistering afterwards must not
 * discard it. The window is driven deterministically: doPoll parks after draining until
 * the test releases it, so the deregistration provably happens between drain and notify.
 */
class CDCListenerDeregistrationDropTest {

    private GatedPullConnector connector;

    @BeforeEach
    void setUp() throws Exception {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("m3-drop")
                .username("u").password("p")
                .pollingIntervalMs(30)
                .build();
        connector = new GatedPullConnector(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        connector.releaseGate.countDown();
        try {
            connector.stop().get(10, TimeUnit.SECONDS);
        } catch (Exception ignore) {
        }
    }

    @Test
    void batchDrainedBeforeDeregistrationMustStillBeDeliveredToCapturedListener() throws Exception {
        List<ChangeEvent> delivered = new CopyOnWriteArrayList<>();
        CDCEventListener listener = new CDCEventListener() {
            @Override
            public void onEvents(String connectorName, List<ChangeEvent> events) {
                delivered.addAll(events);
            }
        };

        connector.queue.offer(new ChangeEvent(ChangeEvent.EventType.INSERT, "db", "t", "1", null, Map.of("id", 1)));
        connector.queue.offer(new ChangeEvent(ChangeEvent.EventType.UPDATE, "db", "t", "1", Map.of("id", 1), Map.of("id", 2)));
        connector.queue.offer(new ChangeEvent(ChangeEvent.EventType.DELETE, "db", "t", "2", Map.of("id", 2), null));

        connector.setEventListener(listener);
        connector.start().get(10, TimeUnit.SECONDS);

        // Scheduler drained the batch and is now parked INSIDE doPoll, before notify.
        assertTrue(connector.drained.await(10, TimeUnit.SECONDS),
                "scheduled task must have entered doPoll and drained the batch");
        assertTrue(connector.releaseGate.getCount() == 1,
                "parking must still hold the drained batch");

        connector.setEventListener(null);      // deregistration inside the drain->notify window
        connector.releaseGate.countDown();     // let the drained batch reach the delivery point

        assertTrue(waitUntil(() -> delivered.size() >= 3, 3_000),
                "a batch already drained before deregistration must not be silently discarded, delivered=" + delivered);
    }

    private static boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(cond.call())) return true;
            Thread.sleep(20);
        }
        return Boolean.TRUE.equals(cond.call());
    }

    /** Minimal connector exposing the production wiring (doStart -> startScheduledPolling). */
    static class GatedPullConnector extends AbstractCDCConnector {
        final ConcurrentLinkedQueue<ChangeEvent> queue = new ConcurrentLinkedQueue<>();
        final CountDownLatch drained = new CountDownLatch(1);
        final CountDownLatch releaseGate = new CountDownLatch(1);

        GatedPullConnector(CDCConfiguration configuration) {
            super(configuration);
        }

        @Override
        protected void doStart() {
            startScheduledPolling();
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected List<ChangeEvent> doPoll() {
            List<ChangeEvent> out = new ArrayList<>();
            ChangeEvent e;
            while ((e = queue.poll()) != null) {
                out.add(e);
            }
            if (!out.isEmpty()) {
                drained.countDown();
                try {
                    releaseGate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return out;
        }

        @Override
        protected void doCommit(String position) {
        }

        @Override
        protected void doResetToPosition(String position) {
        }
    }
}
