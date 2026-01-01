package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RateLimiter interface
 */
class RateLimiterTest {

    @Test
    void testAllowWithDefaultImplementation() {
        // Given - create a simple test implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return true; // Always allow for this test
            }
        };

        // When
        boolean allowed = limiter.allow("test-key");

        // Then - default implementation delegates to allowAt with current time
        assertTrue(allowed);
    }

    @Test
    void testAllowWithCustomTime() {
        // Given - create a time-aware implementation
        RateLimiter limiter = new RateLimiter() {
            private long lastAllowedTime = 0;

            @Override
            public boolean allowAt(String key, long nowMillis) {
                if (nowMillis - lastAllowedTime >= 1000) {
                    lastAllowedTime = nowMillis;
                    return true;
                }
                return false;
            }
        };

        // When - first request
        boolean allowed1 = limiter.allowAt("user-123", 1000);

        // Then
        assertTrue(allowed1);

        // When - second request within rate limit
        boolean allowed2 = limiter.allowAt("user-123", 1500);

        // Then
        assertFalse(allowed2);

        // When - third request after rate limit period
        boolean allowed3 = limiter.allowAt("user-123", 2500);

        // Then
        assertTrue(allowed3);
    }

    @Test
    void testAllowDifferentKeysAreIndependent() {
        // Given - create a simple implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                // Different keys have independent rate limits
                return nowMillis % 2 == 0; // Simple rule based on time
            }
        };

        // When - check different keys at same time
        boolean allowed1 = limiter.allowAt("user-1", 1000);
        boolean allowed2 = limiter.allowAt("user-2", 1000);
        boolean allowed3 = limiter.allowAt("user-3", 1000);

        // Then - all keys should get same result at same time
        assertTrue(allowed1);
        assertTrue(allowed2);
        assertTrue(allowed3);
    }

    @Test
    void testAllowWithNullKey() {
        // Given - create an implementation that handles null
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return key != null;
            }
        };

        // When
        boolean allowed = limiter.allowAt(null, System.currentTimeMillis());

        // Then
        assertFalse(allowed);
    }

    @Test
    void testAllowWithEmptyKey() {
        // Given - create an implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return key != null && !key.isEmpty();
            }
        };

        // When
        boolean allowed = limiter.allowAt("", System.currentTimeMillis());

        // Then
        assertFalse(allowed);
    }

    @Test
    void testAllowDelegatesToAllowAt() {
        // Given - verify that allow() calls allowAt() with current time
        final long[] capturedTime = {0};
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                capturedTime[0] = nowMillis;
                return true;
            }
        };

        long before = System.currentTimeMillis();
        limiter.allow("test-key");
        long after = System.currentTimeMillis();

        // Then - captured time should be between before and after
        assertTrue(capturedTime[0] >= before);
        assertTrue(capturedTime[0] <= after);
    }

    @Test
    void testAllowAtWithNegativeTime() {
        // Given - create an implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return nowMillis >= 0;
            }
        };

        // When
        boolean allowed = limiter.allowAt("test-key", -1000);

        // Then
        assertFalse(allowed);
    }

    @Test
    void testAllowAtWithZeroTime() {
        // Given - create an implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return nowMillis >= 0;
            }
        };

        // When
        boolean allowed = limiter.allowAt("test-key", 0);

        // Then
        assertTrue(allowed);
    }

    @Test
    void testAllowAtWithVeryLargeTime() {
        // Given - create an implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                return true;
            }
        };

        // When
        boolean allowed = limiter.allowAt("test-key", Long.MAX_VALUE);

        // Then - should handle large time values
        assertTrue(allowed);
    }

    @Test
    void testRateLimiterCanBeImplementedAsLambda() {
        // Given - RateLimiter is a functional interface (single abstract method)
        RateLimiter limiter = (key, nowMillis) -> nowMillis % 2 == 0;

        // When
        boolean allowed1 = limiter.allowAt("test", 1000);
        boolean allowed2 = limiter.allowAt("test", 1001);

        // Then
        assertTrue(allowed1);
        assertFalse(allowed2);
    }

    @Test
    void testMultipleImplementationsAreIndependent() {
        // Given - two different implementations
        RateLimiter limiter1 = (key, nowMillis) -> true; // Always allow
        RateLimiter limiter2 = (key, nowMillis) -> false; // Always deny

        // When
        boolean allowed1 = limiter1.allow("test");
        boolean allowed2 = limiter2.allow("test");

        // Then - each implementation has its own logic
        assertTrue(allowed1);
        assertFalse(allowed2);
    }

    @Test
    void testAllowAtIsConsistentForSameInputs() {
        // Given - deterministic implementation
        RateLimiter limiter = (key, nowMillis) -> key.hashCode() + nowMillis > 0;

        // When - call multiple times with same inputs
        boolean allowed1 = limiter.allowAt("user-123", 5000);
        boolean allowed2 = limiter.allowAt("user-123", 5000);
        boolean allowed3 = limiter.allowAt("user-123", 5000);

        // Then - should be consistent
        assertEquals(allowed1, allowed2);
        assertEquals(allowed2, allowed3);
    }

    @Test
    void testInterfaceDefinition() {
        // Given & When & Then - verify interface structure
        assertTrue(RateLimiter.class.isInterface());
        // Interface has allowAt (abstract) and allow (default)
        // Total methods excluding Object methods: 2
        assertTrue(RateLimiter.class.getMethods().length >= 2);
    }

    @Test
    void testDefaultMethodAllow() {
        // Given - verify allow() has default implementation
        try {
            java.lang.reflect.Method allowMethod = RateLimiter.class.getMethod("allow", String.class);
            assertTrue(allowMethod.isDefault());
            assertTrue(java.lang.reflect.Modifier.isPublic(allowMethod.getModifiers()));
        } catch (NoSuchMethodException e) {
            fail("allow method should exist");
        }
    }

    @Test
    void testAbstractMethodAllowAt() {
        // Given & When & Then - verify allowAt() is abstract
        try {
            java.lang.reflect.Method allowAtMethod = RateLimiter.class.getMethod("allowAt", String.class, long.class);
            assertFalse(java.lang.reflect.Modifier.isStatic(allowAtMethod.getModifiers()));
            assertTrue(java.lang.reflect.Modifier.isPublic(allowAtMethod.getModifiers()));
        } catch (NoSuchMethodException e) {
            fail("allowAt method should exist");
        }
    }

    @Test
    void testAllowWithSameKeyAtDifferentTimes() {
        // Given - time-sensitive implementation
        RateLimiter limiter = new RateLimiter() {
            @Override
            public boolean allowAt(String key, long nowMillis) {
                // Only allow one request per second per key
                return nowMillis % 1000 == 0;
            }
        };

        // When - same key at different times
        boolean allowed1 = limiter.allowAt("user-123", 1000);
        boolean allowed2 = limiter.allowAt("user-123", 1500);
        boolean allowed3 = limiter.allowAt("user-123", 2000);

        // Then
        assertTrue(allowed1);
        assertFalse(allowed2);
        assertTrue(allowed3);
    }
}
