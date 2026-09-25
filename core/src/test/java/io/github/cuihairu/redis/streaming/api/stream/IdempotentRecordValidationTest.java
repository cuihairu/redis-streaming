package io.github.cuihairu.redis.streaming.api.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the validation branches of {@link IdempotentRecord}'s compact constructor:
 * blank ids must be rejected and both components are mandatory.
 */
class IdempotentRecordValidationTest {

    @Test
    void blankIdIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new IdempotentRecord<>("   ", "payload"));
        assertEquals("id must not be blank", ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new IdempotentRecord<>("", "payload"));
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new IdempotentRecord<>(null, "payload"));
        assertThrows(NullPointerException.class, () -> new IdempotentRecord<>("id-1", null));
    }

    @Test
    void validRecordExposesComponents() {
        IdempotentRecord<String> record = new IdempotentRecord<>("id-1", "payload");
        assertEquals("id-1", record.id());
        assertEquals("payload", record.value());
    }
}
