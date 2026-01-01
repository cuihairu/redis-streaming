package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceIdentity interface
 */
class ServiceIdentityTest {

    // Test implementation of ServiceIdentity
    private static class TestServiceIdentity implements ServiceIdentity {
        private final String serviceName;
        private final String instanceId;

        TestServiceIdentity(String serviceName, String instanceId) {
            this.serviceName = serviceName;
            this.instanceId = instanceId;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }
    }

    @Test
    void testGetUniqueId() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service", "instance-1");

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertEquals("my-service:instance-1", uniqueId);
    }

    @Test
    void testGetUniqueIdWithNullServiceName() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity(null, "instance-1");

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertNull(uniqueId);
    }

    @Test
    void testGetUniqueIdWithNullInstanceId() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service", null);

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertNull(uniqueId);
    }

    @Test
    void testGetUniqueIdWithBothNull() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity(null, null);

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertNull(uniqueId);
    }

    @Test
    void testGetUniqueIdWithDefaultInstanceId() {
        // Given - using default instanceId (hostname)
        class TestServiceWithDefaultId implements ServiceIdentity {
            @Override
            public String getServiceName() {
                return "test-service";
            }
        }

        ServiceIdentity identity = new TestServiceWithDefaultId();

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertNotNull(uniqueId);
        assertTrue(uniqueId.startsWith("test-service:"));
        assertFalse(uniqueId.equals("test-service:"));
    }

    @Test
    void testIsValidIdentity() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service", "instance-1");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValidIdentityWithNullServiceName() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity(null, "instance-1");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidIdentityWithNullInstanceId() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service", null);

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidIdentityWithEmptyServiceName() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("   ", "instance-1");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidIdentityWithEmptyInstanceId() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service", "  ");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidIdentityWithWhitespaceOnlyValues() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("\t\n", "\t\n");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidIdentityWithValidValues() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("valid-service", "valid-instance");

        // When
        boolean isValid = identity.isValidIdentity();

        // Then
        assertTrue(isValid);
    }

    @Test
    void testGetDefaultInstanceId() {
        // Given - using default getInstanceId() method
        class TestServiceWithDefaultId implements ServiceIdentity {
            @Override
            public String getServiceName() {
                return "test-service";
            }
        }

        ServiceIdentity identity = new TestServiceWithDefaultId();

        // When
        String instanceId = identity.getInstanceId();

        // Then
        assertNotNull(instanceId);
        assertFalse(instanceId.trim().isEmpty());
    }

    @Test
    void testGetUniqueIdFormat() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("service", "123");

        // When
        String uniqueId = identity.getUniqueId();

        // Then - verify format is "serviceName:instanceId"
        assertTrue(uniqueId.matches("^[^:]+:[^:]+$"));
        assertEquals("service:123", uniqueId);
    }

    @Test
    void testGetUniqueIdWithSpecialCharacters() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("my-service.v2", "host:8080");

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertEquals("my-service.v2:host:8080", uniqueId);
    }

    @Test
    void testGetDescriptionDefaultMethod() {
        // Given - interface with default getDescription() method
        Protocol protocol = new Protocol() {
            @Override
            public String getName() {
                return "test-protocol";
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public int getDefaultPort() {
                return 8080;
            }
        };

        // When
        String description = protocol.getDescription();

        // Then - default implementation returns getName()
        assertEquals("test-protocol", description);
    }

    @Test
    void testGetUniqueIdWithColonInServiceName() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("service:name", "instance");

        // When
        String uniqueId = identity.getUniqueId();

        // Then - should preserve colon
        assertEquals("service:name:instance", uniqueId);
    }

    @Test
    void testGetUniqueIdWithColonInInstanceId() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("service", "instance:id");

        // When
        String uniqueId = identity.getUniqueId();

        // Then - should preserve colon
        assertEquals("service:instance:id", uniqueId);
    }

    @Test
    void testMultipleColons() {
        // Given
        ServiceIdentity identity = new TestServiceIdentity("ser:vice", "ins:tance");

        // When
        String uniqueId = identity.getUniqueId();

        // Then
        assertEquals("ser:vice:ins:tance", uniqueId);
    }
}
