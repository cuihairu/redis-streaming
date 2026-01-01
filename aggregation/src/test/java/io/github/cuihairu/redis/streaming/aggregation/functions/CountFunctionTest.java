package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CountFunction
 */
class CountFunctionTest {

    @Test
    void testGetInstance() {
        // Given & When
        CountFunction instance1 = CountFunction.getInstance();
        CountFunction instance2 = CountFunction.getInstance();

        // Then - should return singleton instance
        assertSame(instance1, instance2);
    }

    @Test
    void testApplyWithEmptyCollection() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> emptyCollection = Collections.emptyList();

        // When
        Long result = countFunction.apply(emptyCollection);

        // Then
        assertEquals(0L, result);
    }

    @Test
    void testApplyWithSingleElement() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> collection = List.of("element");

        // When
        Long result = countFunction.apply(collection);

        // Then
        assertEquals(1L, result);
    }

    @Test
    void testApplyWithMultipleElements() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> collection = List.of(1, 2, 3, 4, 5);

        // When
        Long result = countFunction.apply(collection);

        // Then
        assertEquals(5L, result);
    }

    @Test
    void testApplyWithLargeCollection() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        java.util.List<Object> largeCollection = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            largeCollection.add("item-" + i);
        }

        // When
        Long result = countFunction.apply(largeCollection);

        // Then
        assertEquals(1000L, result);
    }

    @Test
    void testApplyWithNullElements() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        // Note: List.of() doesn't allow null elements, need to use ArrayList
        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add("a");
        list.add(null);
        list.add("b");
        list.add(null);
        list.add("c");
        Collection<Object> collection = list;

        // When
        Long result = countFunction.apply(collection);

        // Then - null elements are still counted
        assertEquals(5L, result);
    }

    @Test
    void testApplyWithMixedObjectTypes() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        // Note: Can't use List.of() with mixed types due to generic type inference
        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add("string");
        list.add(123);
        list.add(45.67);
        list.add(true);
        list.add(null);
        Collection<Object> mixedCollection = list;

        // When
        Long result = countFunction.apply(mixedCollection);

        // Then
        assertEquals(5L, result);
    }

    @Test
    void testGetName() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();

        // When
        String name = countFunction.getName();

        // Then
        assertEquals("COUNT", name);
    }

    @Test
    void testApplyIsConsistent() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> collection = List.of("a", "b", "c");

        // When - call multiple times
        Long result1 = countFunction.apply(collection);
        Long result2 = countFunction.apply(collection);
        Long result3 = countFunction.apply(collection);

        // Then - should always return same result
        assertEquals(result1, result2);
        assertEquals(result2, result3);
        assertEquals(3L, result1);
    }

    @Test
    void testApplyWithSingletonList() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> singletonList = Collections.singletonList("only");

        // When
        Long result = countFunction.apply(singletonList);

        // Then
        assertEquals(1L, result);
    }

    @Test
    void testApplyWithArrayList() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        java.util.List<Object> arrayList = new java.util.ArrayList<>();
        arrayList.add("one");
        arrayList.add("two");
        arrayList.add("three");

        // When
        Long result = countFunction.apply(arrayList);

        // Then
        assertEquals(3L, result);
    }

    @Test
    void testCountDoesNotModifyCollection() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        java.util.List<Object> collection = new java.util.ArrayList<>(List.of("a", "b", "c"));
        int originalSize = collection.size();

        // When
        countFunction.apply(collection);

        // Then - collection should not be modified
        assertEquals(originalSize, collection.size());
        assertEquals(3, collection.size());
    }

    @Test
    void testApplyWithZeroElements() {
        // Given
        CountFunction countFunction = CountFunction.getInstance();
        Collection<Object> empty = Collections.emptySet();

        // When
        Long result = countFunction.apply(empty);

        // Then
        assertEquals(0L, result);
    }
}
