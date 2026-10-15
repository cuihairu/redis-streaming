package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterService;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The MQ auto-configuration beans must scope their keys by {@code redis-streaming.mq.tenant}
 * themselves, instead of depending on the process-wide static tenant that
 * {@code MessageQueueFactory}'s constructor happens to set (bean creation order is not a
 * contract, and a user-provided factory bean never sets it at all).
 *
 * <p>Mirrors {@link TenantPropertyWiringTest}, which covers the registry/config beans only.</p>
 */
class MqTenantWiringTest {

    private static final String TENANT = "acme";

    private final RedisStreamingMqAutoConfiguration cfg = new RedisStreamingMqAutoConfiguration();
    private final RedissonClient client = mock(RedissonClient.class);

    @BeforeEach
    void resetStaticTenant() {
        // Fresh-JVM static state: without it the assertions below could pass by leakage
        // from another test that built a factory with a tenant.
        StreamKeys.configure("streaming:mq", "stream:topic", null);
    }

    private MqOptions optionsWithTenant(String tenant) {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getMq().setTenant(tenant);
        return cfg.mqOptions(props);
    }

    private static String tenantOf(Object target) {
        for (String name : new String[]{"keyView", "keys"}) {
            try {
                Field f = target.getClass().getDeclaredField(name);
                f.setAccessible(true);
                if (f.get(target) instanceof StreamKeys keys) {
                    return keys.getTenant();
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // try the next field name
            }
        }
        throw new AssertionError("no StreamKeys view on " + target.getClass().getName());
    }

    @Test
    void adminBeanScopesKeysByTheMqTenant() {
        MqOptions opts = optionsWithTenant(TENANT);
        MessageQueueAdmin admin = cfg.messageQueueAdmin(client, opts);
        assertEquals(TENANT, tenantOf(admin));
    }

    @Test
    void deadLetterServiceBeanScopesKeysByTheMqTenant() {
        MqOptions opts = optionsWithTenant(TENANT);
        DeadLetterService service = cfg.deadLetterService(client, null, opts);
        assertEquals(TENANT, tenantOf(service));
    }

    @Test
    void deadLetterConsumerBeanScopesKeysByTheMqTenant() {
        MqOptions opts = optionsWithTenant(TENANT);
        Object consumer = cfg.deadLetterConsumer(client, opts, null);
        assertEquals(TENANT, tenantOf(consumer));
    }

    @Test
    void deadLetterQueueManagerBeanScopesKeysByTheMqTenant() {
        MqOptions opts = optionsWithTenant(TENANT);
        Object manager = cfg.deadLetterQueueManager(client, opts);
        assertEquals(TENANT, tenantOf(manager));
    }

    @Test
    void defaultTenantKeepsTheLegacyKeyShape() {
        MqOptions opts = optionsWithTenant("default");
        assertEquals("default", tenantOf(cfg.messageQueueAdmin(client, opts)));
        assertEquals("stream:topic:t:p:0",
                ((StreamKeys) viewOf(cfg.messageQueueAdmin(client, opts))).partitionStreamKey("t", 0));
    }

    private static Object viewOf(Object target) {
        for (String name : new String[]{"keyView", "keys"}) {
            try {
                Field f = target.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v instanceof StreamKeys) {
                    return v;
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // try the next field name
            }
        }
        throw new AssertionError("no StreamKeys view on " + target.getClass().getName());
    }
}
