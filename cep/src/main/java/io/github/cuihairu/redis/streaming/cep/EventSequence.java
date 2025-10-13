package io.github.cuihairu.redis.streaming.cep;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a sequence of events that match a pattern.
 *
 * @param <T> The type of events
 */
@Data
public class EventSequence<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<T> events;
    private final long startTime;
    private final long endTime;

    public EventSequence() {
        this.events = new ArrayList<>();
        this.startTime = System.currentTimeMillis();
        this.endTime = startTime;
    }

    public EventSequence(List<T> events) {
        this.events = new ArrayList<>(events);
        this.startTime = System.currentTimeMillis();
        this.endTime = startTime;
    }

    public EventSequence(List<T> events, long startTime, long endTime) {
        this.events = new ArrayList<>(events);
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * Add an event to the sequence
     *
     * @param event The event to add
     */
    public void addEvent(T event) {
        events.add(event);
    }

    /**
     * Get the first event in the sequence
     *
     * @return The first event, or null if empty
     */
    public T getFirst() {
        return events.isEmpty() ? null : events.get(0);
    }

    /**
     * Get the last event in the sequence
     *
     * @return The last event, or null if empty
     */
    public T getLast() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    /**
     * Get the number of events in the sequence
     *
     * @return The size
     */
    public int size() {
        return events.size();
    }

    /**
     * Check if the sequence is empty
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Get the duration of the sequence
     *
     * @return Duration in milliseconds
     */
    public long getDuration() {
        return endTime - startTime;
    }

    /**
     * Get a copy of the events list
     *
     * @return Copy of events
     */
    public List<T> getEventsCopy() {
        return new ArrayList<>(events);
    }

    @Override
    public String toString() {
        return "EventSequence{" +
                "size=" + events.size() +
                ", duration=" + getDuration() + "ms" +
                '}';
    }
}
