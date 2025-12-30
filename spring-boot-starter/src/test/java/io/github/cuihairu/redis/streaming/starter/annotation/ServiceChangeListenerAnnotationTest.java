package io.github.cuihairu.redis.streaming.starter.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for @ServiceChangeListener annotation
 */
class ServiceChangeListenerAnnotationTest {

    // ===== Annotation Type Tests =====

    @Test
    void testAnnotationType() {
        assertTrue(ServiceChangeListener.class.isAnnotation());
        assertTrue(ServiceChangeListener.class.isInterface());
    }

    @Test
    void testAnnotationIsAccessible() throws NoSuchMethodException {
        Method method = SampleUsage.class.getMethod("onChange");
        ServiceChangeListener annotation = method.getAnnotation(ServiceChangeListener.class);

        assertNotNull(annotation);
    }

    // ===== Default Value Tests =====

    @Test
    void testServicesDefaultValue() throws NoSuchMethodException {
        ServiceChangeListener annotation = SampleUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[0], annotation.services());
    }

    @Test
    void testActionsDefaultValue() throws NoSuchMethodException {
        ServiceChangeListener annotation = SampleUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"added", "removed", "updated"}, annotation.actions());
    }

    // ===== Custom Services Tests =====

    @Test
    void testSingleService() throws NoSuchMethodException {
        ServiceChangeListener annotation = SingleServiceUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"user-service"}, annotation.services());
    }

    @Test
    void testMultipleServices() throws NoSuchMethodException {
        ServiceChangeListener annotation = MultipleServicesUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"user-service", "order-service", "payment-service"}, annotation.services());
    }

    // ===== Custom Actions Tests =====

    @Test
    void testSingleAction() throws NoSuchMethodException {
        ServiceChangeListener annotation = SingleActionUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"added"}, annotation.actions());
    }

    @Test
    void testTwoActions() throws NoSuchMethodException {
        ServiceChangeListener annotation = TwoActionsUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"added", "removed"}, annotation.actions());
    }

    @Test
    void testAllActions() throws NoSuchMethodException {
        ServiceChangeListener annotation = AllActionsUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"added", "removed", "updated"}, annotation.actions());
    }

    // ===== Combined Tests =====

    @Test
    void testServicesAndActions() throws NoSuchMethodException {
        ServiceChangeListener annotation = CombinedUsage.class
                .getMethod("onChange")
                .getAnnotation(ServiceChangeListener.class);
        assertArrayEquals(new String[]{"user-service"}, annotation.services());
        assertArrayEquals(new String[]{"added", "removed"}, annotation.actions());
    }

    // ===== Annotation Retention Tests =====

    @Test
    void testAnnotationRetention() {
        java.lang.annotation.Retention retention = ServiceChangeListener.class
                .getAnnotation(java.lang.annotation.Retention.class);
        assertNotNull(retention);
        assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value());
    }

    // ===== Annotation Target Tests =====

    @Test
    void testAnnotationTarget() {
        java.lang.annotation.Target target = ServiceChangeListener.class
                .getAnnotation(java.lang.annotation.Target.class);
        assertNotNull(target);
        assertEquals(java.lang.annotation.ElementType.METHOD, target.value()[0]);
    }

    // ===== Sample usage classes =====

    static class SampleUsage {
        @ServiceChangeListener
        public void onChange() {
        }
    }

    static class SingleServiceUsage {
        @ServiceChangeListener(services = "user-service")
        public void onChange() {
        }
    }

    static class MultipleServicesUsage {
        @ServiceChangeListener(services = {"user-service", "order-service", "payment-service"})
        public void onChange() {
        }
    }

    static class SingleActionUsage {
        @ServiceChangeListener(actions = "added")
        public void onChange() {
        }
    }

    static class TwoActionsUsage {
        @ServiceChangeListener(actions = {"added", "removed"})
        public void onChange() {
        }
    }

    static class AllActionsUsage {
        @ServiceChangeListener(actions = {"added", "removed", "updated"})
        public void onChange() {
        }
    }

    static class CombinedUsage {
        @ServiceChangeListener(services = "user-service", actions = {"added", "removed"})
        public void onChange() {
        }
    }
}
