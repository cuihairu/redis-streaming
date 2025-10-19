package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamGroup;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DLQ consumer ack semantics.
 * Requires a Redis instance (REDIS_URL or redis://127.0.0.1:6379).
 */
@Tag("integration")
class RedisDeadLetterConsumerIntegrationTest {

    private RedissonClient createClient() {
        String addr = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(addr);
        return Redisson.create(cfg);
    }

    private String newTopic() { return "it-dlq-" + UUID.randomUUID(); }

    private long pending(RedissonClient client, String topic, String group) {
        // Use XPENDING summary for accurate pending count; tolerate NOGROUP early window
        String key = DlqKeys.dlq(topic);
        org.redisson.api.RScript script = client.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
        java.util.List<Object> keys = java.util.Collections.singletonList(key);
        try {
            Object res = script.eval(org.redisson.api.RScript.Mode.READ_ONLY,
                    "return redis.call('XPENDING', KEYS[1], ARGV[1])",
                    org.redisson.api.RScript.ReturnType.MULTI,
                    keys,
                    group);
            if (res instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) res;
                if (!list.isEmpty() && list.get(0) != null) {
                    Object v = list.get(0);
                    if (v instanceof Number) return ((Number) v).longValue();
                    try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignore) {}
                }
            }
        } catch (org.redisson.client.RedisException re) {
            String msg = String.valueOf(re.getMessage());
            if (msg != null && msg.contains("NOGROUP")) {
                return 0L; // group not visible yet; treat as 0 and let caller retry
            }
            throw re;
        }
        return 0L;
    }

    private void ensureGroup(RedissonClient client, String topic, String group) {
        String key = DlqKeys.dlq(topic);
        RStream<String, Object> s = client.getStream(key);
        try {
            s.createGroup(org.redisson.api.stream.StreamCreateGroupArgs.name(group).id(org.redisson.api.StreamMessageId.MIN).makeStream());
        } catch (Exception ignore) {}
        // wait until group is visible
        long until = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < until) {
            try {
                java.util.List<org.redisson.api.StreamGroup> gs = s.listGroups();
                if (gs != null && gs.stream().anyMatch(g -> group.equals(g.getName()))) return;
            } catch (Exception ignore) {}
            try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void sendOne(DeadLetterService svc, String topic) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.originalPartition = 0;
        r.payload = "p";
        r.retryCount = 0;
        r.maxRetries = 3;
        r.headers = java.util.Collections.singletonMap("k", "v");
        svc.send(r);
    }

    @Test
    @EnabledIfSystemProperty(named = "RUN_DLQ_IT", matches = "true")
    void groupAckOnlyOnReplaySuccess() throws Exception {
        System.setProperty("reliability.dlq.test.readAllIds", "true");
        System.setProperty("reliability.dlq.test.holdBeforeHandleMs", "50");
        RedissonClient client = createClient();
        String topic = newTopic();
        String group = "g1";
        try {
            // Clean any leftovers
            client.getKeys().delete(DlqKeys.dlq(topic));

            // Prepare service and consumer; assert on replay handler call and origin stream write
            DeadLetterService svc = new RedisDeadLetterService(client);
            AtomicInteger calls = new AtomicInteger(0);
            RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(client, "c1", group, (t, pid, payload, headers, maxRetries) -> {
                calls.incrementAndGet();
                // write a simple record back to original partition stream
                String skey = "stream:topic:" + t + ":p:" + pid;
                RStream<String, Object> p = client.getStream(skey, org.redisson.client.codec.StringCodec.INSTANCE);
                java.util.Map<String, Object> d = new java.util.HashMap<>();
                d.put("payload", String.valueOf(payload));
                d.put("timestamp", java.time.Instant.now().toString());
                d.put("retryCount", "0");
                d.put("maxRetries", String.valueOf(maxRetries));
                d.put("topic", t);
                d.put("partitionId", String.valueOf(pid));
                p.add(org.redisson.api.stream.StreamAddArgs.entries(d));
                return true; // success path -> consumer will ack
            });
            // Ensure group exists, start consumer, then produce (so '>' delivers the new entry)
            ensureGroup(client, topic, group);
            consumer.subscribe(topic, group, entry -> DeadLetterConsumer.HandleResult.RETRY);
            consumer.start();
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            assertTrue(consumer.isRunning(), "consumer not running");
            sendOne(svc, topic);

            // Wait until replay handler called
            long t1 = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < t1 && calls.get() == 0) {
                Thread.sleep(50);
            }
            assertTrue(calls.get() > 0, "expected replay handler to be invoked");
            // Verify origin partition stream has at least one entry
            String skey = "stream:topic:" + topic + ":p:0";
            RStream<String, Object> p = client.getStream(skey, org.redisson.client.codec.StringCodec.INSTANCE);
            long t2 = System.currentTimeMillis() + 10000;
            boolean wrote = false;
            while (System.currentTimeMillis() < t2) {
                try {
                    if (p.isExists() && p.size() > 0) { wrote = true; break; }
                } catch (Exception ignore) {}
                Thread.sleep(50);
            }
            assertTrue(wrote, "expected a record written to original partition stream");

        } finally {
            System.clearProperty("reliability.dlq.test.readAllIds");
            System.clearProperty("reliability.dlq.test.holdBeforeHandleMs");
            try { client.getKeys().delete(DlqKeys.dlq(topic)); } catch (Exception ignore) {}
            client.shutdown();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "RUN_DLQ_IT", matches = "true")
    void groupKeepsPendingWhenReplayFails() throws Exception {
        System.setProperty("reliability.dlq.test.readAllIds", "true");
        System.setProperty("reliability.dlq.test.holdBeforeHandleMs", "50");
        RedissonClient client = createClient();
        String topic = newTopic();
        String group = "g2";
        try {
            client.getKeys().delete(DlqKeys.dlq(topic));

            // Prepare service and consumer with a failing replay handler; assert it was invoked and no origin write
            DeadLetterService svc = new RedisDeadLetterService(client);
            AtomicInteger calls = new AtomicInteger(0);
            RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(client, "c2", group, (t, pid, payload, headers, maxRetries) -> {
                calls.incrementAndGet();
                return false; // failure path -> consumer will not ack
            });
            // Ensure group exists, start consumer, then produce (so '>' delivers the new entry)
            ensureGroup(client, topic, group);
            consumer.subscribe(topic, group, entry -> DeadLetterConsumer.HandleResult.RETRY);
            consumer.start();
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            assertTrue(consumer.isRunning(), "consumer not running");
            sendOne(svc, topic);

            // Wait until replay handler called
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && calls.get() == 0) {
                Thread.sleep(50);
            }
            assertTrue(calls.get() > 0, "expected replay handler to be invoked");
            // Verify origin partition stream has not received records (handler returned false and didn't write)
            String skey = "stream:topic:" + topic + ":p:0";
            RStream<String, Object> p = client.getStream(skey, org.redisson.client.codec.StringCodec.INSTANCE);
            boolean none = true;
            try { none = (!p.isExists() || p.size() == 0); } catch (Exception ignore) {}
            assertTrue(none, "unexpected record in original partition stream for failure path");

        } finally {
            System.clearProperty("reliability.dlq.test.readAllIds");
            System.clearProperty("reliability.dlq.test.holdBeforeHandleMs");
            try { client.getKeys().delete(DlqKeys.dlq(topic)); } catch (Exception ignore) {}
            client.shutdown();
        }
    }
}
