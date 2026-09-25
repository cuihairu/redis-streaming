package io.github.cuihairu.redis.streaming.core.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two remaining {@code SystemUtils#getLocalHostname} branches:
 * the inner double-checked-lock cache hit (requires a concurrent caller that
 * enters the synchronized block after the cache has been populated) and the
 * {@link UnknownHostException} failure path.
 */
class SystemUtilsCacheRaceTest {

    @AfterEach
    void resetCache() {
        SystemUtils.clearHostnameCache();
    }

    @Test
    void innerCacheHitReturnsValuePublishedByConcurrentCaller() throws Exception {
        SystemUtils.clearHostnameCache();

        Field lockField = SystemUtils.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        Object lock = lockField.get(null);

        Field cacheField = SystemUtils.class.getDeclaredField("cachedHostname");
        cacheField.setAccessible(true);

        AtomicReference<String> observed = new AtomicReference<>();
        Thread caller;
        synchronized (lock) {
            caller = new Thread(() -> observed.set(SystemUtils.getLocalHostname()));
            caller.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (caller.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, caller.getState(),
                    "caller must be parked on the cache lock before the cache is populated");
            cacheField.set(null, "race-host");
        }
        caller.join(5_000);
        assertEquals("race-host", observed.get(),
                "the waiting caller must serve the value cached by the winner of the race");
    }

    @Test
    void unknownHostFailureIsWrappedInRuntimeException() {
        SystemUtils.clearHostnameCache();
        try (MockedStatic<InetAddress> inet = Mockito.mockStatic(InetAddress.class)) {
            inet.when(InetAddress::getLocalHost).thenThrow(new UnknownHostException("no-host"));
            RuntimeException ex = assertThrows(RuntimeException.class, SystemUtils::getLocalHostname);
            assertTrue(ex.getMessage().contains("Failed to get local hostname"));
            assertInstanceOf(UnknownHostException.class, ex.getCause());
        }
    }
}
