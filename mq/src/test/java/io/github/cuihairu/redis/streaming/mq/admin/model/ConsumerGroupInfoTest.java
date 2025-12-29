package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsumerGroupInfo
 */
class ConsumerGroupInfoTest {

    @Test
    void testBuilder() {
        StreamMessageId lastDeliveredId = new StreamMessageId(100);

        List<ConsumerInfo> consumerList = new ArrayList<>();
        consumerList.add(ConsumerInfo.builder().name("consumer1").pending(5).idleTime(1000).build());
        consumerList.add(ConsumerInfo.builder().name("consumer2").pending(3).idleTime(2000).build());

        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("group1")
                .consumers(2)
                .pending(8)
                .lastDeliveredId(lastDeliveredId)
                .consumerList(consumerList)
                .totalConsumed(1000L)
                .build();

        assertEquals("group1", groupInfo.getName());
        assertEquals(2, groupInfo.getConsumers());
        assertEquals(8, groupInfo.getPending());
        assertEquals(lastDeliveredId, groupInfo.getLastDeliveredId());
        assertEquals(consumerList, groupInfo.getConsumerList());
        assertEquals(1000L, groupInfo.getTotalConsumed());
    }

    @Test
    void testBuilderWithPartialFields() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("partial-group")
                .build();

        assertEquals("partial-group", groupInfo.getName());
        assertEquals(0, groupInfo.getConsumers());
        assertEquals(0, groupInfo.getPending());
        assertNull(groupInfo.getLastDeliveredId());
        assertNull(groupInfo.getConsumerList());
        assertEquals(0, groupInfo.getTotalConsumed());
    }

    @Test
    void testSettersAndGetters() {
        // Create a ConsumerGroupInfo using builder
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder().build();
        StreamMessageId lastId = new StreamMessageId(50);

        List<ConsumerInfo> consumerList = new ArrayList<>();
        consumerList.add(ConsumerInfo.builder().name("c1").build());

        groupInfo.setName("new-group");
        groupInfo.setConsumers(5);
        groupInfo.setPending(20);
        groupInfo.setLastDeliveredId(lastId);
        groupInfo.setConsumerList(consumerList);
        groupInfo.setTotalConsumed(5000L);

        assertEquals("new-group", groupInfo.getName());
        assertEquals(5, groupInfo.getConsumers());
        assertEquals(20, groupInfo.getPending());
        assertEquals(lastId, groupInfo.getLastDeliveredId());
        assertEquals(consumerList, groupInfo.getConsumerList());
        assertEquals(5000L, groupInfo.getTotalConsumed());
    }

    @Test
    void testWithNullValues() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name(null)
                .lastDeliveredId(null)
                .consumerList(null)
                .build();

        assertNull(groupInfo.getName());
        assertNull(groupInfo.getLastDeliveredId());
        assertNull(groupInfo.getConsumerList());
        assertEquals(0, groupInfo.getConsumers());
        assertEquals(0, groupInfo.getPending());
        assertEquals(0, groupInfo.getTotalConsumed());
    }

    @Test
    void testWithEmptyName() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("")
                .build();

        assertEquals("", groupInfo.getName());
    }

    @Test
    void testWithZeroConsumers() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("no-consumers-group")
                .consumers(0)
                .build();

        assertEquals(0, groupInfo.getConsumers());
    }

    @Test
    void testWithZeroPending() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("up-to-date-group")
                .pending(0)
                .build();

        assertEquals(0, groupInfo.getPending());
    }

    @Test
    void testWithZeroTotalConsumed() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("new-group")
                .totalConsumed(0)
                .build();

        assertEquals(0, groupInfo.getTotalConsumed());
    }

    @Test
    void testWithEmptyConsumerList() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("group-with-empty-list")
                .consumerList(Collections.emptyList())
                .build();

        assertNotNull(groupInfo.getConsumerList());
        assertTrue(groupInfo.getConsumerList().isEmpty());
    }

    @Test
    void testLombokDataAnnotation() {
        StreamMessageId lastId = new StreamMessageId(123);
        List<ConsumerInfo> consumers = List.of(
                ConsumerInfo.builder().name("c1").build()
        );

        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder()
                .name("group1")
                .consumers(1)
                .pending(5)
                .lastDeliveredId(lastId)
                .consumerList(consumers)
                .totalConsumed(100L)
                .build();

        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder()
                .name("group1")
                .consumers(1)
                .pending(5)
                .lastDeliveredId(lastId)
                .consumerList(consumers)
                .totalConsumed(100L)
                .build();

        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
    }

    @Test
    void testDifferentConsumerCounts() {
        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder().consumers(0).build();
        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder().consumers(1).build();
        ConsumerGroupInfo g3 = ConsumerGroupInfo.builder().consumers(10).build();
        ConsumerGroupInfo g4 = ConsumerGroupInfo.builder().consumers(100).build();

        assertEquals(0, g1.getConsumers());
        assertEquals(1, g2.getConsumers());
        assertEquals(10, g3.getConsumers());
        assertEquals(100, g4.getConsumers());
    }

    @Test
    void testDifferentPendingCounts() {
        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder().pending(0).build();
        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder().pending(1).build();
        ConsumerGroupInfo g3 = ConsumerGroupInfo.builder().pending(1000).build();
        ConsumerGroupInfo g4 = ConsumerGroupInfo.builder().pending(1000000).build();

        assertEquals(0, g1.getPending());
        assertEquals(1, g2.getPending());
        assertEquals(1000, g3.getPending());
        assertEquals(1000000, g4.getPending());
    }

    @Test
    void testDifferentTotalConsumed() {
        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder().totalConsumed(0L).build();
        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder().totalConsumed(1L).build();
        ConsumerGroupInfo g3 = ConsumerGroupInfo.builder().totalConsumed(10000L).build();
        ConsumerGroupInfo g4 = ConsumerGroupInfo.builder().totalConsumed(Long.MAX_VALUE).build();

        assertEquals(0L, g1.getTotalConsumed());
        assertEquals(1L, g2.getTotalConsumed());
        assertEquals(10000L, g3.getTotalConsumed());
        assertEquals(Long.MAX_VALUE, g4.getTotalConsumed());
    }

    @Test
    void testWithSingleConsumer() {
        List<ConsumerInfo> consumers = List.of(
                ConsumerInfo.builder().name("consumer1").pending(10).idleTime(5000).build()
        );

        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("single-consumer-group")
                .consumers(1)
                .consumerList(consumers)
                .build();

        assertEquals(1, groupInfo.getConsumers());
        assertEquals(1, groupInfo.getConsumerList().size());
        assertEquals("consumer1", groupInfo.getConsumerList().get(0).getName());
    }

    @Test
    void testWithMultipleConsumers() {
        List<ConsumerInfo> consumers = List.of(
                ConsumerInfo.builder().name("consumer1").pending(5).idleTime(1000).build(),
                ConsumerInfo.builder().name("consumer2").pending(3).idleTime(2000).build(),
                ConsumerInfo.builder().name("consumer3").pending(8).idleTime(3000).build()
        );

        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("multi-consumer-group")
                .consumers(3)
                .consumerList(consumers)
                .build();

        assertEquals(3, groupInfo.getConsumers());
        assertEquals(3, groupInfo.getConsumerList().size());
        assertEquals("consumer1", groupInfo.getConsumerList().get(0).getName());
        assertEquals("consumer2", groupInfo.getConsumerList().get(1).getName());
        assertEquals("consumer3", groupInfo.getConsumerList().get(2).getName());
    }

    @Test
    void testDifferentLastDeliveredIds() {
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(100);
        StreamMessageId id3 = new StreamMessageId(1234567890);

        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder().lastDeliveredId(id1).build();
        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder().lastDeliveredId(id2).build();
        ConsumerGroupInfo g3 = ConsumerGroupInfo.builder().lastDeliveredId(id3).build();

        assertEquals(id1, g1.getLastDeliveredId());
        assertEquals(id2, g2.getLastDeliveredId());
        assertEquals(id3, g3.getLastDeliveredId());
    }

    @Test
    void testMultipleConsumerGroupInfos() {
        ConsumerGroupInfo g1 = ConsumerGroupInfo.builder()
                .name("group1")
                .consumers(2)
                .pending(10)
                .totalConsumed(1000L)
                .build();

        ConsumerGroupInfo g2 = ConsumerGroupInfo.builder()
                .name("group2")
                .consumers(3)
                .pending(20)
                .totalConsumed(2000L)
                .build();

        assertEquals("group1", g1.getName());
        assertEquals("group2", g2.getName());
        assertEquals(2, g1.getConsumers());
        assertEquals(3, g2.getConsumers());
        assertEquals(1000L, g1.getTotalConsumed());
        assertEquals(2000L, g2.getTotalConsumed());
    }

    @Test
    void testWithSpecialCharactersInName() {
        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("group:with:colons")
                .build();

        assertEquals("group:with:colons", groupInfo.getName());
    }

    @Test
    void testConsumerListModification() {
        List<ConsumerInfo> consumers = new ArrayList<>();
        consumers.add(ConsumerInfo.builder().name("c1").build());

        ConsumerGroupInfo groupInfo = ConsumerGroupInfo.builder()
                .name("test-group")
                .consumerList(consumers)
                .build();

        assertEquals(1, groupInfo.getConsumerList().size());

        // Modify the list
        groupInfo.getConsumerList().add(ConsumerInfo.builder().name("c2").build());

        assertEquals(2, groupInfo.getConsumerList().size());
    }
}
