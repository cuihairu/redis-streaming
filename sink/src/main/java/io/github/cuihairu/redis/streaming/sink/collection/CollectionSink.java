package io.github.cuihairu.redis.streaming.sink.collection;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A sink that collects elements into a collection.
 *
 * This is useful for testing or collecting results in memory.
 *
 * @param <T> The type of elements
 */
public class CollectionSink<T> implements StreamSink<T> {

    private static final long serialVersionUID = 1L;

    private final Collection<T> collection;

    /**
     * Create a collection sink with a new ArrayList
     */
    public CollectionSink() {
        this(new ArrayList<>());
    }

    /**
     * Create a collection sink with a specific collection
     *
     * @param collection The collection to add elements to
     */
    public CollectionSink(Collection<T> collection) {
        this.collection = collection;
    }

    @Override
    public void invoke(T value) {
        collection.add(value);
    }

    /**
     * Get the collection containing all collected elements
     *
     * @return The collection
     */
    public Collection<T> getCollection() {
        return collection;
    }

    /**
     * Get the collection as a list (if it is a list)
     *
     * @return The list, or null if not a list
     */
    public List<T> getList() {
        if (collection instanceof List) {
            return (List<T>) collection;
        }
        return null;
    }

    /**
     * Get the number of collected elements
     *
     * @return The size
     */
    public int size() {
        return collection.size();
    }

    /**
     * Clear all collected elements
     */
    public void clear() {
        collection.clear();
    }
}
