package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: DLQ entry maps fed to Redisson XADD must never contain null values.
 */
class DeadLetterCodecNullValueRegressionTest {

    private static void assertNoNullValues(Map<String, Object> entry) {
        entry.forEach((k, v) -> assertNotNull(v, "null value for field " + k));
    }

    @Test
    void buildEntryWithNullPayloadHasNoNullValues() {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = "t";
        r.payload = null;

        Map<String, Object> entry = DeadLetterCodec.buildEntry(r);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
    }

    @Test
    void buildPartitionEntryFromDlqWithMissingPayloadHasNoNullValues() {
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("originalTopic", "t");
        // payload intentionally absent (e.g. missing-payload DLQ entry)

        Map<String, Object> entry = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "t", 0);

        assertNoNullValues(entry);
        assertFalse(entry.containsKey("payload"));
    }
}
