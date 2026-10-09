package io.github.cuihairu.redis.streaming.mq.control;

/**
 * Optional control interface for consumers that support runtime repartitioning
 * (dynamic scaling): the modulo assignment of a subscription can be changed without
 * unsubscribing, and the lease rebalance picks up the new assignment on its next tick.
 *
 * <p>Used by higher-level runtimes to implement parallelism changes: every subtask
 * consumer is re-pinned to {@code partitionId % newParallelism == subtaskIndex}.</p>
 */
public interface ReassignableMessageConsumer {

    /**
     * Update the partition assignment ({@code partitionId % modulo == remainder}) of an
     * existing subscription. Partitions that fall out of the new assignment are released
     * promptly (their workers are stopped and the leases released when the workers exit)
     * so their new owner — another consumer of the same group — can acquire them without
     * waiting for the lease TTL; partitions newly inside the assignment are acquired by
     * the periodic rebalance.
     *
     * <p>Unknown topics return false; the call never throws for invalid arguments
     * (values are clamped like {@code SubscriptionOptions}).</p>
     *
     * @return true when the assignment was applied
     */
    default boolean updatePartitionAssignment(String topic, int partitionModulo, int partitionRemainder) {
        return false;
    }
}
