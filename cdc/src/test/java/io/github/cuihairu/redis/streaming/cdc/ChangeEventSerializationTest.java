package io.github.cuihairu.redis.streaming.cdc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson serialization/deserialization contract for {@link ChangeEvent}. The event is a
 * Lombok {@code @Data} POJO whose {@code isDataChange()}-style predicates serialize as
 * extra properties with no matching setter, so readers must not fail on unknown properties
 * (the repo convention {@code findAndRegisterModules()} alone is not enough). With that
 * tolerance the JSON round trip must preserve every field — including the {@link Instant}
 * timestamp, picked up from jackson-datatype-jsr310 on the classpath — and tolerate absent
 * optional fields. (The MQ bridge serializes events as payload maps, not Jackson; this pins
 * the direct-JSON interop contract instead.)
 */
class ChangeEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void jacksonRoundTripPreservesAllFields() throws Exception {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", 7);
        before.put("name", "old");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", 7);
        after.put("name", "new");

        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.UPDATE,
                "public",
                "users",
                "7",
                before,
                after);
        event.setPosition("0/16B45D1");
        event.setSource("pg-conn");
        event.setTimestamp(Instant.parse("2026-09-28T03:00:00.123Z"));
        event.setTransactionId("tx-99");
        event.setMetadata(Map.of("binlog-file", "mysql-bin.000009", "row", 2));

        String json = mapper.writeValueAsString(event);
        ChangeEvent back = mapper.readValue(json, ChangeEvent.class);

        assertEquals(event.getEventType(), back.getEventType());
        assertEquals("public", back.getDatabase());
        assertEquals("users", back.getTable());
        assertEquals("7", back.getKey());
        assertEquals(before, back.getBeforeData());
        assertEquals(after, back.getAfterData());
        assertEquals("0/16B45D1", back.getPosition());
        assertEquals("pg-conn", back.getSource());
        assertEquals("tx-99", back.getTransactionId());
        assertEquals(event.getMetadata(), back.getMetadata());
        assertEquals(event.getTimestamp(), back.getTimestamp(),
                "the Instant timestamp must survive the round trip exactly");
        assertEquals(event, back);
    }

    @Test
    void tableDriven_allEventTypeValuesRoundTrip() throws Exception {
        for (ChangeEvent.EventType type : ChangeEvent.EventType.values()) {
            ChangeEvent event = new ChangeEvent();
            event.setEventType(type);

            String json = mapper.writeValueAsString(event);
            ChangeEvent back = mapper.readValue(json, ChangeEvent.class);

            assertEquals(type, back.getEventType(),
                    "event type " + type + " must round trip through JSON");
        }
    }

    @Test
    void nullOptionalFieldsStayNullThroughRoundTrip() throws Exception {
        // no-args constructor: unlike the 6-arg convenience constructor it does NOT stamp
        // a default timestamp, so every field except the type stays genuinely null
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.HEARTBEAT);

        String json = mapper.writeValueAsString(event);
        ChangeEvent back = mapper.readValue(json, ChangeEvent.class);

        assertEquals(ChangeEvent.EventType.HEARTBEAT, back.getEventType());
        assertNull(back.getDatabase());
        assertNull(back.getTable());
        assertNull(back.getKey());
        assertNull(back.getBeforeData());
        assertNull(back.getAfterData());
        assertNull(back.getPosition());
        assertNull(back.getSource());
        assertNull(back.getTimestamp());
    }

    @Test
    void handWrittenJsonWithOnlyCoreFieldsParses() throws Exception {
        String json = "{\"eventType\":\"DELETE\",\"database\":\"db1\",\"table\":\"orders\","
                + "\"key\":\"42\",\"beforeData\":{\"id\":42}}";

        ChangeEvent back = mapper.readValue(json, ChangeEvent.class);

        assertEquals(ChangeEvent.EventType.DELETE, back.getEventType());
        assertEquals("db1", back.getDatabase());
        assertEquals("orders", back.getTable());
        assertEquals("42", back.getKey());
        assertEquals(Map.of("id", 42), back.getBeforeData());
        assertNull(back.getAfterData());
    }

    @Test
    void serializedJsonExposesTheDocumentedWireShape() throws Exception {
        ChangeEvent event = new ChangeEvent(
                ChangeEvent.EventType.INSERT, "db", "t", "k", null, Map.of("id", 1));

        String json = mapper.writeValueAsString(event);

        assertTrue(json.contains("\"eventType\":\"INSERT\""), "enum is written by name: " + json);
        assertTrue(json.contains("\"database\":\"db\""), json);
        assertTrue(json.contains("\"table\":\"t\""), json);
        assertTrue(json.contains("\"afterData\":{\"id\":1}"), json);
    }
}
