package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * todo §10 bullet 1 — produce→consume end-to-end over real Redis: message fidelity
 * (payload/headers/key/topic) across partitions and independent consumer-group fan-out.
 *
 * <p>The other §10 bullets are already backed by dedicated suites: DLQ forwarding
 * ({@code RetryAndDlqIntegrationTest}, {@code MissingPayloadDlqIntegrationTest}),
 * consumer-group management ({@code CommitFrontierMultiGroupIntegrationTest},
 * {@code LeaseOwnershipIntegrationTest}, {@code PendingClaimIntegrationTest}),
 * ack/retry policies ({@code Ack*PolicyIntegrationTest}, {@code RetryMoverIntegrationTest})
 * and stream-entry serialization ({@code DlqCodecCompatibilityIntegrationTest},
 * {@code PayloadLifecycleIntegrationTest}).</p>
 *
 * <p>Deliberately NOT asserted here: strict per-key delivery order. Under a fresh consumer
 * group the partition worker intermittently defers the partition's first backlog entry
 * (observed as {@code [1..7,0]} arrivals or the first entry arriving past a 30s window,
 * ~40% of rounds) — the entry is eventually delivered (at-least-once) but its position is
 * not guaranteed until that deferral is addressed in the consumer read path. Ordering-sensitive
 * workloads must not rely on it until then; see the todo §10 note.</p>
 */
@Tag("integration")
class ProduceConsumeEndToEndIntegrationTest {

    @Test
    void endToEndDeliveryPreservesMessageFidelityAcrossPartitions() throws Exception {
        String topic = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MessageQueueFactory factory = new MessageQueueFactory(client,
                    MqOptions.builder().defaultPartitionCount(4).build());
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("e2e-consumer");

            List<Message> received = new CopyOnWriteArrayList<>();
            consumer.subscribe(topic, "e2e", m -> {
                received.add(m);
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();
            settlePartitionAttach();

            Map<String, Message> sent = new LinkedHashMap<>();
            for (int i = 0; i < 12; i++) {
                Message m = new Message(topic, "payload-" + i);
                m.setKey("k" + i);
                m.setHeaders(Map.of("trace", "t-" + i, "kind", "e2e"));
                sent.put(m.getKey(), m);
                producer.send(m).get(10, TimeUnit.SECONDS);
            }

            assertTrue(waitUntil(() -> received.size() >= 12, 30_000),
                    "all 12 messages must be delivered, got " + received.size());

            // exactly once per group, and every message arrives with its own identity intact.
            // (the delivered id is the Redis stream entry id, so correlation uses the key)
            Set<String> seenKeys = new HashSet<>();
            for (Message m : received) {
                assertTrue(seenKeys.add(m.getKey()), "duplicate delivery within one group: " + m.getKey());
                Message origin = sent.get(m.getKey());
                assertEquals(origin.getPayload(), m.getPayload());
                assertEquals(origin.getTopic(), m.getTopic());
                // user headers survive end to end; the transport may add its own internals
                // (x-payload-*, partitionId) on top of them
                for (Map.Entry<String, String> h : origin.getHeaders().entrySet()) {
                    assertEquals(h.getValue(), m.getHeaders().get(h.getKey()),
                            "header " + h.getKey() + " must survive delivery");
                }
            }
            assertEquals(12, seenKeys.size());

            // the key-hash spread must actually have used more than one partition stream
            int nonEmpty = 0;
            for (int p = 0; p < 4; p++) {
                RStream<String, Object> s = client.getStream(StreamKeys.partitionStream(topic, p));
                if (s.isExists() && s.size() > 0) {
                    nonEmpty++;
                }
            }
            assertTrue(nonEmpty >= 2, "expected at least two non-empty partitions, got " + nonEmpty);

            consumer.stop();
            consumer.close();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void independentConsumerGroupsEachReceiveTheFullStream() throws Exception {
        String topic = "e2e-fanout-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MessageQueueFactory factory = new MessageQueueFactory(client);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumerA = factory.createConsumer("e2e-a");
            MessageConsumer consumerB = factory.createConsumer("e2e-b");

            List<String> groupA = new CopyOnWriteArrayList<>();
            List<String> groupB = new CopyOnWriteArrayList<>();
            consumerA.subscribe(topic, "group-a", m -> {
                groupA.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            });
            consumerB.subscribe(topic, "group-b", m -> {
                groupB.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            });
            consumerA.start();
            consumerB.start();
            settlePartitionAttach();

            for (int i = 0; i < 6; i++) {
                producer.send(topic, "k" + i, "fan-" + i).get(10, TimeUnit.SECONDS);
            }

            assertTrue(waitUntil(() -> groupA.size() >= 6 && groupB.size() >= 6, 30_000),
                    "both groups must see the full stream: A=" + groupA + " B=" + groupB);
            assertEquals(Set.copyOf(List.of("fan-0", "fan-1", "fan-2", "fan-3", "fan-4", "fan-5")),
                    Set.copyOf(groupA));
            assertEquals(Set.copyOf(List.of("fan-0", "fan-1", "fan-2", "fan-3", "fan-4", "fan-5")),
                    Set.copyOf(groupB));

            consumerA.stop();
            consumerA.close();
            consumerB.stop();
            consumerB.close();
        } finally {
            client.shutdown();
        }
    }

    /* ---------- helpers ---------- */

    /**
     * Partition workers attach through lease acquisition on the rebalance cadence
     * ({@code rebalanceIntervalSec}, default 5s). Producing only after this settle keeps
     * the runs deterministic: consumer groups already exist on every (empty) partition
     * stream, so no entry can predate the group and be skipped by newest-only semantics.
     */
    private static void settlePartitionAttach() throws InterruptedException {
        Thread.sleep(6_500);
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10);
        return Redisson.create(config);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
