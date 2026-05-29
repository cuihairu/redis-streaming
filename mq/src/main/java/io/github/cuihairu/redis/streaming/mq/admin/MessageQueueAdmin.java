package io.github.cuihairu.redis.streaming.mq.admin;

import io.github.cuihairu.redis.streaming.mq.admin.model.*;

import java.time.Duration;
import java.util.List;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort;

/**
 * Message queue admin interface
 * <p>Provides queue monitoring, operational management, and other features</p>
 */
public interface MessageQueueAdmin {

    // ==================== Queue Information Queries ====================

    /**
     * Get queue information
     *
     * @param topic queue name
     * @return queue information
     */
    QueueInfo getQueueInfo(String topic);

    /**
     * List all queues
     *
     * @return list of queue names
     */
    List<String> listAllTopics();

    /**
     * Check if a queue exists
     *
     * @param topic queue name
     * @return whether the queue exists
     */
    boolean topicExists(String topic);

    // ==================== Consumer Group Management ====================

    /**
     * Get all consumer groups for a given queue
     *
     * @param topic queue name
     * @return list of consumer group information
     */
    List<ConsumerGroupInfo> getConsumerGroups(String topic);

    /**
     * Get consumer group statistics
     *
     * @param topic queue name
     * @param group consumer group name
     * @return statistics
     */
    ConsumerGroupStats getConsumerGroupStats(String topic, String group);

    /**
     * Check if a consumer group exists
     *
     * @param topic queue name
     * @param group consumer group name
     * @return whether the consumer group exists
     */
    boolean consumerGroupExists(String topic, String group);

    // ==================== Pending Message Management ====================

    /**
     * Get pending message list
     *
     * @param topic queue name
     * @param group consumer group name
     * @param limit maximum number of results
     * @return list of pending messages
     */
    List<PendingMessage> getPendingMessages(String topic, String group, int limit);

    /**
     * Get pending message list (with sorting and filtering)
     *
     * @param topic queue name
     * @param group consumer group name
     * @param limit maximum number of results
     * @param sort sort field (idle time / delivery count / ID)
     * @param desc whether to sort in descending order
     * @param minIdleMs minimum idle time (milliseconds); records below this value will be filtered out
     */
    List<PendingMessage> getPendingMessages(String topic, String group, int limit, PendingSort sort, boolean desc, long minIdleMs);

    /**
     * Get pending message count
     *
     * @param topic queue name
     * @param group consumer group name
     * @return pending message count
     */
    long getPendingCount(String topic, String group);

    // ==================== Operations ====================

    /**
     * Trim the queue (keep the latest N messages)
     *
     * @param topic queue name
     * @param maxLen maximum number of messages to keep
     * @return number of deleted messages
     */
    long trimQueue(String topic, long maxLen);

    /**
     * Trim the queue by age (delete messages older than the specified time)
     *
     * @param topic queue name
     * @param maxAge maximum retention time
     * @return number of deleted messages
     */
    long trimQueueByAge(String topic, Duration maxAge);

    /**
     * Delete a queue
     *
     * @param topic queue name
     * @return whether the queue was successfully deleted
     */
    boolean deleteTopic(String topic);

    /**
     * Delete a consumer group
     *
     * @param topic queue name
     * @param group consumer group name
     * @return whether the consumer group was successfully deleted
     */
    boolean deleteConsumerGroup(String topic, String group);

    /**
     * Reset the consumption position of a consumer group
     *
     * @param topic queue name
     * @param group consumer group name
     * @param messageId the message ID to reset to ("0" means from the beginning, "$" means from the latest message)
     * @return whether the reset was successful
     */
    boolean resetConsumerGroupOffset(String topic, String group, String messageId);

    /**
     * Update the partition count for a topic (only increases are allowed).
     *
     * @param topic queue name
     * @param newPartitionCount new partition count (must be greater than the current partition count)
     * @return whether the update was successful
     */
    boolean updatePartitionCount(String topic, int newPartitionCount);

    // ==================== Raw Message Peek (Read-Only) ====================

    /**
     * List recent messages across all partitions (aggregated per partition count, sorted by Stream ID descending)
     */
    List<MessageEntry> listRecent(String topic, int perPartitionCount);

    /**
     * Read messages by partition and range (returns fields as-is from XRANGE/XREVRANGE).
     * @param reverse if true, uses XREVRANGE to read from toId in reverse order
     */
    List<MessageEntry> range(String topic, int partitionId, String fromId, String toId, int count, boolean reverse);
}
