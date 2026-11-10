package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Partition assignment half of {@link RedisMessageConsumer}: rebalance, lease renewal,
 * eligibility accounting, reassignment and partition metrics. Shares the consumer's
 * subscription and worker maps in-place.
 */
@Slf4j
final class RedisPartitionAssignmentCoordinator {

    private final RedisMessageConsumer consumer;

    RedisPartitionAssignmentCoordinator(RedisMessageConsumer consumer) {
        this.consumer = consumer;
    }

    void rebalanceAssignments() {
        if (!consumer.running.get() || consumer.closed.get()) return;
        int maxLeased = getMaxLeasedPartitions();
        try {
            MqMetrics.get().setMaxLeasedPartitions(consumer.consumerName, maxLeased);
        } catch (Throwable ignore) {
        }
        consumer.subscriptions.forEach((topic, sub) -> {
            int pc = consumer.partitionRegistry.getPartitionCount(topic);
            int eligibleCount = 0;
            for (int i = 0; i < pc; i++) {
                if (sub.partitionModulo != null && sub.partitionRemainder != null) {
                    int mod = Math.max(1, sub.partitionModulo);
                    int rem = Math.max(0, sub.partitionRemainder);
                    if ((i % mod) != (rem % mod)) {
                        continue;
                    }
                }
                eligibleCount++;
                if (consumer.workers.size() >= maxLeased) {
                    continue;
                }
                final PartitionKey pk = new PartitionKey(topic, sub.consumerGroup, i);
                final String leaseKey = consumer.keys.leaseKey(topic, sub.consumerGroup, i);
                final int pid = i;
                // MQ-15: the old containsKey -> tryAcquire -> put sequence was a check-then-act
                // across two maintenance tasks sharing a multi-thread scheduler: renewLeases()
                // (or unsubscribe()) could drop the slot's entry between the check and the put,
                // registering a successor while the retired worker was still draining — two live
                // readers per partition and a double releaseIfOwner at retirement. Per-key
                // compute makes acquire+register atomic against those removals.
                consumer.workers.compute(pk, (k, current) -> {
                    if (current != null) {
                        return current; // a live worker already owns this slot
                    }
                    boolean acquired = consumer.leaseManager.tryAcquire(leaseKey, consumer.consumerName, consumer.options.getLeaseTtlSeconds());
                    if (!acquired && consumer.leaseManager.isOwner(leaseKey, consumer.consumerName)) {
                        acquired = true;
                        try {
                            consumer.leaseManager.renewIfOwner(leaseKey, consumer.consumerName, consumer.options.getLeaseTtlSeconds());
                        } catch (Exception ignore) {
                        }
                    }
                    if (!acquired) {
                        return null;
                    }
                    PartitionWorker worker = new PartitionWorker(topic, sub.consumerGroup, pid, sub.handler);
                    worker.batchOverride = sub.batchOverride;
                    worker.timeoutOverrideMs = sub.timeoutOverrideMs;
                    consumer.consumerPool.submit(() -> consumer.runPartitionWorker(worker));
                    log.info("Acquired partition {}-{} for group {}", topic, pid, sub.consumerGroup);
                    return worker;
                });
            }
            publishPartitionMetrics(topic, sub.consumerGroup, eligibleCount);
        });
    }

    void renewLeases() {
        if (!consumer.running.get() || consumer.closed.get()) return;
        int maxLeased = getMaxLeasedPartitions();
        try {
            MqMetrics.get().setMaxLeasedPartitions(consumer.consumerName, maxLeased);
        } catch (Throwable ignore) {
        }
        Set<String> touched = new HashSet<>();
        consumer.workers.forEach((pk, w) -> {
            String leaseKey = consumer.keys.leaseKey(pk.topic, pk.group, pk.partitionId);
            boolean ok = consumer.leaseManager.renewIfOwner(leaseKey, consumer.consumerName, consumer.options.getLeaseTtlSeconds());
            if (!ok) {
                // lost ownership, stop worker
                w.stop();
                consumer.workers.remove(pk);
                log.info("Lost lease for {}", pk);
            }
            touched.add(pk.topic + "|" + pk.group);
        });
        consumer.subscriptions.forEach((topic, sub) -> touched.add(topic + "|" + sub.consumerGroup));
        for (String key : touched) {
            String[] parts = key.split("\\|", 2);
            if (parts.length != 2) {
                continue;
            }
            String topic = parts[0];
            String group = parts[1];
            int eligibleCount = computeEligiblePartitions(topic, group);
            publishPartitionMetrics(topic, group, eligibleCount);
        }
    }

    int getMaxLeasedPartitions() {
        int cfg = consumer.options.getMaxLeasedPartitionsPerConsumer();
        if (cfg > 0) {
            return Math.max(1, cfg);
        }
        return Math.max(1, consumer.options.getWorkerThreads());
    }

    int computeEligiblePartitions(String topic, String group) {
        Subscription sub = consumer.subscriptions.get(topic);
        if (sub == null || !Objects.equals(sub.consumerGroup, group)) {
            return 0;
        }
        int pc = consumer.partitionRegistry.getPartitionCount(topic);
        if (sub.partitionModulo == null || sub.partitionRemainder == null) {
            return pc;
        }
        int mod = Math.max(1, sub.partitionModulo);
        int rem = Math.max(0, sub.partitionRemainder);
        int eligible = 0;
        for (int i = 0; i < pc; i++) {
            if ((i % mod) == (rem % mod)) {
                eligible++;
            }
        }
        return eligible;
    }

    boolean updatePartitionAssignment(String topic, int partitionModulo, int partitionRemainder) {
        Subscription sub = consumer.subscriptions.get(topic);
        if (sub == null) {
            return false;
        }
        int mod = Math.max(1, partitionModulo);
        int rem = Math.max(0, partitionRemainder);
        sub.partitionModulo = mod;
        sub.partitionRemainder = rem;
        // Prompt handover: stop the consumer.workers of partitions that fell out of the new
        // assignment. The worker's own exit path removes its slot and releases the lease
        // while it is still the registered owner (MQ-15 semantics), so the new owner —
        // another consumer of the same group — can acquire without waiting for the TTL.
        consumer.workers.forEach((pk, w) -> {
            if (pk.topic.equals(topic) && pk.group.equals(sub.consumerGroup)
                    && (pk.partitionId % mod) != (rem % mod)) {
                w.stop();
            }
        });
        log.info("Updated partition assignment: topic='{}', group='{}', consumer='{}', modulo={}, remainder={}",
                topic, sub.consumerGroup, consumer.consumerName, mod, rem);
        return true;
    }

    void publishPartitionMetrics(String topic, String group, int eligibleCount) {
        try {
            int leased = 0;
            for (PartitionKey pk : consumer.workers.keySet()) {
                if (Objects.equals(pk.topic, topic) && Objects.equals(pk.group, group)) {
                    leased++;
                }
            }
            MqMetrics.get().setEligiblePartitions(consumer.consumerName, topic, group, eligibleCount);
            MqMetrics.get().setLeasedPartitions(consumer.consumerName, topic, group, leased);
        } catch (Throwable ignore) {
        }
    }
}
