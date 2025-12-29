package io.github.cuihairu.redis.streaming.starter.annotation;

import io.github.cuihairu.redis.streaming.starter.autoconfigure.RedisStreamingAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EnableRedisStreaming annotation
 */
class EnableRedisStreamingTest {

    @Test
    void testAnnotationHasCorrectTarget() {
        ElementType[] targets = EnableRedisStreaming.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.TYPE, targets[0]);
    }

    @Test
    void testAnnotationHasRuntimeRetention() {
        Retention retention = EnableRedisStreaming.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void testAnnotationIsDocumented() {
        assertNotNull(EnableRedisStreaming.class.getAnnotation(Documented.class));
    }

    @Test
    void testAnnotationImportsAutoConfiguration() {
        Import importAnnotation = EnableRedisStreaming.class.getAnnotation(Import.class);
        assertNotNull(importAnnotation);
        assertEquals(1, importAnnotation.value().length);
        assertEquals(RedisStreamingAutoConfiguration.class, importAnnotation.value()[0]);
    }

    @Test
    void testAnnotationCanBeUsedOnClass() {
        // Simulate annotation usage
        @EnableRedisStreaming
        class TestApplication {
        }

        EnableRedisStreaming annotation = TestApplication.class.getAnnotation(EnableRedisStreaming.class);
        assertNotNull(annotation);
        // Verify default values
        assertTrue(annotation.registry());
        assertTrue(annotation.discovery());
        assertTrue(annotation.config());
    }

    @Test
    void testAnnotationCanBeUsedOnInterface() {
        @EnableRedisStreaming
        interface TestInterface {
        }

        EnableRedisStreaming annotation = TestInterface.class.getAnnotation(EnableRedisStreaming.class);
        assertNotNull(annotation);
    }

    @Test
    void testAnnotationWithCustomValues() {
        @EnableRedisStreaming(
                registry = false,
                discovery = true,
                config = false
        )
        class TestApplication {
        }

        EnableRedisStreaming annotation = TestApplication.class.getAnnotation(EnableRedisStreaming.class);
        assertNotNull(annotation);
        assertFalse(annotation.registry());
        assertTrue(annotation.discovery());
        assertFalse(annotation.config());
    }

    @Test
    void testAnnotationRetentionPolicyAllowsRuntimeAccess() {
        Retention retention = EnableRedisStreaming.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        // This ensures the annotation is available at runtime via reflection
    }

    @Test
    void testAnnotationImportConfigurationIsCorrect() {
        Import importAnnotation = EnableRedisStreaming.class.getAnnotation(Import.class);
        assertNotNull(importAnnotation);
        assertEquals(RedisStreamingAutoConfiguration.class, importAnnotation.value()[0]);
        // This ensures the auto-configuration is properly imported
    }

    @Test
    void testAnnotationWithAllFalse() {
        @EnableRedisStreaming(
                registry = false,
                discovery = false,
                config = false
        )
        class TestApplication {
        }

        EnableRedisStreaming annotation = TestApplication.class.getAnnotation(EnableRedisStreaming.class);
        assertNotNull(annotation);
        assertFalse(annotation.registry());
        assertFalse(annotation.discovery());
        assertFalse(annotation.config());
    }

    @Test
    void testAnnotationWithAllTrue() {
        @EnableRedisStreaming(
                registry = true,
                discovery = true,
                config = true
        )
        class TestApplication {
        }

        EnableRedisStreaming annotation = TestApplication.class.getAnnotation(EnableRedisStreaming.class);
        assertNotNull(annotation);
        assertTrue(annotation.registry());
        assertTrue(annotation.discovery());
        assertTrue(annotation.config());
    }

    @Test
    void testAnnotationMultipleClasses() {
        @EnableRedisStreaming(registry = false)
        class FirstApplication {
        }

        @EnableRedisStreaming(discovery = false)
        class SecondApplication {
        }

        EnableRedisStreaming firstAnnotation = FirstApplication.class.getAnnotation(EnableRedisStreaming.class);
        EnableRedisStreaming secondAnnotation = SecondApplication.class.getAnnotation(EnableRedisStreaming.class);

        assertNotNull(firstAnnotation);
        assertNotNull(secondAnnotation);

        assertFalse(firstAnnotation.registry());
        assertTrue(firstAnnotation.discovery());
        assertTrue(firstAnnotation.config());

        assertTrue(secondAnnotation.registry());
        assertFalse(secondAnnotation.discovery());
        assertTrue(secondAnnotation.config());
    }
}
