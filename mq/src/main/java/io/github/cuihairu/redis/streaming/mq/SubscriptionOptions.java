package io.github.cuihairu.redis.streaming.mq;

import lombok.Getter;

/**
 * Per-subscription overrides. Keep minimal for now.
 */
@Getter
public class SubscriptionOptions {
    private Integer batchCount; // null -> use global
    private Long pollTimeoutMs; // null -> use global
    private Integer partitionModulo; // null -> disabled
    private Integer partitionRemainder; // null -> disabled

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final SubscriptionOptions o = new SubscriptionOptions();
        public Builder batchCount(int v) { o.batchCount = Math.max(1, v); return this; }
        public Builder pollTimeoutMs(long v) { o.pollTimeoutMs = Math.max(0, v); return this; }
        public Builder partitionModulo(int v) { o.partitionModulo = Math.max(1, v); return this; }
        public Builder partitionRemainder(int v) { o.partitionRemainder = Math.max(0, v); return this; }
        public SubscriptionOptions build() { return o; }
    }

}
