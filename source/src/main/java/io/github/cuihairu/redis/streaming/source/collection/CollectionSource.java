package io.github.cuihairu.redis.streaming.source.collection;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;

import java.util.Collection;

/**
 * A source that reads elements from a collection.
 *
 * This is useful for testing or processing finite datasets.
 *
 * @param <T> The type of elements
 */
public class CollectionSource<T> implements StreamSource<T> {

    private static final long serialVersionUID = 1L;

    private final Collection<T> elements;
    private volatile boolean running = true;

    /**
     * Create a source from a collection
     *
     * @param elements The collection of elements to emit
     */
    public CollectionSource(Collection<T> elements) {
        this.elements = elements;
    }

    @Override
    public void run(SourceContext<T> ctx) throws Exception {
        for (T element : elements) {
            if (!running) {
                break;
            }
            ctx.collect(element);
        }
    }

    @Override
    public void cancel() {
        running = false;
    }
}
