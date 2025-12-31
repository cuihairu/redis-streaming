package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;

import java.util.Objects;

/**
 * Configuration for the Redis-backed runtime.
 *
 * <p>This runtime is single-process but uses Redis Streams consumer groups for distributed-safe
 * message consumption and at-least-once delivery.</p>
 */
public final class RedisRuntimeConfig {

    private final String jobName;
    private final String stateKeyPrefix;
    private final MqOptions mqOptions;

    private RedisRuntimeConfig(Builder b) {
        this.jobName = Objects.requireNonNull(b.jobName, "jobName");
        this.stateKeyPrefix = Objects.requireNonNull(b.stateKeyPrefix, "stateKeyPrefix");
        this.mqOptions = b.mqOptions == null ? MqOptions.builder().build() : b.mqOptions;
    }

    public String getJobName() {
        return jobName;
    }

    public String getStateKeyPrefix() {
        return stateKeyPrefix;
    }

    public MqOptions getMqOptions() {
        return mqOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String jobName = "redis-streaming-job";
        private String stateKeyPrefix = "streaming:runtime";
        private MqOptions mqOptions;

        public Builder jobName(String jobName) {
            if (jobName != null && !jobName.isBlank()) {
                this.jobName = jobName;
            }
            return this;
        }

        public Builder stateKeyPrefix(String stateKeyPrefix) {
            if (stateKeyPrefix != null && !stateKeyPrefix.isBlank()) {
                this.stateKeyPrefix = stateKeyPrefix;
            }
            return this;
        }

        public Builder mqOptions(MqOptions mqOptions) {
            this.mqOptions = mqOptions;
            return this;
        }

        public RedisRuntimeConfig build() {
            return new RedisRuntimeConfig(this);
        }
    }
}

