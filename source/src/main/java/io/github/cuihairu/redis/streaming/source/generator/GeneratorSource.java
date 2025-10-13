package io.github.cuihairu.redis.streaming.source.generator;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;

import java.util.function.Supplier;

/**
 * A source that generates elements using a generator function.
 *
 * This is useful for testing or generating synthetic data streams.
 *
 * @param <T> The type of elements
 */
public class GeneratorSource<T> implements StreamSource<T> {

    private static final long serialVersionUID = 1L;

    private final Supplier<T> generator;
    private final long count;
    private final long delayMillis;
    private volatile boolean running = true;

    /**
     * Create an infinite generator source (stops only when cancelled)
     *
     * @param generator The function to generate elements
     */
    public GeneratorSource(Supplier<T> generator) {
        this(generator, Long.MAX_VALUE, 0);
    }

    /**
     * Create a generator source with a limit
     *
     * @param generator The function to generate elements
     * @param count Maximum number of elements to generate
     */
    public GeneratorSource(Supplier<T> generator, long count) {
        this(generator, count, 0);
    }

    /**
     * Create a generator source with limit and delay
     *
     * @param generator The function to generate elements
     * @param count Maximum number of elements to generate
     * @param delayMillis Delay in milliseconds between elements
     */
    public GeneratorSource(Supplier<T> generator, long count, long delayMillis) {
        this.generator = generator;
        this.count = count;
        this.delayMillis = delayMillis;
    }

    @Override
    public void run(SourceContext<T> ctx) throws Exception {
        for (long i = 0; i < count && running; i++) {
            T element = generator.get();
            ctx.collect(element);

            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
        }
    }

    @Override
    public void cancel() {
        running = false;
    }

    /**
     * Create a sequence generator that produces numbers from 0 to count-1
     *
     * @param count Number of elements to generate
     * @return A generator source
     */
    public static GeneratorSource<Long> sequence(long count) {
        return new GeneratorSource<>(new SequenceGenerator(), count);
    }

    /**
     * Create a sequence generator with start value
     *
     * @param start Starting value
     * @param count Number of elements to generate
     * @return A generator source
     */
    public static GeneratorSource<Long> sequence(long start, long count) {
        return new GeneratorSource<>(new SequenceGenerator(start), count);
    }

    /**
     * Sequence generator
     */
    private static class SequenceGenerator implements Supplier<Long> {
        private static final long serialVersionUID = 1L;
        private long current;

        public SequenceGenerator() {
            this(0);
        }

        public SequenceGenerator(long start) {
            this.current = start;
        }

        @Override
        public Long get() {
            return current++;
        }
    }
}
