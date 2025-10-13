package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * 变化阈值类型枚举
 */
public enum ChangeThresholdType {
    /**
     * 百分比变化
     * 计算相对于旧值的百分比变化
     */
    PERCENTAGE("percentage"),

    /**
     * 绝对值变化
     * 计算新值与旧值的绝对差值
     */
    ABSOLUTE("absolute"),

    /**
     * 任何变化
     * 只要值不相等就认为有显著变化
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
     * 从字符串值解析枚举
     *
     * @param value 字符串值
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果值不匹配
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
