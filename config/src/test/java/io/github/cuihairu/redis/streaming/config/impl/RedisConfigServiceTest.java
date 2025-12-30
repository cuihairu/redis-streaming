package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RedisConfigService
 */
class RedisConfigServiceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testConstructorWithRedissonClientOnly() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithConfig() {
        ConfigServiceConfig config = new ConfigServiceConfig();
        ConfigService service = new RedisConfigService(mockRedissonClient, config);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testConstructorWithNullConfig() {
        ConfigService service = new RedisConfigService(mockRedissonClient, null);

        assertNotNull(service);
        assertFalse(service.isRunning());
    }

    @Test
    void testStart() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertFalse(service.isRunning());
        service.start();
        assertTrue(service.isRunning());
    }

    @Test
    void testStop() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        assertTrue(service.isRunning());
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testStartIsIdempotent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        service.start();
        assertTrue(service.isRunning());

        service.start(); // Should not cause issues
        assertTrue(service.isRunning());
    }

    @Test
    void testStopIsIdempotent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        service.stop();
        assertFalse(service.isRunning());

        service.stop(); // Should not cause issues
        assertFalse(service.isRunning());
    }

    @Test
    void testStopWhenNotRunning() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertDoesNotThrow(() -> service.stop());
        assertFalse(service.isRunning());
    }

    @Test
    void testGetConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () -> service.getConfig("test-data-id", "test-group"));
    }

    @Test
    void testPublishConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.publishConfig("test-data-id", "test-group", "test-content")
        );
    }

    @Test
    void testPublishConfigWithDescriptionWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.publishConfig("test-data-id", "test-group", "test-content", "test-description")
        );
    }

    @Test
    void testRemoveConfigWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.removeConfig("test-data-id", "test-group")
        );
    }

    @Test
    void testAddListenerWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertThrows(IllegalStateException.class, () ->
                service.addListener("test-data-id", "test-group", listener)
        );
    }

    @Test
    void testRemoveListenerWhenNotRunning() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertDoesNotThrow(() ->
                service.removeListener("test-data-id", "test-group", listener)
        );
    }

    @Test
    void testGetConfigHistoryWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.getConfigHistory("test-data-id", "test-group", 10)
        );
    }

    @Test
    void testTrimHistoryBySizeWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.trimHistoryBySize("test-data-id", "test-group", 100)
        );
    }

    @Test
    void testTrimHistoryByAgeWhenNotRunningThrowsException() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        assertThrows(IllegalStateException.class, () ->
                service.trimHistoryByAge("test-data-id", "test-group", Duration.ofHours(1))
        );
    }

    @Test
    void testLifecycleTransitions() {
        ConfigService service = new RedisConfigService(mockRedissonClient);

        // Initial state
        assertFalse(service.isRunning());

        // Start
        service.start();
        assertTrue(service.isRunning());

        // Stop
        service.stop();
        assertFalse(service.isRunning());

        // Restart
        service.start();
        assertTrue(service.isRunning());

        // Final stop
        service.stop();
        assertFalse(service.isRunning());
    }

    @Test
    void testGetConfigHistoryWithZeroSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", 0);

        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    void testGetConfigHistoryWithNegativeSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", -10);

        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    void testGetConfigHistoryWithLargeSize() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        List<?> history = service.getConfigHistory("test-data-id", "test-group", 10000);

        assertNotNull(history);
    }

    @Test
    void testTrimHistoryBySizeWithNegativeMax() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryBySize("test-data-id", "test-group", -1);

        // Negative size should delete all history
        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryBySizeWithZeroMax() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryBySize("test-data-id", "test-group", 0);

        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryByAgeWithNullDuration() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryByAge("test-data-id", "test-group", null);

        assertTrue(removed >= 0);
    }

    @Test
    void testTrimHistoryByAgeWithZeroDuration() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        int removed = service.trimHistoryByAge("test-data-id", "test-group", Duration.ZERO);

        assertTrue(removed >= 0);
    }

    @Test
    void testRemoveListenerNonExistent() {
        ConfigService service = new RedisConfigService(mockRedissonClient);
        service.start();

        ConfigChangeListener listener = (dataId, group, content, version) -> {};
        assertDoesNotThrow(() ->
                service.removeListener("test-data-id", "test-group", listener)
        );
    }
}
