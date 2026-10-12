package io.github.cuihairu.redis.streaming.join;

/**
 * One buffered join element with its event timestamp. Shared by the raw
 * {@link StreamJoiner} and the keyed operator path ({@code join.operator}) so both sides
 * buffer and compare the same structure.
 *
 * @param <T> The type of the buffered element
 */
public record TimestampedElement<T>(T element, long timestamp) {
}
