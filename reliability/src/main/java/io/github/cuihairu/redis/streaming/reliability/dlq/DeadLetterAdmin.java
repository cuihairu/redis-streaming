package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.redisson.api.StreamMessageId;

import java.util.List;

/**
 * DLQ 运维/治理接口（仅契约，无 Web 实现）。
 *
 * 设计目标：
 * - 提供按 topic 维度的列举/统计/回放/清理能力；
 * - 与 MQ 解耦（复用 reliability 下的 DeadLetterEntry/Service 概念）；
 * - 仅定义接口，便于后续在 CLI / REST / Admin 控制台中复用。
 */
public interface DeadLetterAdmin {
    /**
     * 列举存在 DLQ 的所有 topic 名称（实现可按 key 前缀或注册中心获取）。
     */
    List<String> listTopics();

    /**
     * 获取指定 topic 的 DLQ 消息总数（尽量为近似 O(1) 实现）。
     */
    long size(String topic);

    /**
     * 列举指定 topic 的 DLQ 消息（按时间/ID 顺序，最多返回 limit 条）。
     */
    List<DeadLetterEntry> list(String topic, int limit);

    /**
     * 回放指定 DLQ 消息到原 topic/分区。
     * 返回 true 表示已成功发布回原队列；失败由调用方决定是否重试或上报。
     */
    boolean replay(String topic, StreamMessageId id);

    /**
     * 批量回放该 topic 下最多 maxCount 条消息。
     * 返回成功回放的条数；失败条目由实现自行处理（可忽略/重试/保留）。
     */
    long replayAll(String topic, int maxCount);

    /**
     * 删除指定 DLQ 消息（不回放，直接丢弃）。
     */
    boolean delete(String topic, StreamMessageId id);

    /**
     * 清空指定 topic 的 DLQ，返回清理的条数。
     */
    long clear(String topic);
}

