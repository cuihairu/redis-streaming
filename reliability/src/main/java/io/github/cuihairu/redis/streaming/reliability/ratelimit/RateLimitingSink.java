package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;

import java.util.Objects;
import java.util.function.Function;

/**
 * A sink decorator that applies rate limiting before delegating to the underlying sink.
 *
 * If the limiter denies, the element is either dropped or an exception is thrown
 * based on the configured policy.
 */
public class RateLimitingSink<T> implements StreamSink<T> {

    public enum DenyPolicy { DROP, THROW }

    private final RateLimiter limiter;
    private final Function<T, String> keySelector;
    private final StreamSink<T> delegate;
    private final DenyPolicy denyPolicy;

    public RateLimitingSink(RateLimiter limiter,
                            Function<T, String> keySelector,
                            StreamSink<T> delegate,
                            DenyPolicy denyPolicy) {
        this.limiter = Objects.requireNonNull(limiter);
        this.keySelector = Objects.requireNonNull(keySelector);
        this.delegate = Objects.requireNonNull(delegate);
        this.denyPolicy = denyPolicy == null ? DenyPolicy.DROP : denyPolicy;
    }

    public static <T> RateLimitingSink<T> drop(RateLimiter limiter, Function<T, String> keySelector, StreamSink<T> delegate) {
        return new RateLimitingSink<>(limiter, keySelector, delegate, DenyPolicy.DROP);
    }

    public static <T> RateLimitingSink<T> throwing(RateLimiter limiter, Function<T, String> keySelector, StreamSink<T> delegate) {
        return new RateLimitingSink<>(limiter, keySelector, delegate, DenyPolicy.THROW);
    }

    @Override
    public void invoke(T value) throws Exception {
        String key = keySelector.apply(value);
        if (limiter.allow(key)) {
            delegate.invoke(value);
        } else if (denyPolicy == DenyPolicy.THROW) {
            throw new RateLimitedException("Rate limited for key: " + key);
        }
        // DROP: do nothing
    }

    /** Exception thrown when denyPolicy == THROW and limiter denies. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) { super(message); }
    }
}

