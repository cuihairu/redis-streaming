package io.github.cuihairu.redis.streaming.reliability.dlq;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default Redis-based implementation of DeadLetterAdmin.
 *
 * Responsibilities:
 * - Discover DLQ topics via key pattern scan ("{prefix}:*:dlq");
 * - Delegate list/size/replay/delete/clear to DeadLetterService;
 * - Convert raw DLQ entries to DeadLetterEntry domain.
 */
@Slf4j
public class RedisDeadLetterAdmin implements DeadLetterAdmin {

    private final RedissonClient redissonClient;
    private final DeadLetterService service;

    public RedisDeadLetterAdmin(RedissonClient redissonClient, DeadLetterService service) {
        this.redissonClient = redissonClient;
        this.service = service;
    }

    @Override
    public List<String> listTopics() {
        String pattern = DlqKeys.dlq("*"); // e.g. "stream:topic:*:dlq"
        List<String> topics = new ArrayList<>();
        try {
            // Avoid deprecated pattern APIs: scan all keys and filter suffix/prefix
            String startToken = DlqKeys.dlq(""); // e.g. "stream:topic::dlq"
            startToken = startToken.substring(0, Math.max(0, startToken.length() - ":dlq".length())); // "stream:topic:"
            Iterable<String> all = redissonClient.getKeys().getKeys();
            for (String key : all) {
                if (key == null) continue;
                if (!key.endsWith(":dlq")) continue;
                if (!key.startsWith(startToken)) continue;
                String t = extractTopicFromDlqKey(key);
                if (t != null && !t.isEmpty()) topics.add(t);
            }
        } catch (Exception e) {
            log.error("Failed to scan DLQ topics with pattern {}", pattern, e);
        }
        return topics.stream().distinct().collect(Collectors.toList());
    }

    @Override
    public long size(String topic) {
        try {
            return service.size(topic);
        } catch (Exception e) {
            log.error("Failed to get DLQ size for topic {}", topic, e);
            return 0;
        }
    }

    @Override
    public List<DeadLetterEntry> list(String topic, int limit) {
        try {
            Map<StreamMessageId, Map<String, Object>> raw = service.range(topic, limit);
            List<DeadLetterEntry> result = new ArrayList<>(raw.size());
            for (Map.Entry<StreamMessageId, Map<String, Object>> e : raw.entrySet()) {
                result.add(DeadLetterCodec.parseEntry(e.getKey().toString(), e.getValue()));
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to list DLQ entries for topic {}", topic, e);
            return List.of();
        }
    }

    @Override
    public boolean replay(String topic, StreamMessageId id) {
        try {
            return service.replay(topic, id);
        } catch (Exception e) {
            log.error("Failed to replay DLQ entry: topic={}, id={}", topic, id, e);
            return false;
        }
    }

    @Override
    public long replayAll(String topic, int maxCount) {
        long ok = 0;
        try {
            Map<StreamMessageId, Map<String, Object>> raw = service.range(topic, Math.max(0, maxCount));
            for (StreamMessageId id : raw.keySet()) {
                try {
                    if (service.replay(topic, id)) ok++;
                } catch (Exception ex) {
                    log.warn("Replay failed for topic={}, id={}", topic, id, ex);
                }
            }
        } catch (Exception e) {
            log.error("Failed to replayAll for topic {}", topic, e);
        }
        return ok;
    }

    @Override
    public boolean delete(String topic, StreamMessageId id) {
        try {
            return service.delete(topic, id);
        } catch (Exception e) {
            log.error("Failed to delete DLQ entry: topic={}, id={}", topic, id, e);
            return false;
        }
    }

    @Override
    public long clear(String topic) {
        try {
            return service.clear(topic);
        } catch (Exception e) {
            log.error("Failed to clear DLQ for topic {}", topic, e);
            return 0;
        }
    }

    // Extract {topic} from "{prefix}:{topic}:dlq" safely (topic may contain colons)
    private String extractTopicFromDlqKey(String key) {
        if (key == null) return null;
        String suffix = ":dlq";
        int end = key.lastIndexOf(suffix);
        if (end <= 0) return null;
        String probe = DlqKeys.dlq(""); // e.g. "stream:topic::dlq"
        String startToken = probe.substring(0, Math.max(0, probe.length() - suffix.length())); // "stream:topic:"
        if (!key.startsWith(startToken)) return null;
        return key.substring(startToken.length(), end);
    }
}
