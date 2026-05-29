package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;

/**
 * Service change action enum
 * Defines the types of service instance changes
 */
@Getter
public enum ServiceChangeAction {
    /**
     * Service instance added
     */
    ADDED("added"),

    /**
     * Service instance removed
     */
    REMOVED("removed"),

    /**
     * Service instance updated
     */
    UPDATED("updated"),

    /**
     * Current state (used to immediately notify existing instances upon subscription)
     */
    CURRENT("current"),

    /**
     * Health status recovered
     */
    HEALTH_RECOVERY("health_recovery"),

    /**
     * Health status failed
     */
    HEALTH_FAILURE("health_failure");

    private final String value;

    ServiceChangeAction(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Parse the enum from a string value
     *
     * @param value the string value
     * @return the corresponding enum value
     * @throws IllegalArgumentException if the value does not match
     */
    public static ServiceChangeAction fromValue(String value) {
        for (ServiceChangeAction action : values()) {
            if (action.value.equals(value)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown action: " + value);
    }
}
