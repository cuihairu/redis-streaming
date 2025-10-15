package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamTrimArgs;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dead Letter Queue management utility
 */
@Slf4j
public class DeadLetterQueueManager {

    private final RedissonClient redissonClient;

    public DeadLetterQueueManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Get all messages from a dead letter queue
     *
     * @param originalTopic the original topic name
     * @param limit maximum number of messages to retrieve
     * @return list of dead letter messages
     */
    public Map<StreamMessageId, Map<String, Object>> getDeadLetterMessages(String originalTopic, int limit) {
        String dlqKey = StreamKeys.dlq(originalTopic);
        RStream<String, Object> dlqStream = redissonClient.getStream(dlqKey);

        try {
            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> result = dlqStream.range(limit, StreamMessageId.MIN, StreamMessageId.MAX);
            return result;
        } catch (Exception e) {
            log.error("Failed to read messages from dead letter queue: {}", dlqKey, e);
            return Map.of();
        }
    }

    /**
     * Replay a dead letter message back to the original topic
     *
     * @param originalTopic the original topic name
     * @param messageId the dead letter message ID to replay
     * @return true if successfully replayed, false otherwise
     */
    public boolean replayMessage(String originalTopic, StreamMessageId messageId) {
        String dlqTopic = StreamKeys.dlq(originalTopic);

        try {
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqTopic);

            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> messages = dlqStream.range(1, messageId, messageId);

            if (messages.isEmpty()) {
                log.warn("Message {} not found in dead letter queue: {}", messageId, dlqTopic);
                return false;
            }

            Map<String, Object> messageData = messages.get(messageId);

            int pid = 0;
            Object pidVal = messageData.get("partitionId");
            if (pidVal instanceof Number) pid = ((Number) pidVal).intValue();
            else if (pidVal != null) {
                try { pid = Integer.parseInt(pidVal.toString()); } catch (Exception ignore) {}
            }

            String streamKey = StreamKeys.partitionStream(originalTopic, pid);
            RStream<String, Object> originalStream = redissonClient.getStream(streamKey);

            Map<String, Object> replayData = io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec
                    .buildPartitionEntryFromDlq(messageData, originalTopic, pid);

            StreamMessageId newMessageId = originalStream.add(StreamAddArgs.entries(replayData));

            log.info("Message {} replayed from DLQ {} to topic {} partition {} with new ID: {}",
                    messageId, dlqTopic, originalTopic, pid, newMessageId);

            return true;

        } catch (Exception e) {
            log.error("Failed to replay message {} from DLQ {} to topic {}",
                    messageId, dlqTopic, originalTopic, e);
            return false;
        }
    }

    /**
     * Delete a message from dead letter queue
     *
     * @param originalTopic the original topic name
     * @param messageId the message ID to delete
     * @return true if successfully deleted, false otherwise
     */
    public boolean deleteMessage(String originalTopic, StreamMessageId messageId) {
        String dlqTopic = StreamKeys.dlq(originalTopic);

        try {
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqTopic);
            long deleted = dlqStream.remove(messageId);

            if (deleted > 0) {
                log.info("Message {} deleted from dead letter queue: {}", messageId, dlqTopic);
                return true;
            } else {
                log.warn("Message {} not found in dead letter queue: {}", messageId, dlqTopic);
                return false;
            }

        } catch (Exception e) {
            log.error("Failed to delete message {} from dead letter queue: {}",
                    messageId, dlqTopic, e);
            return false;
        }
    }

    /**
     * Get the count of messages in a dead letter queue
     *
     * @param originalTopic the original topic name
     * @return number of messages in the dead letter queue
     */
    public long getDeadLetterQueueSize(String originalTopic) {
        String dlqTopic = StreamKeys.dlq(originalTopic);

        try {
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqTopic);
            long sz = dlqStream.size();
            if (sz == 0) {
                // Fallback in case size() lags; try fetching a single entry
                @SuppressWarnings("deprecation")
                Map<StreamMessageId, Map<String, Object>> any = dlqStream.range(1, StreamMessageId.MIN, StreamMessageId.MAX);
                return any.isEmpty() ? 0 : any.size();
            }
            return sz;
        } catch (Exception e) {
            log.error("Failed to get size of dead letter queue: {}", dlqTopic, e);
            return 0;
        }
    }

    /**
     * Clear all messages from a dead letter queue
     *
     * @param originalTopic the original topic name
     * @return number of messages deleted
     */
    public long clearDeadLetterQueue(String originalTopic) {
        String dlqTopic = StreamKeys.dlq(originalTopic);

        try {
            RStream<String, Object> dlqStream = redissonClient.getStream(dlqTopic);
            long size = dlqStream.size();

            // Remove the stream completely to clear all messages
            boolean deleted = redissonClient.getKeys().delete(dlqTopic) > 0;

            log.info("Cleared {} messages from dead letter queue: {}", size, dlqTopic);
            return deleted ? size : 0;

        } catch (Exception e) {
            log.error("Failed to clear dead letter queue: {}", dlqTopic, e);
            return 0;
        }
    }
}
