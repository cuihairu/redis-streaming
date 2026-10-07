package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.CDCEventListener;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CDC-M1: the MySQL binlog connector's event queue must be bounded and its producer must
 * apply loss-free backpressure while a slow consumer drains it. The old unbounded
 * ConcurrentLinkedQueue let the binlog listener thread out-run {@code poll()} consumers
 * without limit (heap growth / OOM on replication bursts).
 */
class EventQueueBackpressureTest {

    @Test
    void defaultCapacityIsTenThousand() throws Exception {
        MySQLBinlogCDCConnector connector = newConnector("mysql-bp-default", null);
        assertEquals(10_000, remainingCapacityOf(connector),
                "without the property the queue must still be bounded at the default capacity");
    }

    @Test
    void invalidCapacityFallsBackToTenThousand() throws Exception {
        MySQLBinlogCDCConnector connector = newConnector("mysql-bp-invalid", "abc");
        assertEquals(10_000, remainingCapacityOf(connector),
                "a typo must not produce an unbounded (or zero-capacity) queue");
    }

    @Test
    void validCapacityIsConfigurable() throws Exception {
        MySQLBinlogCDCConnector connector = newConnector("mysql-bp-custom", "3");
        assertEquals(3, remainingCapacityOf(connector));
    }

    @Test
    void fullQueueBlocksProducerWithoutLossAndRecoversWhenConsumerDrains() throws Exception {
        MySQLBinlogCDCConnector connector = newConnector("mysql-bp-block", "2");
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");
        long tableId = 1L;
        feedTableMap(connector, tableId, 100);

        BlockingQueue<ChangeEvent> queue = queueOf(connector);
        feedWriteRow(connector, tableId, 200, 1); // fills the queue (1/2)
        feedWriteRow(connector, tableId, 300, 2); // fills the queue (2/2)
        assertEquals(2, queue.size(), "capacity-2 queue must be full now");

        // The listener thread must BLOCK on the third event, not grow the queue.
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                invokeHandle(connector, writeRowEvent(tableId, 400, 3));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "binlog-listener");
        producer.start();
        producer.join(300);
        assertTrue(producer.isAlive(), "producer must still be blocked while the queue is full");
        assertEquals(2, queue.size(), "a blocked producer must not have grown the queue");

        // Consumer drains: the blocked producer completes and nothing is lost. The batch
        // BOUNDARY is timing-dependent — the drain's isEmpty check can race the unblocked
        // producer's refill and sweep the third event into the same batch — so assert on
        // the merged stream (no loss, FIFO order), not on where batches split. The
        // producer's offer must return before its thread ends, so after join() all three
        // events are in firstBatch or still queued for the follow-up poll.
        List<ChangeEvent> firstBatch = connector.poll();
        producer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(producer.isAlive(), "draining must release the blocked producer");
        assertNull(failure.get(), () -> "producer must complete cleanly after drain: " + failure.get());
        assertTrue(firstBatch.size() >= 2, "drain must return the two pre-filled events, got=" + firstBatch.size());
        assertEquals("mysql-bin.000001:200", firstBatch.get(0).getPosition());
        assertEquals("mysql-bin.000001:300", firstBatch.get(1).getPosition());

        List<ChangeEvent> all = new java.util.ArrayList<>(firstBatch);
        all.addAll(connector.poll());
        assertEquals(List.of("mysql-bin.000001:200", "mysql-bin.000001:300", "mysql-bin.000001:400"),
                all.stream().map(ChangeEvent::getPosition).collect(java.util.stream.Collectors.toList()),
                "drain must deliver every event exactly once, in order");
        assertEquals(400, connector.getBinlogPosition(),
                "watermark only advances after every row of the event was enqueued");
    }

    @Test
    void stopWhileProducerBlockedLeavesWatermarkBehindUndeliveredEvent() throws Exception {
        MySQLBinlogCDCConnector connector = newConnector("mysql-bp-stop", "1");
        List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();
        connector.setEventListener(new CDCEventListener() {
            @Override
            public void onConnectorError(String connectorName, Throwable error) {
                reportedErrors.add(error);
            }
        });
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000002");
        long tableId = 7L;
        feedTableMap(connector, tableId, 100);
        feedWriteRow(connector, tableId, 200, 1); // queue full (1/1)

        BlockingQueue<ChangeEvent> queue = queueOf(connector);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                invokeHandle(connector, writeRowEvent(tableId, 300, 2));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "binlog-listener");
        producer.start();
        producer.join(300);
        assertTrue(producer.isAlive(), "producer must be parked in the backpressure loop");

        connector.stop().get(10, TimeUnit.SECONDS);
        producer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(producer.isAlive(), "stop must release the blocked producer promptly");
        // the stop-time drop is loud, not silent: it is reported as a connector error
        assertEquals(1, reportedErrors.size());
        assertTrue(reportedErrors.get(0) instanceof IllegalStateException,
                () -> "expected the backpressure IllegalStateException, got: " + reportedErrors.get(0));

        // CDC-M1 stop semantics: the undelivered event is NOT enqueued, so the trailing
        // watermark update never runs — restart resumes AT the last delivered event.
        assertEquals(200, connector.getBinlogPosition(),
                "watermark must stay behind the event that was not delivered");
        assertEquals(1, queue.size(), "captured-but-undelivered events survive a stop (restart semantics)");
        assertEquals("mysql-bin.000002:200", queue.poll().getPosition());
    }

    // ===== harness (same style as MySQLBinlogPositionOffByOneTest) =====

    private static MySQLBinlogCDCConnector newConnector(String name, String capacityProperty) {
        CDCConfigurationBuilder builder = CDCConfigurationBuilder.forMySQLBinlog(name)
                .username("u").password("p").batchSize(100);
        if (capacityProperty != null) {
            builder.property("event.queue.capacity", capacityProperty);
        }
        return new MySQLBinlogCDCConnector(builder.build());
    }

    private static int remainingCapacityOf(MySQLBinlogCDCConnector connector) throws Exception {
        assertTrue(queueOf(connector) instanceof ArrayBlockingQueue,
                "CDC-M1: the event queue must be a bounded ArrayBlockingQueue");
        return queueOf(connector).remainingCapacity();
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<ChangeEvent> queueOf(MySQLBinlogCDCConnector connector) throws Exception {
        Field f = MySQLBinlogCDCConnector.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        return (BlockingQueue<ChangeEvent>) f.get(connector);
    }

    private static void feedTableMap(MySQLBinlogCDCConnector connector, long tableId, long nextPosition) {
        TableMapEventData tableMap = mock(TableMapEventData.class);
        when(tableMap.getTableId()).thenReturn(tableId);
        when(tableMap.getDatabase()).thenReturn("db");
        when(tableMap.getTable()).thenReturn("t");
        invokeHandle(connector, event(EventType.TABLE_MAP, nextPosition, tableMap));
    }

    private static void feedWriteRow(MySQLBinlogCDCConnector connector, long tableId,
                                     long nextPosition, int id) {
        invokeHandle(connector, writeRowEvent(tableId, nextPosition, id));
    }

    private static Event writeRowEvent(long tableId, long nextPosition, int id) {
        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{id, "v" + id}));
        return event(EventType.WRITE_ROWS, nextPosition, write);
    }

    private static Event event(EventType type, long nextPosition, Object data) {
        EventHeaderV4 header = mock(EventHeaderV4.class);
        when(header.getEventType()).thenReturn(type);
        when(header.getNextPosition()).thenReturn(nextPosition);
        Event e = mock(Event.class);
        when(e.getHeader()).thenReturn(header);
        when(e.getData()).thenReturn((com.github.shyiko.mysql.binlog.event.EventData) data);
        return e;
    }

    private static void invokeHandle(MySQLBinlogCDCConnector connector, Event event) {
        try {
            Method m = MySQLBinlogCDCConnector.class.getDeclaredMethod("handleBinlogEvent", Event.class);
            m.setAccessible(true);
            m.invoke(connector, event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
