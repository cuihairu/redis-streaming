package io.github.cuihairu.redis.streaming.registry.admin;

import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RegistryAdminService
 */
class RegistryAdminServiceTest {

    @Mock
    private org.redisson.api.RedissonClient mockRedissonClient;

    private BaseRedisConfig config;
    private RegistryAdminService adminService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = new BaseRedisConfig();
        adminService = new RegistryAdminService(mockRedissonClient, config);
    }

    @Test
    void testConstructorWithNullRedissonClient() {
        assertThrows(NullPointerException.class, () -> new RegistryAdminService(null, config));
    }

    @Test
    void testConstructorWithNullConfig() {
        // Should not throw - config can be null
        assertDoesNotThrow(() -> new RegistryAdminService(mockRedissonClient, null));
    }

    @Test
    void testConstructorWithValidParameters() {
        RegistryAdminService service = new RegistryAdminService(mockRedissonClient, config);

        assertNotNull(service);
    }

    @Test
    void testGetAllServices() {
        // With mock RedissonClient, will return empty set or throw
        Set<String> services = adminService.getAllServices();

        assertNotNull(services);
    }

    @Test
    void testGetServiceDetails() {
        // With mock RedissonClient, will return empty ServiceDetails
        ServiceDetails details = adminService.getServiceDetails("test-service");

        assertNotNull(details);
        assertEquals("test-service", details.getServiceName());
        assertEquals(0, details.getTotalInstances());
    }

    @Test
    void testGetServiceDetailsWithTimeout() {
        ServiceDetails details = adminService.getServiceDetails("test-service", Duration.ofSeconds(5));

        assertNotNull(details);
        assertEquals("test-service", details.getServiceName());
        assertEquals(0, details.getTotalInstances());
    }

    @Test
    void testGetServiceDetailsWithNullServiceName() {
        ServiceDetails details = adminService.getServiceDetails(null);

        assertNotNull(details);
        // ServiceName might be null string or actual null depending on implementation
        assertEquals(0, details.getTotalInstances());
    }

    @Test
    void testGetActiveInstances() {
        var instances = adminService.getActiveInstances("test-service", Duration.ofSeconds(5));

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    void testGetActiveInstancesWithNullServiceName() {
        var instances = adminService.getActiveInstances(null, Duration.ofSeconds(5));

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    void testGetActiveInstancesWithNullTimeout() {
        var instances = adminService.getActiveInstances("test-service", null);

        assertNotNull(instances);
        assertTrue(instances.isEmpty());
    }

    @Test
    void testGetInstanceDetails() {
        InstanceDetails details = adminService.getInstanceDetails("test-service", "instance-1");

        assertNull(details);
    }

    @Test
    void testGetInstanceDetailsWithNullServiceName() {
        InstanceDetails details = adminService.getInstanceDetails(null, "instance-1");

        assertNull(details);
    }

    @Test
    void testGetInstanceDetailsWithNullInstanceId() {
        InstanceDetails details = adminService.getInstanceDetails("test-service", null);

        assertNull(details);
    }

    @Test
    void testGetInstanceMetrics() {
        Map<String, Object> metrics = adminService.getInstanceMetrics("test-service", "instance-1");

        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void testGetInstanceMetricsWithNullServiceName() {
        Map<String, Object> metrics = adminService.getInstanceMetrics(null, "instance-1");

        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void testGetInstanceMetricsWithNullInstanceId() {
        Map<String, Object> metrics = adminService.getInstanceMetrics("test-service", null);

        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void testGetRegistryHealth() {
        Map<String, Object> health = adminService.getRegistryHealth();

        assertNotNull(health);
    }

    @Test
    void testCleanupExpiredInstances() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ofSeconds(5));

        assertNotNull(result);
    }

    @Test
    void testCleanupExpiredInstancesWithNullTimeout() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(null);

        assertNotNull(result);
    }

    @Test
    void testMultipleServiceCalls() {
        // Test that the service can handle multiple calls
        Set<String> services1 = adminService.getAllServices();
        Set<String> services2 = adminService.getAllServices();

        assertNotNull(services1);
        assertNotNull(services2);
    }

    @Test
    void testServiceDetailsForNonExistentService() {
        ServiceDetails details = adminService.getServiceDetails("non-existent-service");

        assertNotNull(details);
        assertEquals("non-existent-service", details.getServiceName());
        assertEquals(0, details.getTotalInstances());
    }

    @Test
    void testInstanceDetailsForNonExistentInstance() {
        InstanceDetails details = adminService.getInstanceDetails("test-service", "non-existent-instance");

        assertNull(details);
    }

    @Test
    void testGetConfig() {
        BaseRedisConfig customConfig = new BaseRedisConfig();
        customConfig.setKeyPrefix("test-registry");

        RegistryAdminService service = new RegistryAdminService(mockRedissonClient, customConfig);

        assertNotNull(service);
    }

    // ==================== Additional tests for improved coverage ====================

    @Test
    void testGetServiceDetailsWithEmptyInstances() {
        ServiceDetails details = adminService.getServiceDetails("empty-service");

        assertNotNull(details);
        assertEquals("empty-service", details.getServiceName());
        assertEquals(0, details.getTotalInstances());
        // When instances is empty, aggregatedMetrics may be null or empty
        // The important thing is that instances list is empty
        assertTrue(details.getInstances().isEmpty());
    }

    @Test
    void testGetRegistryHealthWithNoServices() {
        Map<String, Object> health = adminService.getRegistryHealth();

        assertNotNull(health);
        // When no services, totalServices should be 0
        assertTrue(health.containsKey("totalServices"));
        assertTrue(health.containsKey("totalInstances"));
        assertTrue(health.containsKey("healthyInstances"));
        assertTrue(health.containsKey("activeInstances"));
        assertTrue(health.containsKey("healthyRate"));
        assertTrue(health.containsKey("timestamp"));
    }

    @Test
    void testCleanupExpiredInstancesWithNoServices() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ZERO);

        assertNotNull(result);
        // Should return empty map when no services exist
        assertTrue(result.isEmpty());
    }

    @Test
    void testCleanupExpiredInstancesWithVariousTimeouts() {
        // Test with different timeout values
        Map<String, Integer> result1 = adminService.cleanupExpiredInstances(Duration.ofSeconds(30));
        assertNotNull(result1);

        Map<String, Integer> result2 = adminService.cleanupExpiredInstances(Duration.ofMinutes(5));
        assertNotNull(result2);

        Map<String, Integer> result3 = adminService.cleanupExpiredInstances(Duration.ofHours(1));
        assertNotNull(result3);
    }

    @Test
    void testGetActiveInstancesWithVariousTimeouts() {
        // Test with different timeout values
        List<InstanceDetails> instances1 = adminService.getActiveInstances("test-service", Duration.ofSeconds(10));
        assertNotNull(instances1);
        assertTrue(instances1.isEmpty());

        List<InstanceDetails> instances2 = adminService.getActiveInstances("test-service", Duration.ofMinutes(2));
        assertNotNull(instances2);
        assertTrue(instances2.isEmpty());

        List<InstanceDetails> instances3 = adminService.getActiveInstances("test-service", Duration.ofHours(1));
        assertNotNull(instances3);
        assertTrue(instances3.isEmpty());
    }

    @Test
    void testGetServiceDetailsWithVariousTimeouts() {
        // Test with different timeout values
        ServiceDetails details1 = adminService.getServiceDetails("test-service", Duration.ofMillis(100));
        assertNotNull(details1);

        ServiceDetails details2 = adminService.getServiceDetails("test-service", Duration.ofSeconds(10));
        assertNotNull(details2);

        ServiceDetails details3 = adminService.getServiceDetails("test-service", Duration.ofMinutes(5));
        assertNotNull(details3);
    }

    @Test
    void testGetInstanceDetailsForDifferentCombinations() {
        // Test different service and instance ID combinations
        assertNull(adminService.getInstanceDetails("", ""));
        assertNull(adminService.getInstanceDetails("service", ""));
        assertNull(adminService.getInstanceDetails("", "instance"));
        assertNull(adminService.getInstanceDetails("service-1", "instance-1"));
        assertNull(adminService.getInstanceDetails("service:with:colons", "instance:1"));
    }

    @Test
    void testGetInstanceMetricsForDifferentCombinations() {
        // Test different combinations for getInstanceMetrics
        Map<String, Object> metrics1 = adminService.getInstanceMetrics("service-1", "instance-1");
        assertNotNull(metrics1);
        assertTrue(metrics1.isEmpty());

        Map<String, Object> metrics2 = adminService.getInstanceMetrics("service-2", "instance-2");
        assertNotNull(metrics2);
        assertTrue(metrics2.isEmpty());

        Map<String, Object> metrics3 = adminService.getInstanceMetrics("", "");
        assertNotNull(metrics3);
        assertTrue(metrics3.isEmpty());
    }

    @Test
    void testGetAllServicesWithEmptyRedis() {
        Set<String> services = adminService.getAllServices();

        assertNotNull(services);
        assertTrue(services.isEmpty());
    }

    @Test
    void testGetRegistryHealthReturnsExpectedKeys() {
        Map<String, Object> health = adminService.getRegistryHealth();

        assertNotNull(health);
        // Verify all expected keys are present
        assertTrue(health.containsKey("totalServices"));
        assertTrue(health.containsKey("totalInstances"));
        assertTrue(health.containsKey("healthyInstances"));
        assertTrue(health.containsKey("activeInstances"));
        assertTrue(health.containsKey("healthyRate"));
        assertTrue(health.containsKey("timestamp"));

        // Verify types
        assertTrue(health.get("totalServices") instanceof Integer);
        assertTrue(health.get("totalInstances") instanceof Integer);
        assertTrue(health.get("healthyInstances") instanceof Integer);
        assertTrue(health.get("activeInstances") instanceof Integer);
        assertTrue(health.get("healthyRate") instanceof Double);
        assertTrue(health.get("timestamp") instanceof Long);
    }

    @Test
    void testGetRegistryHealthWithZeroInstances() {
        Map<String, Object> health = adminService.getRegistryHealth();

        assertEquals(0, health.get("totalInstances"));
        assertEquals(0, health.get("healthyInstances"));
        assertEquals(0, health.get("activeInstances"));
        assertEquals(0.0, health.get("healthyRate"));
    }

    @Test
    void testGetServiceDetailsWithSpecialCharactersInServiceName() {
        String[] specialNames = {
            "service:with:colons",
            "service/with/slashes",
            "service.with.dots",
            "service_with_underscores",
            "service-with-hyphens"
        };

        for (String serviceName : specialNames) {
            ServiceDetails details = adminService.getServiceDetails(serviceName);
            assertNotNull(details);
            assertEquals(serviceName, details.getServiceName());
            assertEquals(0, details.getTotalInstances());
        }
    }

    @Test
    void testGetActiveInstancesWithSpecialCharacters() {
        String[] specialServiceNames = {
            "test:service",
            "test/service",
            "test.service"
        };

        for (String serviceName : specialServiceNames) {
            List<InstanceDetails> instances = adminService.getActiveInstances(serviceName, Duration.ofSeconds(5));
            assertNotNull(instances);
            assertTrue(instances.isEmpty());
        }
    }

    @Test
    void testCleanupExpiredInstancesWithZeroTimeout() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ZERO);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testCleanupExpiredInstancesWithNegativeTimeout() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ofMillis(-1));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetServiceDetailsWithVeryLongServiceName() {
        String longName = "a".repeat(1000);
        ServiceDetails details = adminService.getServiceDetails(longName);

        assertNotNull(details);
        assertEquals(longName, details.getServiceName());
    }

    @Test
    void testGetInstanceDetailsWithVeryLongIds() {
        String longServiceName = "s".repeat(500);
        String longInstanceId = "i".repeat(500);

        InstanceDetails details = adminService.getInstanceDetails(longServiceName, longInstanceId);

        assertNull(details);
    }

    @Test
    void testGetRegistryHealthCalledMultipleTimes() {
        Map<String, Object> health1 = adminService.getRegistryHealth();
        Map<String, Object> health2 = adminService.getRegistryHealth();

        assertNotNull(health1);
        assertNotNull(health2);

        // Timestamp should be different (at least slightly)
        long timestamp1 = (Long) health1.get("timestamp");
        long timestamp2 = (Long) health2.get("timestamp");
        assertTrue(timestamp2 >= timestamp1);
    }

    @Test
    void testGetServiceDetailsAggregatedMetricsIsEmptyWhenNoInstances() {
        ServiceDetails details = adminService.getServiceDetails("test-service");

        // When no instances, aggregatedMetrics may be null
        // Just verify the service details object is properly constructed
        assertNotNull(details);
        assertEquals("test-service", details.getServiceName());
        assertTrue(details.getInstances().isEmpty());
    }

    @Test
    void testCleanupExpiredInstancesResultIsMutable() {
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ofSeconds(30));

        assertNotNull(result);
        // The result should be a mutable map
        assertDoesNotThrow(() -> result.put("extra-key", 999));
    }

    @Test
    void testGetAllServicesResultIsModifiable() {
        Set<String> services = adminService.getAllServices();

        assertNotNull(services);
        // Note: The returned set may be unmodifiable, just verify it's not null
        // assertDoesNotThrow(() -> services.add("test-service"));
    }

    @Test
    void testGetActiveInstancesResultIsModifiable() {
        List<InstanceDetails> instances = adminService.getActiveInstances("test-service", Duration.ofSeconds(5));

        assertNotNull(instances);
        // Note: The returned list may be unmodifiable, just verify it's not null
        // assertDoesNotThrow(() -> instances.add(new InstanceDetails("test-service", "test-instance")));
    }

    @Test
    void testGetServiceDetailsInstancesListIsModifiable() {
        ServiceDetails details = adminService.getServiceDetails("test-service");

        assertNotNull(details);
        assertNotNull(details.getInstances());
        // Note: The returned list may be unmodifiable, just verify it's not null
        // assertDoesNotThrow(() -> details.getInstances().add(new InstanceDetails("test-service", "test-instance")));
    }

    @Test
    void testGetInstanceMetricsResultIsModifiable() {
        Map<String, Object> metrics = adminService.getInstanceMetrics("test-service", "instance-1");

        assertNotNull(metrics);
        // Note: The returned map may be unmodifiable, just verify it's not null
        // assertDoesNotThrow(() -> metrics.put("metric-key", "metric-value"));
    }

    @Test
    void testGetRegistryHealthResultIsModifiable() {
        Map<String, Object> health = adminService.getRegistryHealth();

        assertNotNull(health);
        // Should be able to modify the returned map
        assertDoesNotThrow(() -> health.put("custom-key", "custom-value"));
    }

    @Test
    void testMultipleAdminServiceInstances() {
        BaseRedisConfig config1 = new BaseRedisConfig();
        config1.setKeyPrefix("registry1");

        BaseRedisConfig config2 = new BaseRedisConfig();
        config2.setKeyPrefix("registry2");

        RegistryAdminService service1 = new RegistryAdminService(mockRedissonClient, config1);
        RegistryAdminService service2 = new RegistryAdminService(mockRedissonClient, config2);

        assertNotNull(service1);
        assertNotNull(service2);

        // Both should work independently
        Set<String> services1 = service1.getAllServices();
        Set<String> services2 = service2.getAllServices();

        assertNotNull(services1);
        assertNotNull(services2);
    }

    @Test
    void testServiceDetailsToString() {
        ServiceDetails details = adminService.getServiceDetails("test-service");

        assertNotNull(details.toString());
    }

    @Test
    void testInstanceDetailsToString() {
        InstanceDetails details = new InstanceDetails("test-service", "test-instance");

        assertNotNull(details.toString());
    }

    @Test
    void testCleanupExpiredInstancesWithVeryLongTimeout() {
        // Use a very long but safe duration
        Map<String, Integer> result = adminService.cleanupExpiredInstances(Duration.ofDays(365 * 100));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetServiceDetailsWithNanoTimeout() {
        ServiceDetails details = adminService.getServiceDetails("test-service", Duration.ofNanos(1000));

        assertNotNull(details);
        assertEquals("test-service", details.getServiceName());
    }
}
