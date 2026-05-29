package io.github.cuihairu.redis.streaming.registry.metrics;

import lombok.Getter;

/**
 * Change thresholdConfiguration
 */
@Getter
public class ChangeThreshold {
    private final double threshold;
    private final ChangeThresholdType type;

    public ChangeThreshold(double threshold, ChangeThresholdType type) {
        this.threshold = threshold;
        this.type = type;
    }

    /**
     * Backward-compatible constructor that accepts a string type
     *
     * @param threshold Threshold value
     * @param typeStr Type string
     * @deprecated Use {@link #ChangeThreshold(double, ChangeThresholdType)} instead
     */
    @Deprecated
    public ChangeThreshold(double threshold, String typeStr) {
        this.threshold = threshold;
        this.type = ChangeThresholdType.fromValue(typeStr);
    }

    /**
     * Check whether the change is significant
     */
    public boolean isSignificant(Object oldValue, Object newValue) {
        if (type == ChangeThresholdType.ANY) {
            return !java.util.Objects.equals(oldValue, newValue);
        }

        if (oldValue == null || newValue == null) {
            return true;
        }

        if (!(oldValue instanceof Number) || !(newValue instanceof Number)) {
            return !oldValue.equals(newValue);
        }

        double oldNum = ((Number) oldValue).doubleValue();
        double newNum = ((Number) newValue).doubleValue();

        switch (type) {
            case PERCENTAGE:
                if (oldNum == 0) {
                    return newNum != 0;
                }
                double percentChange = Math.abs(newNum - oldNum) / Math.abs(oldNum) * 100;
                return percentChange > threshold;

            case ABSOLUTE:
                double absoluteChange = Math.abs(newNum - oldNum);
                return absoluteChange > threshold;

            default:
                return false;
        }
    }

}