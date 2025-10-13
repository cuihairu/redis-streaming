package io.github.cuihairu.redis.streaming.registry.metrics;

import lombok.Getter;

/**
 * 变化阈值配置
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
     * 向后兼容的构造函数，接受字符串类型
     *
     * @param threshold 阈值
     * @param typeStr 类型字符串
     * @deprecated 使用 {@link #ChangeThreshold(double, ChangeThresholdType)} 代替
     */
    @Deprecated
    public ChangeThreshold(double threshold, String typeStr) {
        this.threshold = threshold;
        this.type = ChangeThresholdType.fromValue(typeStr);
    }

    /**
     * 检查变化是否显著
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