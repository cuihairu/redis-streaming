package io.github.cuihairu.redis.streaming.api.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateDescriptorTest {

    @Test
    void exposesNameTypeDefaultValueAndToString() {
        StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 7);

        assertEquals("count", descriptor.getName());
        assertEquals(Integer.class, descriptor.getType());
        assertEquals(7, descriptor.getDefaultValue());

        String s = descriptor.toString();
        assertTrue(s.contains("name='count'"));
        assertTrue(s.contains("type=Integer"));
    }

    @Test
    void constructorWithNameAndType() {
        StateDescriptor<String> descriptor = new StateDescriptor<>("name", String.class);

        assertEquals("name", descriptor.getName());
        assertEquals(String.class, descriptor.getType());
        assertNull(descriptor.getDefaultValue());
    }

    @Test
    void constructorWithNameTypeAndDefaultValue() {
        StateDescriptor<Double> descriptor = new StateDescriptor<>("rate", Double.class, 3.14);

        assertEquals("rate", descriptor.getName());
        assertEquals(Double.class, descriptor.getType());
        assertEquals(3.14, descriptor.getDefaultValue());
    }

    @Test
    void constructorWithNullDefaultValue() {
        StateDescriptor<Long> descriptor = new StateDescriptor<>("timestamp", Long.class, null);

        assertEquals("timestamp", descriptor.getName());
        assertEquals(Long.class, descriptor.getType());
        assertNull(descriptor.getDefaultValue());
    }

    @Test
    void toStringIncludesTypeName() {
        StateDescriptor<Boolean> descriptor = new StateDescriptor<>("flag", Boolean.class, true);

        String s = descriptor.toString();
        assertTrue(s.contains("StateDescriptor"));
        assertTrue(s.contains("name='flag'"));
        assertTrue(s.contains("type=Boolean"));
    }

    @Test
    void getNameReturnsCorrectName() {
        StateDescriptor<Integer> descriptor = new StateDescriptor<>("counter", Integer.class);

        assertEquals("counter", descriptor.getName());
    }

    @Test
    void getTypeReturnsCorrectType() {
        StateDescriptor<String> descriptor = new StateDescriptor<>("text", String.class);

        assertEquals(String.class, descriptor.getType());
    }

    @Test
    void getDefaultValueReturnsDefault() {
        StateDescriptor<Integer> descriptor = new StateDescriptor<>("value", Integer.class, 42);

        assertEquals(42, descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithObject() {
        class TestData {
            String name;
            int value;
        }

        StateDescriptor<TestData> descriptor = new StateDescriptor<>("data", TestData.class);

        assertEquals("data", descriptor.getName());
        assertEquals(TestData.class, descriptor.getType());
        assertNull(descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithEmptyStringName() {
        StateDescriptor<String> descriptor = new StateDescriptor<>("", String.class, "default");

        assertEquals("", descriptor.getName());
        assertEquals(String.class, descriptor.getType());
        assertEquals("default", descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithPrimitiveWrapper() {
        StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);

        assertEquals("count", descriptor.getName());
        assertEquals(Integer.class, descriptor.getType());
        assertEquals(0, descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithLongDefaultValue() {
        StateDescriptor<Long> descriptor = new StateDescriptor<>("timestamp", Long.class, 123456789L);

        assertEquals(123456789L, descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithStringDefaultValue() {
        StateDescriptor<String> descriptor = new StateDescriptor<>("message", String.class, "hello");

        assertEquals("hello", descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithBooleanDefaultValue() {
        StateDescriptor<Boolean> descriptor = new StateDescriptor<>("enabled", Boolean.class, false);

        assertEquals(false, descriptor.getDefaultValue());
    }

    @Test
    void twoDescriptorsWithSameValuesAreEqual() {
        StateDescriptor<Integer> d1 = new StateDescriptor<>("count", Integer.class, 5);
        StateDescriptor<Integer> d2 = new StateDescriptor<>("count", Integer.class, 5);

        assertEquals(d1.getName(), d2.getName());
        assertEquals(d1.getType(), d2.getType());
        assertEquals(d1.getDefaultValue(), d2.getDefaultValue());
    }

    @Test
    void descriptorWithNegativeDefaultValue() {
        StateDescriptor<Integer> descriptor = new StateDescriptor<>("offset", Integer.class, -10);

        assertEquals(-10, descriptor.getDefaultValue());
    }

    @Test
    void descriptorWithZeroDefaultValue() {
        StateDescriptor<Double> descriptor = new StateDescriptor<>("rate", Double.class, 0.0);

        assertEquals(0.0, descriptor.getDefaultValue());
    }
}

