package io.github.cuihairu.redis.streaming.starter.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceChangeListener annotation
 */
class ServiceChangeListenerTest {

    @Test
    void testAnnotationHasCorrectTarget() {
        ElementType[] targets = ServiceChangeListener.class.getAnnotation(java.lang.annotation.Target.class).value();
        assertEquals(1, targets.length);
        assertEquals(ElementType.METHOD, targets[0]);
    }

    @Test
    void testAnnotationHasRuntimeRetention() {
        Retention retention = ServiceChangeListener.class.getAnnotation(Retention.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
    }

    @Test
    void testAnnotationIsDocumented() {
        assertNotNull(ServiceChangeListener.class.getAnnotation(Documented.class));
    }

    @Test
    void testAnnotationWithDefaultValues() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(0, annotation.services().length);
        assertArrayEquals(new String[]{"added", "removed", "updated"}, annotation.actions());
    }

    @Test
    void testAnnotationWithSingleService() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(services = "my-service")
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(1, annotation.services().length);
        assertEquals("my-service", annotation.services()[0]);
        assertArrayEquals(new String[]{"added", "removed", "updated"}, annotation.actions());
    }

    @Test
    void testAnnotationWithMultipleServices() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(services = {"service1", "service2", "service3"})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(3, annotation.services().length);
        assertEquals("service1", annotation.services()[0]);
        assertEquals("service2", annotation.services()[1]);
        assertEquals("service3", annotation.services()[2]);
    }

    @Test
    void testAnnotationWithSingleAction() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(actions = "added")
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(1, annotation.actions().length);
        assertEquals("added", annotation.actions()[0]);
    }

    @Test
    void testAnnotationWithMultipleActions() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(actions = {"added", "removed"})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(2, annotation.actions().length);
        assertEquals("added", annotation.actions()[0]);
        assertEquals("removed", annotation.actions()[1]);
    }

    @Test
    void testAnnotationWithServicesAndActions() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(
                    services = {"service-a", "service-b"},
                    actions = {"updated", "removed"}
            )
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(2, annotation.services().length);
        assertEquals("service-a", annotation.services()[0]);
        assertEquals("service-b", annotation.services()[1]);
        assertEquals(2, annotation.actions().length);
        assertEquals("updated", annotation.actions()[0]);
        assertEquals("removed", annotation.actions()[1]);
    }

    @Test
    void testAnnotationWithEmptyServices() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(services = {})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(0, annotation.services().length);
    }

    @Test
    void testAnnotationWithEmptyActions() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(actions = {})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(0, annotation.actions().length);
    }

    @Test
    void testAnnotationWithAllDefaultActions() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(3, annotation.actions().length);
        assertEquals("added", annotation.actions()[0]);
        assertEquals("removed", annotation.actions()[1]);
        assertEquals("updated", annotation.actions()[2]);
    }

    @Test
    void testAnnotationWithServicesOnly() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(services = {"important-service"})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(1, annotation.services().length);
        assertEquals("important-service", annotation.services()[0]);
        // actions should have default value
        assertEquals(3, annotation.actions().length);
    }

    @Test
    void testAnnotationWithActionsOnly() throws NoSuchMethodException {
        class TestClass {
            @ServiceChangeListener(actions = {"added"})
            public void onServiceChange() {}
        }

        ServiceChangeListener annotation = TestClass.class
                .getMethod("onServiceChange")
                .getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
        assertEquals(0, annotation.services().length);
        assertEquals(1, annotation.actions().length);
        assertEquals("added", annotation.actions()[0]);
    }
}
