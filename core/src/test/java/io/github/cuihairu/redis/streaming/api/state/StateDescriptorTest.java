package io.github.cuihairu.redis.streaming.api.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

