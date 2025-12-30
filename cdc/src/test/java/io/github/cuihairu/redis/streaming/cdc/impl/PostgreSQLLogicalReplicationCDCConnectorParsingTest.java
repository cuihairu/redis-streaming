package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgreSQLLogicalReplicationCDCConnectorParsingTest {

    @Test
    void parseLogicalMessageEnqueuesInsertUpdateDelete() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg")
                .postgresqlHostname("localhost")
                .postgresqlPort(5432)
                .postgresqlDatabase("db")
                .username("u")
                .password("p")
                .build();

        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(config);
        connector.running.set(true);
        setField(connector, "replicationStream", null);
        setField(connector, "tableFilter", null);

        String msg = String.join("\n",
                "table public.users:",
                "INSERT: id[integer]:1 name[text]:'a'",
                "UPDATE: id[integer]:1 name[text]:'b' old-key:id[integer]:1 name[text]:'a'",
                "DELETE: id[integer]:1 name[text]:'b'"
        );

        invokeParse(connector, msg);

        List<ChangeEvent> events = connector.poll();
        assertEquals(3, events.size());
        assertEquals(ChangeEvent.EventType.INSERT, events.get(0).getEventType());
        assertEquals(ChangeEvent.EventType.UPDATE, events.get(1).getEventType());
        assertEquals(ChangeEvent.EventType.DELETE, events.get(2).getEventType());

        assertEquals("public", events.get(0).getDatabase());
        assertEquals("users", events.get(0).getTable());
        String key = events.get(0).getKey();
        assertTrue(key.contains("1"));
        assertTrue(key.contains("a"));

        assertEquals("b", events.get(1).getAfterData().get("name"));
        assertEquals("a", events.get(1).getBeforeData().get("name"));
    }

    @Test
    void parseLogicalMessageRespectsTableFilter() {
        CDCConfiguration config = CDCConfigurationBuilder.forPostgreSQLLogicalReplication("pg")
                .postgresqlHostname("localhost")
                .postgresqlPort(5432)
                .postgresqlDatabase("db")
                .username("u")
                .password("p")
                .build();

        PostgreSQLLogicalReplicationCDCConnector connector = new PostgreSQLLogicalReplicationCDCConnector(config);
        connector.running.set(true);
        setField(connector, "replicationStream", null);
        setField(connector, "tableFilter", TableFilter.from(List.of("public.allowed_*"), List.of()));

        invokeParse(connector, "table public.users:\nINSERT: id[integer]:1");

        assertTrue(connector.poll().isEmpty(), "filtered table should not emit events");
    }

    private static void invokeParse(PostgreSQLLogicalReplicationCDCConnector connector, String message) {
        try {
            Method m = PostgreSQLLogicalReplicationCDCConnector.class.getDeclaredMethod("parseLogicalMessage", String.class);
            m.setAccessible(true);
            m.invoke(connector, message);
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
