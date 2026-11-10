package io.github.cuihairu.redis.streaming.mq.impl;

import java.util.Objects;


/** Value key identifying a leased partition: topic + consumer group + partition id. */
final class PartitionKey {
        final String topic;
        final String group;
        final int partitionId;

        PartitionKey(String topic, String group, int partitionId) {
            this.topic = topic;
            this.group = group;
            this.partitionId = partitionId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PartitionKey)) return false;
            PartitionKey that = (PartitionKey) o;
            return partitionId == that.partitionId &&
                    Objects.equals(topic, that.topic) &&
                    Objects.equals(group, that.group);
        }

        @Override public int hashCode() {
            return Objects.hash(topic, group, partitionId);
        }

        @Override public String toString() {
            return topic + ":" + group + ":p" + partitionId;
        }
}
