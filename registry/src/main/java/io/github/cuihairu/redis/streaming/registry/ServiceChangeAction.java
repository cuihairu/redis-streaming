package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;

/**
 * 服务变更动作枚举
 * 定义服务实例变化的类型
 */
@Getter
public enum ServiceChangeAction {
    /**
     * 服务实例添加
     */
    ADDED("added"),

    /**
     * 服务实例移除
     */
    REMOVED("removed"),

    /**
     * 服务实例更新
     */
    UPDATED("updated"),

    /**
     * 当前状态（用于订阅时立即通知现有实例）
     */
    CURRENT("current"),

    /**
     * 健康状态恢复
     */
    HEALTH_RECOVERY("health_recovery"),

    /**
     * 健康状态失败
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
     * 从字符串值解析枚举
     *
     * @param value 字符串值
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果值不匹配
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
