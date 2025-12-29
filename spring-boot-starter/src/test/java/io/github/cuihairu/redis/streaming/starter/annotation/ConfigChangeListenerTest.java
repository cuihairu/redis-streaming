package io.github.cuihairu.redis.streaming.starter.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigChangeListener annotation
 */
class ConfigChangeListenerTest {

    @Test
    void testAnnotationHasCorrectTarget() {
        ElementType[] targets = ConfigChangeListener.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.METHOD, targets[0]);
    }

    @Test
    void testAnnotationHasRuntimeRetention() {
        Retention retention = ConfigChangeListener.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void testAnnotationIsDocumented() {
        assertNotNull(ConfigChangeListener.class.getAnnotation(Documented.class));
    }

    @Test
    void testAnnotationWithDataId() throws NoSuchMethodException {
        // Create a test class with the annotation
        class TestClass {
            @ConfigChangeListener(dataId = "test-config")
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("test-config", annotation.dataId());
        assertEquals("DEFAULT_GROUP", annotation.group());
        assertTrue(annotation.autoRefresh());
    }

    @Test
    void testAnnotationWithAllAttributes() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(
                    dataId = "my-config",
                    group = "CUSTOM_GROUP",
                    autoRefresh = false
            )
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("my-config", annotation.dataId());
        assertEquals("CUSTOM_GROUP", annotation.group());
        assertFalse(annotation.autoRefresh());
    }

    @Test
    void testAnnotationWithEmptyGroup() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(
                    dataId = "test",
                    group = ""
            )
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("", annotation.group());
    }

    @Test
    void testAnnotationAutoRefreshTrue() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(
                    dataId = "test",
                    autoRefresh = true
            )
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertTrue(annotation.autoRefresh());
    }

    @Test
    void testAnnotationAutoRefreshFalse() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(
                    dataId = "test",
                    autoRefresh = false
            )
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertFalse(annotation.autoRefresh());
    }

    @Test
    void testAnnotationWithSpecialCharactersInDataId() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(dataId = "config:with:colons")
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("config:with:colons", annotation.dataId());
    }

    @Test
    void testAnnotationWithDefaultGroup() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(dataId = "test")
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("DEFAULT_GROUP", annotation.group());
    }

    @Test
    void testAnnotationWithCustomGroup() throws NoSuchMethodException {
        class TestClass {
            @ConfigChangeListener(
                    dataId = "test",
                    group = "my-custom-group"
            )
            public void onConfigChange(String config) {}
        }

        ConfigChangeListener annotation = TestClass.class
                .getMethod("onConfigChange", String.class)
                .getAnnotation(ConfigChangeListener.class);

        assertNotNull(annotation);
        assertEquals("my-custom-group", annotation.group());
    }
}
