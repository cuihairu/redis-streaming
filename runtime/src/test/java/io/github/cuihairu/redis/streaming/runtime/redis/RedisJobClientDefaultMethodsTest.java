package io.github.cuihairu.redis.streaming.runtime.redis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the default method bodies of {@link RedisJobClient} by invoking them on a minimal
 * implementation that does not override them.
 */
class RedisJobClientDefaultMethodsTest {

    @Test
    void defaultMethodsBehaveAsDocumented() {
        AtomicBoolean canceled = new AtomicBoolean();
        RedisJobClient client = new RedisJobClient() {
            @Override
            public void cancel() {
                canceled.set(true);
            }

            @Override
            public boolean awaitTermination(Duration timeout) {
                return true;
            }
        };

        assertNull(client.triggerCheckpointNow());
        assertNull(client.getLatestCheckpoint());
        assertDoesNotThrow(client::pause);
        assertDoesNotThrow(client::resume);
        assertEquals(-1L, client.inFlight());
        assertTrue(client.diagnostics().isEmpty());

        client.close();
        assertTrue(canceled.get(), "close() must delegate to cancel()");
    }
}
