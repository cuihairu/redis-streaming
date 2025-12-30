package io.github.cuihairu.redis.streaming.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceChangeAction enum
 */
class ServiceChangeActionTest {

    // ===== Enum Values Tests =====

    @Test
    void testAddedValue() {
        assertEquals("added", ServiceChangeAction.ADDED.getValue());
        assertEquals("added", ServiceChangeAction.ADDED.toString());
    }

    @Test
    void testRemovedValue() {
        assertEquals("removed", ServiceChangeAction.REMOVED.getValue());
        assertEquals("removed", ServiceChangeAction.REMOVED.toString());
    }

    @Test
    void testUpdatedValue() {
        assertEquals("updated", ServiceChangeAction.UPDATED.getValue());
        assertEquals("updated", ServiceChangeAction.UPDATED.toString());
    }

    @Test
    void testCurrentValue() {
        assertEquals("current", ServiceChangeAction.CURRENT.getValue());
        assertEquals("current", ServiceChangeAction.CURRENT.toString());
    }

    @Test
    void testHealthRecoveryValue() {
        assertEquals("health_recovery", ServiceChangeAction.HEALTH_RECOVERY.getValue());
        assertEquals("health_recovery", ServiceChangeAction.HEALTH_RECOVERY.toString());
    }

    @Test
    void testHealthFailureValue() {
        assertEquals("health_failure", ServiceChangeAction.HEALTH_FAILURE.getValue());
        assertEquals("health_failure", ServiceChangeAction.HEALTH_FAILURE.toString());
    }

    // ===== fromValue Tests =====

    @Test
    void testFromValueWithAdded() {
        assertEquals(ServiceChangeAction.ADDED, ServiceChangeAction.fromValue("added"));
    }

    @Test
    void testFromValueWithRemoved() {
        assertEquals(ServiceChangeAction.REMOVED, ServiceChangeAction.fromValue("removed"));
    }

    @Test
    void testFromValueWithUpdated() {
        assertEquals(ServiceChangeAction.UPDATED, ServiceChangeAction.fromValue("updated"));
    }

    @Test
    void testFromValueWithCurrent() {
        assertEquals(ServiceChangeAction.CURRENT, ServiceChangeAction.fromValue("current"));
    }

    @Test
    void testFromValueWithHealthRecovery() {
        assertEquals(ServiceChangeAction.HEALTH_RECOVERY, ServiceChangeAction.fromValue("health_recovery"));
    }

    @Test
    void testFromValueWithHealthFailure() {
        assertEquals(ServiceChangeAction.HEALTH_FAILURE, ServiceChangeAction.fromValue("health_failure"));
    }

    @Test
    void testFromValueWithInvalidValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ServiceChangeAction.fromValue("invalid")
        );
        assertTrue(exception.getMessage().contains("Unknown action: invalid"));
    }

    @Test
    void testFromValueWithNull() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ServiceChangeAction.fromValue(null)
        );
        assertTrue(exception.getMessage().contains("Unknown action: null"));
    }

    @Test
    void testFromValueCaseSensitive() {
        assertEquals(ServiceChangeAction.ADDED, ServiceChangeAction.fromValue("added"));
        assertThrows(IllegalArgumentException.class, () -> ServiceChangeAction.fromValue("ADDED"));
        assertThrows(IllegalArgumentException.class, () -> ServiceChangeAction.fromValue("Added"));
    }

    // ===== Enum Constants Tests =====

    @Test
    void testAllEnumValues() {
        ServiceChangeAction[] values = ServiceChangeAction.values();

        assertEquals(6, values.length);
        assertEquals(ServiceChangeAction.ADDED, values[0]);
        assertEquals(ServiceChangeAction.REMOVED, values[1]);
        assertEquals(ServiceChangeAction.UPDATED, values[2]);
        assertEquals(ServiceChangeAction.CURRENT, values[3]);
        assertEquals(ServiceChangeAction.HEALTH_RECOVERY, values[4]);
        assertEquals(ServiceChangeAction.HEALTH_FAILURE, values[5]);
    }

    // ===== valueOf Tests =====

    @Test
    void testValueOfWithValidName() {
        assertEquals(ServiceChangeAction.ADDED, ServiceChangeAction.valueOf("ADDED"));
        assertEquals(ServiceChangeAction.REMOVED, ServiceChangeAction.valueOf("REMOVED"));
        assertEquals(ServiceChangeAction.UPDATED, ServiceChangeAction.valueOf("UPDATED"));
        assertEquals(ServiceChangeAction.CURRENT, ServiceChangeAction.valueOf("CURRENT"));
        assertEquals(ServiceChangeAction.HEALTH_RECOVERY, ServiceChangeAction.valueOf("HEALTH_RECOVERY"));
        assertEquals(ServiceChangeAction.HEALTH_FAILURE, ServiceChangeAction.valueOf("HEALTH_FAILURE"));
    }

    @Test
    void testValueOfWithInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> ServiceChangeAction.valueOf("INVALID"));
    }

    // ===== ordinal Tests =====

    @Test
    void testAddedOrdinal() {
        assertEquals(0, ServiceChangeAction.ADDED.ordinal());
    }

    @Test
    void testRemovedOrdinal() {
        assertEquals(1, ServiceChangeAction.REMOVED.ordinal());
    }

    @Test
    void testUpdatedOrdinal() {
        assertEquals(2, ServiceChangeAction.UPDATED.ordinal());
    }

    @Test
    void testCurrentOrdinal() {
        assertEquals(3, ServiceChangeAction.CURRENT.ordinal());
    }

    @Test
    void testHealthRecoveryOrdinal() {
        assertEquals(4, ServiceChangeAction.HEALTH_RECOVERY.ordinal());
    }

    @Test
    void testHealthFailureOrdinal() {
        assertEquals(5, ServiceChangeAction.HEALTH_FAILURE.ordinal());
    }

    // ===== Enum Equality Tests =====

    @Test
    void testEnumEquality() {
        assertEquals(ServiceChangeAction.ADDED, ServiceChangeAction.ADDED);
        assertEquals(ServiceChangeAction.REMOVED, ServiceChangeAction.REMOVED);
    }

    @Test
    void testEnumInequality() {
        assertNotEquals(ServiceChangeAction.ADDED, ServiceChangeAction.REMOVED);
        assertNotEquals(ServiceChangeAction.UPDATED, ServiceChangeAction.CURRENT);
    }

    // ===== Semantic Meaning Tests =====

    @Test
    void testAddedSemantics() {
        // 服务实例添加
        ServiceChangeAction action = ServiceChangeAction.ADDED;
        assertEquals("added", action.getValue());
    }

    @Test
    void testRemovedSemantics() {
        // 服务实例移除
        ServiceChangeAction action = ServiceChangeAction.REMOVED;
        assertEquals("removed", action.getValue());
    }

    @Test
    void testUpdatedSemantics() {
        // 服务实例更新
        ServiceChangeAction action = ServiceChangeAction.UPDATED;
        assertEquals("updated", action.getValue());
    }

    @Test
    void testCurrentSemantics() {
        // 当前状态（用于订阅时立即通知现有实例）
        ServiceChangeAction action = ServiceChangeAction.CURRENT;
        assertEquals("current", action.getValue());
    }

    @Test
    void testHealthRecoverySemantics() {
        // 健康状态恢复
        ServiceChangeAction action = ServiceChangeAction.HEALTH_RECOVERY;
        assertEquals("health_recovery", action.getValue());
    }

    @Test
    void testHealthFailureSemantics() {
        // 健康状态失败
        ServiceChangeAction action = ServiceChangeAction.HEALTH_FAILURE;
        assertEquals("health_failure", action.getValue());
    }

    // ===== Getter Test =====

    @Test
    void testGetValue() {
        assertEquals("added", ServiceChangeAction.ADDED.getValue());
        assertEquals("removed", ServiceChangeAction.REMOVED.getValue());
        assertEquals("updated", ServiceChangeAction.UPDATED.getValue());
        assertEquals("current", ServiceChangeAction.CURRENT.getValue());
        assertEquals("health_recovery", ServiceChangeAction.HEALTH_RECOVERY.getValue());
        assertEquals("health_failure", ServiceChangeAction.HEALTH_FAILURE.getValue());
    }
}
