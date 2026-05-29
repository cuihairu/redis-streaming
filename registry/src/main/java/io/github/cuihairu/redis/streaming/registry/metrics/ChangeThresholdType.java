package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * Change threshold typeEnum
 */
public enum ChangeThresholdType {
    /**
     * Percentage change
     * Calculate the percentage change relative to the old value
     */
    PERCENTAGE("percentage"),

    /**
     * Absolute value change
     * Calculate the absolute difference between the new and old values
     */
    ABSOLUTE("absolute"),

    /**
     * Any change
     * Any unequal values are considered a significant change
     */
    ANY("any");

    private final String value;

    ChangeThresholdType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Parse enum from string value
     *
     * @param value String value
     * @return Corresponding enum value
     * @throws IllegalArgumentException If the value does not match
     */
    public static ChangeThresholdType fromValue(String value) {
        for (ChangeThresholdType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown threshold type: " + value);
    }
}
