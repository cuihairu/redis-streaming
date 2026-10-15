package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The DLQ admin must scan the same tenant-scoped keys the service writes. A
 * tenant-blind scan mislabels every tenant-scoped DLQ topic (the tenant rides
 * along in the name, e.g. "acme:orders") and the follow-up size/list/replay
 * calls then build a key that does not exist.
 */
class RedisDeadLetterAdminTenantTest {

    @AfterEach
    void resetTenant() {
        StreamKeys.configure("streaming:mq", "stream:topic", null);
    }

    @Test
    void tenantScopedKeysListBareTopicNames() {
        StreamKeys.configure("streaming:mq", "stream:topic", "acme");
        RedissonClient client = mockClient("stream:topic:acme:orders:dlq",
                "stream:topic:acme:billing:dlq");
        RedisDeadLetterService service = new RedisDeadLetterService(client);

        assertEquals(List.of("orders", "billing"), new RedisDeadLetterAdmin(client, service).listTopics());
    }

    @Test
    void defaultTenantKeepsTheLegacyListing() {
        RedissonClient client = mockClient("stream:topic:orders:dlq");
        RedisDeadLetterService service = new RedisDeadLetterService(client);

        assertEquals(List.of("orders"), new RedisDeadLetterAdmin(client, service).listTopics());
    }

    @Test
    void scanPatternAndStartTokenFollowTheServiceKeyView() {
        StreamKeys.configure("streaming:mq", "stream:topic", "acme");
        RedisDeadLetterService service = new RedisDeadLetterService(mockClient());

        assertEquals("stream:topic:acme:*:dlq", RedisDeadLetterAdmin.scanPattern(service.keys()));
        assertEquals("stream:topic:acme:", RedisDeadLetterAdmin.scanStartToken(service.keys()));
    }

    private RedissonClient mockClient(String... dlqKeys) {
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(dlqKeys));
        return client;
    }
}
