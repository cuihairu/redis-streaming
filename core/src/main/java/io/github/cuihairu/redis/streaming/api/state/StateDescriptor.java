package io.github.cuihairu.redis.streaming.api.state;

import java.io.Serializable;

/**
 * StateDescriptor describes the configuration of a state.
 *
 * @param <T> The type of the state value
 */
public class StateDescriptor<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final Class<T> type;
    private final T defaultValue;
    private final int schemaVersion;

    public StateDescriptor(String name, Class<T> type) {
        this(name, type, null, 1);
    }

    public StateDescriptor(String name, Class<T> type, T defaultValue) {
        this(name, type, defaultValue, 1);
    }

    public StateDescriptor(String name, Class<T> type, T defaultValue, int schemaVersion) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.schemaVersion = Math.max(1, schemaVersion);
    }

    public String getName() {
        return name;
    }

    public Class<T> getType() {
        return type;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    /**
     * Schema/version number for state evolution. Defaults to 1.
     */
    public int getSchemaVersion() {
        return schemaVersion;
    }

    @Override
    public String toString() {
        return "StateDescriptor{" +
                "name='" + name + '\'' +
                ", type=" + type.getSimpleName() +
                ", schemaVersion=" + schemaVersion +
                '}';
    }
}
