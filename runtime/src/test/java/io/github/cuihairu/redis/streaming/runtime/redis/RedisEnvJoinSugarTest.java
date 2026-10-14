package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinWindow;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisJoinEnvelopeCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Redis join sugar ({@link RedisStreamExecutionEnvironment#join}): the U3 three-pipeline
 * orchestration (left and right producers write self-describing envelopes to one join topic, a
 * third consumer pipeline runs the join operator over it).
 *
 * <p>The wire codec and the pipeline-registration topology are unit-tested against a mocked
 * Redisson client (no I/O happens while pipelines are compiled and registered); the end-to-end
 * leg is a tagged integration test over a real Redis.
 */
class RedisEnvJoinSugarTest {

    /** Left payload; a plain POJO exercises the codec's self-describing type round-trip. */
    public static class Order {
        private String orderId;
        private String userId;
        private long amount;

        public Order() {
        }

        public Order(String orderId, String userId, long amount) {
            this.orderId = orderId;
            this.userId = userId;
            this.amount = amount;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public long getAmount() {
            return amount;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Order)) {
                return false;
            }
            Order other = (Order) o;
            return amount == other.amount
                    && java.util.Objects.equals(orderId, other.orderId)
                    && java.util.Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(orderId, userId, amount);
        }
    }

    /** Right payload. */
    public static class User {
        private String userId;
        private String name;

        public User() {
        }

        public User(String userId, String name) {
            this.userId = userId;
            this.name = name;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof User)) {
                return false;
            }
            User other = (User) o;
            return java.util.Objects.equals(userId, other.userId)
                    && java.util.Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, name);
        }
    }

    private static JoinConfig<Order, User, String> innerConfig() {
        return JoinConfig.<Order, User, String>innerJoin(
                Order::getUserId, User::getUserId, JoinWindow.ofSize(Duration.ofSeconds(30)));
    }

    /** Wiring never touches Redis, so a mocked client is enough to compile and register pipelines. */
    private static RedisStreamExecutionEnvironment env() {
        return RedisStreamExecutionEnvironment.create(
                Mockito.mock(RedissonClient.class),
                RedisRuntimeConfig.builder().jobName("join-sugar-ut").build());
    }

    private static DataStream<Order> left(RedisStreamExecutionEnvironment env, String topic) {
        return env.fromMqTopic(topic, topic + "-group").map(m -> new Order("o", "u", 1L));
    }

    private static DataStream<User> right(RedisStreamExecutionEnvironment env, String topic) {
        return env.fromMqTopic(topic, topic + "-group").map(m -> new User("u", "n"));
    }

    private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return cond.getAsBoolean();
    }

    // ---------- wire codec ----------

    @Test
    void wireRoundTripsLeftEnvelope() {
        Envelope<String, Order, User> in = Envelope.forLeft("u1", 1000L, new Order("o1", "u1", 42));

        Envelope<String, Order, User> out = RedisJoinEnvelopeCodec.parse(RedisJoinEnvelopeCodec.toJson(in));

        assertEquals(Envelope.Side.LEFT, out.getSide());
        assertEquals("u1", out.getJoinKey());
        assertEquals(1000L, out.getTimestamp());
        assertEquals(new Order("o1", "u1", 42), out.getLeft());
        assertNull(out.getRight());
    }

    @Test
    void wireRoundTripsRightEnvelope() {
        Envelope<String, Order, User> in = Envelope.forRight("u2", 2000L, new User("u2", "Bob"));

        Envelope<String, Order, User> out = RedisJoinEnvelopeCodec.parse(RedisJoinEnvelopeCodec.toJson(in));

        assertEquals(Envelope.Side.RIGHT, out.getSide());
        assertEquals("u2", out.getJoinKey());
        assertEquals(2000L, out.getTimestamp());
        assertEquals(new User("u2", "Bob"), out.getRight());
        assertNull(out.getLeft());
    }

    @Test
    void wireKeepsPayloadRuntimeTypeInsteadOfDecodingToAMap() {
        Envelope<String, Order, User> in = Envelope.forLeft("u1", 1L, new Order("o1", "u1", 7));

        Envelope<String, Order, User> out = RedisJoinEnvelopeCodec.parse(RedisJoinEnvelopeCodec.toJson(in));

        assertEquals(Order.class, out.getLeft().getClass(),
                "the join operator needs the payload's own class, not Jackson's default Map");
    }

    @Test
    void wireRejectsNullJoinKeyNamingTheOffendingSide() {
        IllegalArgumentException left = assertThrows(IllegalArgumentException.class,
                () -> RedisJoinEnvelopeCodec.toJson(Envelope.forLeft(null, 1L, new Order("o1", "u1", 1))));
        assertTrue(left.getMessage().contains("left"), left.getMessage());

        IllegalArgumentException right = assertThrows(IllegalArgumentException.class,
                () -> RedisJoinEnvelopeCodec.toJson(Envelope.forRight(null, 1L, new User("u1", "Ann"))));
        assertTrue(right.getMessage().contains("right"), right.getMessage());
    }

    // ---------- pipeline topology ----------

    @Test
    void joinRejectsNullArguments() {
        RedisStreamExecutionEnvironment env = env();
        DataStream<Order> left = left(env, "ut-l");
        DataStream<User> right = right(env, "ut-r");
        JoinConfig<Order, User, String> config = innerConfig();

        assertThrows(NullPointerException.class, () -> env.join(null, left, right, config, (o, u) -> ""));
        assertThrows(NullPointerException.class, () -> env.join("ut-j", null, right, config, (o, u) -> ""));
        assertThrows(NullPointerException.class, () -> env.join("ut-j", left, null, config, (o, u) -> ""));
        assertThrows(NullPointerException.class, () -> env.join("ut-j", left, right, null, (o, u) -> ""));
        assertThrows(NullPointerException.class, () -> env.join("ut-j", left, right, config, null));
    }

    @Test
    void joinRejectsBlankJoinTopic() {
        RedisStreamExecutionEnvironment env = env();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> env.join(
                " ", left(env, "ut-l"), right(env, "ut-r"), innerConfig(), (o, u) -> ""));
        assertTrue(e.getMessage().contains("joinTopic"), e.getMessage());
    }

    @Test
    void joinValidatesTheConfigBeforeRegisteringAnything() {
        RedisStreamExecutionEnvironment env = env();
        JoinConfig<Order, User, String> invalid = JoinConfig.<Order, User, String>builder()
                .leftKeySelector(Order::getUserId)
                .rightKeySelector(User::getUserId)
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> env.join(
                "ut-j", left(env, "ut-l"), right(env, "ut-r"), invalid, (o, u) -> ""));
        assertTrue(e.getMessage().contains("Join type"), e.getMessage());
    }

    @Test
    void joinTwiceOnTheSameJoinTopicCollidesOnTheConsumerPipeline() {
        RedisStreamExecutionEnvironment env = env();
        env.join("ut-join-topic", left(env, "ut-l1"), right(env, "ut-r1"), innerConfig(), (o, u) -> "")
                .addSink(v -> {
                });

        // the consumer's stable source id is "join-" + joinTopic, so re-using the topic trips
        // the duplicate-sourceId guard first — before the (topic, group) guard below it
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> env.join(
                "ut-join-topic", left(env, "ut-l2"), right(env, "ut-r2"), innerConfig(), (o, u) -> "")
                .addSink(v -> {
                }));

        assertTrue(e.getMessage().contains("Duplicate sourceId/streamId"), e.getMessage());
        assertTrue(e.getMessage().contains("join-ut-join-topic"), e.getMessage());
    }

    @Test
    void joinTopicOutsideTheSourceIdCharsetIsRejected() {
        RedisStreamExecutionEnvironment env = env();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> env.join(
                "ut join topic", left(env, "ut-l"), right(env, "ut-r"), innerConfig(), (o, u) -> ""));
        assertTrue(e.getMessage().contains("ut join topic"), e.getMessage());
    }

    @Test
    void joinTwiceOnTheSameLeftSourceCollidesOnTheProducerPipeline() {
        RedisStreamExecutionEnvironment env = env();
        DataStream<Order> left = left(env, "ut-left");
        DataStream<User> right = right(env, "ut-right");
        env.join("ut-join-a", left, right, innerConfig(), (o, u) -> "").addSink(v -> {
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> env.join(
                "ut-join-b", left, right, innerConfig(), (o, u) -> ""));

        assertTrue(e.getMessage().contains("Duplicate pipeline"), e.getMessage());
        assertTrue(e.getMessage().contains("ut-left"), e.getMessage());
    }

    // ---------- end to end ----------

    @Test
    @Tag("integration")
    void redisEngineJoinSugarOrchestratesThreePipelines() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return; // gated on a reachable Redis, like the other runtime integration legs
        }
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(cfg);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String leftTopic = "it-join-sugar-l-" + tag;
        String rightTopic = "it-join-sugar-r-" + tag;
        String joinTopic = "it-join-sugar-j-" + tag;
        List<String> out = new CopyOnWriteArrayList<>();
        RedisJobClient job = null;
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis,
                    RedisRuntimeConfig.builder().jobName("it-join-sugar-" + tag).build());

            DataStream<Order> left = env.fromMqTopic(leftTopic, "orders-group")
                    .map(m -> new Order(String.valueOf(m.getPayload()).split("\\|")[0],
                            String.valueOf(m.getPayload()).split("\\|")[1], 1L));
            DataStream<User> right = env.fromMqTopic(rightTopic, "users-group")
                    .map(m -> {
                        String[] p = String.valueOf(m.getPayload()).split("\\|");
                        return new User(p[0], p[1]);
                    });

            env.join(joinTopic, left, right, innerConfig(), (order, user) -> order.getOrderId() + "+" + user.getName())
                    .addSink(out::add);
            job = env.executeAsync();

            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            producer.send(leftTopic, "u1", "o1|u1").get(10, TimeUnit.SECONDS);
            producer.send(rightTopic, "u1", "u1|Alice").get(10, TimeUnit.SECONDS);
            producer.send(leftTopic, "u2", "o2|u2").get(10, TimeUnit.SECONDS);
            producer.send(rightTopic, "u2", "u2|Bob").get(10, TimeUnit.SECONDS);
            producer.close();

            assertTrue(await(() -> out.containsAll(List.of("o1+Alice", "o2+Bob")), 30_000),
                    "expected both join pairs across the join topic, got " + out);
        } finally {
            if (job != null) {
                job.close();
            }
            try {
                redis.getKeys().deleteByPattern("*" + tag + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    private static boolean reachable(String url) {
        try (java.net.Socket s = new java.net.Socket()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("://([^/:]+):(\\d+)").matcher(url);
            if (!m.find()) {
                return false;
            }
            s.connect(new java.net.InetSocketAddress(m.group(1), Integer.parseInt(m.group(2))), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
