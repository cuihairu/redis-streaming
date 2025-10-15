package io.github.cuihairu.redis.streaming.mq.admin;

import io.github.cuihairu.redis.streaming.mq.admin.model.*;

import java.time.Duration;
import java.util.List;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort;

/**
 * 消息队列管理接口
 * <p>提供队列监控、运维操作等功能</p>
 */
public interface MessageQueueAdmin {

    // ==================== 队列信息查询 ====================

    /**
     * 获取队列信息
     *
     * @param topic 队列名称
     * @return 队列信息
     */
    QueueInfo getQueueInfo(String topic);

    /**
     * 列出所有队列
     *
     * @return 队列名称列表
     */
    List<String> listAllTopics();

    /**
     * 检查队列是否存在
     *
     * @param topic 队列名称
     * @return 是否存在
     */
    boolean topicExists(String topic);

    // ==================== 消费者组管理 ====================

    /**
     * 获取指定队列的所有消费者组
     *
     * @param topic 队列名称
     * @return 消费者组信息列表
     */
    List<ConsumerGroupInfo> getConsumerGroups(String topic);

    /**
     * 获取消费者组统计信息
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @return 统计信息
     */
    ConsumerGroupStats getConsumerGroupStats(String topic, String group);

    /**
     * 检查消费者组是否存在
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @return 是否存在
     */
    boolean consumerGroupExists(String topic, String group);

    // ==================== Pending 消息管理 ====================

    /**
     * 获取待处理消息列表
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @param limit 最大返回数量
     * @return 待处理消息列表
     */
    List<PendingMessage> getPendingMessages(String topic, String group, int limit);

    /**
     * 获取待处理消息列表（带排序与过滤）
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @param limit 最大返回数量
     * @param sort 排序字段（空闲时长/投递次数/ID）
     * @param desc 是否倒序
     * @param minIdleMs 最小空闲时间（毫秒），小于该值的记录将被过滤
     */
    List<PendingMessage> getPendingMessages(String topic, String group, int limit, PendingSort sort, boolean desc, long minIdleMs);

    /**
     * 获取待处理消息数量
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @return 待处理消息数量
     */
    long getPendingCount(String topic, String group);

    // ==================== 运维操作 ====================

    /**
     * 清理队列（保留最新的 N 条消息）
     *
     * @param topic 队列名称
     * @param maxLen 保留的最大消息数
     * @return 删除的消息数
     */
    long trimQueue(String topic, long maxLen);

    /**
     * 按时间清理队列（删除超过指定时间的消息）
     *
     * @param topic 队列名称
     * @param maxAge 最大保留时间
     * @return 删除的消息数
     */
    long trimQueueByAge(String topic, Duration maxAge);

    /**
     * 删除队列
     *
     * @param topic 队列名称
     * @return 是否成功删除
     */
    boolean deleteTopic(String topic);

    /**
     * 删除消费者组
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @return 是否成功删除
     */
    boolean deleteConsumerGroup(String topic, String group);

    /**
     * 重置消费者组的消费位置
     *
     * @param topic 队列名称
     * @param group 消费者组名称
     * @param messageId 要重置到的消息ID（"0"表示从头开始，"$"表示从最新消息开始）
     * @return 是否成功重置
     */
    boolean resetConsumerGroupOffset(String topic, String group, String messageId);

    /**
     * 更新 topic 的分区数（仅允许增加）。
     *
     * @param topic 队列名称
     * @param newPartitionCount 新分区数（必须 > 当前分区数）
     * @return 是否更新成功
     */
    boolean updatePartitionCount(String topic, int newPartitionCount);
}
