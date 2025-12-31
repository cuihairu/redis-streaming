package io.github.cuihairu.redis.streaming.cdc.impl;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.RotateEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MySQLBinlogCDCConnectorEventHandlingTest {

    @Test
    void writeUpdateDeleteAndRotateEventsEnqueueChangeEventsAndAdvancePosition() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql")
                .username("u")
                .password("p")
                .build();

        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(config);
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");
        setField(connector, "columnNameResolver", new MySQLColumnNameResolver() {
            @Override
            public List<String> resolve(String database, String table) {
                return List.of("id", "name");
            }

            @Override
            public void close() {}
        });

        long tableId = 1L;
        TableMapEventData tableMap = mock(TableMapEventData.class);
        when(tableMap.getTableId()).thenReturn(tableId);
        when(tableMap.getDatabase()).thenReturn("db");
        when(tableMap.getTable()).thenReturn("t");

        invokeHandle(connector, event(EventType.TABLE_MAP, 10, tableMap));

        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(List.<Serializable[]>of(
                new Serializable[]{1, "a"},
                new Serializable[]{2, "b"}
        ));
        invokeHandle(connector, event(EventType.WRITE_ROWS, 11, write));

        UpdateRowsEventData update = mock(UpdateRowsEventData.class);
        when(update.getTableId()).thenReturn(tableId);
        when(update.getRows()).thenReturn(List.of(
                Map.entry(new Serializable[]{1, "a"}, new Serializable[]{1, "a2"})
        ));
        invokeHandle(connector, event(EventType.UPDATE_ROWS, 12, update));

        DeleteRowsEventData delete = mock(DeleteRowsEventData.class);
        when(delete.getTableId()).thenReturn(tableId);
        when(delete.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{2, "b"}));
        invokeHandle(connector, event(EventType.DELETE_ROWS, 13, delete));

        RotateEventData rotate = mock(RotateEventData.class);
        when(rotate.getBinlogFilename()).thenReturn("mysql-bin.000002");
        when(rotate.getBinlogPosition()).thenReturn(4L);
        invokeHandle(connector, event(EventType.ROTATE, 0, rotate));

        List<ChangeEvent> events = connector.poll();
        assertEquals(4, events.size());
        assertEquals(ChangeEvent.EventType.INSERT, events.get(0).getEventType());
        assertEquals(ChangeEvent.EventType.INSERT, events.get(1).getEventType());
        assertEquals(ChangeEvent.EventType.UPDATE, events.get(2).getEventType());
        assertEquals(ChangeEvent.EventType.DELETE, events.get(3).getEventType());

        ChangeEvent first = events.get(0);
        assertEquals("db", first.getDatabase());
        assertEquals("t", first.getTable());
        assertNotNull(first.getAfterData());
        assertEquals(1, first.getAfterData().get("id"));
        assertEquals("a", first.getAfterData().get("name"));

        assertEquals("mysql-bin.000002", connector.getBinlogFilename());
        assertEquals("mysql-bin.000002:4", connector.getCurrentPosition());
    }

    @Test
    void tableFilterCanExcludeTables() {
        CDCConfiguration config = CDCConfigurationBuilder.forMySQLBinlog("mysql")
                .username("u")
                .password("p")
                .build();

        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(config);
        connector.running.set(true);
        setField(connector, "binlogFilename", "mysql-bin.000001");
        setField(connector, "tableFilter", TableFilter.from(List.of("db.allowed.*"), List.of()));

        long tableId = 1L;
        TableMapEventData tableMap = mock(TableMapEventData.class);
        when(tableMap.getTableId()).thenReturn(tableId);
        when(tableMap.getDatabase()).thenReturn("db");
        when(tableMap.getTable()).thenReturn("t");

        invokeHandle(connector, event(EventType.TABLE_MAP, 10, tableMap));

        WriteRowsEventData write = mock(WriteRowsEventData.class);
        when(write.getTableId()).thenReturn(tableId);
        when(write.getRows()).thenReturn(List.<Serializable[]>of(new Serializable[]{1, "a"}));
        invokeHandle(connector, event(EventType.WRITE_ROWS, 11, write));

        assertTrue(connector.poll().isEmpty(), "filtered table should not emit events");
        assertFalse(connector.getCurrentPosition() == null || connector.getCurrentPosition().isBlank());
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
