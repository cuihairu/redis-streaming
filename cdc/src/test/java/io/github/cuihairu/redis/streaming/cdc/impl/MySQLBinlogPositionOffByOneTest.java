package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CDC-M5: the ChangeEvent position stamp is one event behind. The rows handlers read
 * {@code getCurrentPosition()} while the watermark only advances AFTER the handler returns
 * ({@code updateCurrentPosition(event)} at the end of the listener), so every emitted row
 * carries the PREVIOUS event's binlog position. Committing such an event and restarting
 * replays the just-committed event (duplicate writes).
 *
 * Each ChangeEvent must instead carry its OWN event's next position — the resume point at
 * which that event has been fully processed.
 */
class MySQLBinlogPositionOffByOneTest {

    @Test
    void everyRowEventIsStampedWithItsOwnNextPositionNotThePreviousEvent() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql-cdc-m5")
                .username("u").password("p").build();
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(config);
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");
        setField(connector, "columnNameResolver", new MySQLColumnNameResolver() {
            @Override public List<String> resolve(String database, String table) { return List.of("id", "name"); }
            @Override public void close() {}
        });

        long tableId = 1L;
        TableMapEventData tableMap = mock(TableMapEventData.class);
        when(tableMap.getTableId()).thenReturn(tableId);
        when(tableMap.getDatabase()).thenReturn("db");
        when(tableMap.getTable()).thenReturn("t");
        invokeHandle(connector, event(EventType.TABLE_MAP, 100, tableMap));

        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{1, "a"}));
        invokeHandle(connector, event(EventType.WRITE_ROWS, 200, write));

        UpdateRowsEventData update = mock(UpdateRowsEventData.class);
        when(update.getTableId()).thenReturn(tableId);
        when(update.getRows()).thenReturn(List.of(
                Map.entry(new Serializable[]{1, "a"}, new Serializable[]{1, "b"})));
        invokeHandle(connector, event(EventType.UPDATE_ROWS, 300, update));

        DeleteRowsEventData delete = mock(DeleteRowsEventData.class);
        when(delete.getTableId()).thenReturn(tableId);
        when(delete.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{1, "b"}));
        invokeHandle(connector, event(EventType.DELETE_ROWS, 400, delete));

        List<ChangeEvent> events = connector.poll();
        assertEquals(3, events.size());

        // Each stamp must be the END of the very event that produced the row: committing it
        // and resuming skips exactly the delivered events (no replay, no gap).
        assertEquals("mysql-bin.000001:200", events.get(0).getPosition(),
                "INSERT must carry its own WRITE_ROWS next position, not the previous event's");
        assertEquals("mysql-bin.000001:300", events.get(1).getPosition(),
                "UPDATE must carry its own UPDATE_ROWS next position");
        assertEquals("mysql-bin.000001:400", events.get(2).getPosition(),
                "DELETE must carry its own DELETE_ROWS next position");
    }

    @Test
    void firstRowEventOnAFreshConnectorIsNeverCommittedAsNullPosition() {
        // Before the fix the watermark only advanced at the END of the listener, so a rows
        // event preceded solely by its TABLE_MAP still reported the TABLE_MAP stamp; with no
        // event seen at all the stamp would be null. After the fix the rows event advances
        // the watermark to its own end before stamping.
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql-cdc-m5b")
                .username("u").password("p").build();
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(config);
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000003");

        long tableId = 9L;
        TableMapEventData tableMap = mock(TableMapEventData.class);
        when(tableMap.getTableId()).thenReturn(tableId);
        when(tableMap.getDatabase()).thenReturn("db");
        when(tableMap.getTable()).thenReturn("t");
        // columns stay unresolved (no resolver) -> row keys fall back to col_i, still emitted
        invokeHandle(connector, event(EventType.TABLE_MAP, 150, tableMap));

        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{7, "x"}));
        invokeHandle(connector, event(EventType.WRITE_ROWS, 250, write));

        List<ChangeEvent> events = connector.poll();
        assertEquals(1, events.size());
        assertEquals("mysql-bin.000003:250", events.get(0).getPosition(),
                "the very first emitted event must not carry a stale position");
    }

    // ===== harness identical to MySQLBinlogCDCConnectorEventHandlingTest (pre-fix API only) =====

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
