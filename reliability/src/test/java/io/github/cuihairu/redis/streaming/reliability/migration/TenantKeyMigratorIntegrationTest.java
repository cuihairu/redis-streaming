package io.github.cuihairu.redis.streaming.reliability.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis checks for the tenant key migration engine: the SCAN pattern reaches
 * exactly the source family, RENAME keeps payload and TTL intact, existing targets
 * are never overwritten, and a second run is a clean no-op.
 */
@Tag("integration")
class TenantKeyMigratorIntegrationTest {

    private RedissonClient client;
    private String fromPrefix;
    private String toPrefix;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        client = Redisson.create(config);
        String root = "tenant-migr-" + UUID.randomUUID().toString().substring(0, 8) + ":";
        fromPrefix = root;
        toPrefix = root + "acme:";
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern(fromPrefix + "*");
        client.getKeys().deleteByPattern(toPrefix + "*");
        client.shutdown();
    }

    private TenantKeyMigrator migrator() {
        return new TenantKeyMigrator(client);
    }

    private static MigrationOptions apply() {
        return MigrationOptions.builder().dryRun(false).build();
    }

    private TenantKeyMigrator.KeyMove move() {
        return TenantKeyMigrator.KeyMove.of(fromPrefix, toPrefix);
    }

    private void seed() {
        RMap<String, String> map = client.getMap(fromPrefix + "cfg", StringCodec.INSTANCE);
        map.put("group", "svc");
        map.put("env", "prod");

        client.getBucket(fromPrefix + "last", StringCodec.INSTANCE).set("123");
        client.getKeys().expire(fromPrefix + "last", 60, TimeUnit.SECONDS);

        RList<String> list = client.<String>getList(fromPrefix + "orders:0", StringCodec.INSTANCE);
        list.add("m1");
        list.add("m2");
    }

    @Test
    void movesKeysOfAnyTypeAndKeepsTtlAndPayload() {
        seed();

        MigrationReport report = migrator().migrate(List.of(move()), apply());

        assertEquals(3, report.getTotalMoved());
        assertTrue(report.isSuccess(), report.toString());

        assertEquals(0, client.getKeys().countExists(
                fromPrefix + "cfg", fromPrefix + "last", fromPrefix + "orders:0"));
        assertEquals(Map.of("group", "svc", "env", "prod"),
                client.getMap(toPrefix + "cfg", StringCodec.INSTANCE).readAllMap());
        assertEquals("123", client.getBucket(toPrefix + "last", StringCodec.INSTANCE).get());
        assertEquals(List.of("m1", "m2"),
                client.<String>getList(toPrefix + "orders:0", StringCodec.INSTANCE).readAll());

        long ttl = client.getKeys().remainTimeToLive(toPrefix + "last");
        assertTrue(ttl > 0 && ttl <= 60_000, "TTL must survive the move, was " + ttl + "ms");
    }

    @Test
    void dryRunLeavesEveryKeyInPlace() {
        seed();

        MigrationReport report = migrator().migrate(List.of(move()));

        assertEquals(3, report.getTotalScanned());
        assertEquals(3, report.getTotalWouldMove());
        assertTrue(report.isSuccess(), report.toString());

        assertEquals(3, client.getKeys().countExists(
                fromPrefix + "cfg", fromPrefix + "last", fromPrefix + "orders:0"));
        assertEquals(0, client.getKeys().countExists(toPrefix + "cfg", toPrefix + "last", toPrefix + "orders:0"));
    }

    @Test
    void existingTargetIsNeverOverwritten() {
        client.getBucket(toPrefix + "last", StringCodec.INSTANCE).set("existing");
        client.getBucket(fromPrefix + "last", StringCodec.INSTANCE).set("migrating");

        MigrationReport report = migrator().migrate(List.of(move()), apply());

        assertEquals(1, report.getTotalSkipped());
        assertEquals(0, report.getTotalMoved());
        assertFalse(report.isSuccess());
        assertEquals("existing", client.getBucket(toPrefix + "last", StringCodec.INSTANCE).get());
        assertEquals("migrating", client.getBucket(fromPrefix + "last", StringCodec.INSTANCE).get());
    }

    @Test
    void aSecondRunAfterASuccessfulMigrationIsANoOp() {
        seed();
        assertTrue(migrator().migrate(List.of(move()), apply()).isSuccess());

        MigrationReport second = migrator().migrate(List.of(move()), apply());

        assertEquals(0, second.getTotalScanned());
        assertTrue(second.isSuccess(), second.toString());
        assertEquals(3, client.getKeys().countExists(
                toPrefix + "cfg", toPrefix + "last", toPrefix + "orders:0"));
    }

    @Test
    void rollbackDirectionMovesTheTenantSegmentOut() {
        client.getBucket(toPrefix + "last", StringCodec.INSTANCE).set("tenant");

        MigrationReport report = migrator().migrate(
                List.of(TenantKeyMigrator.KeyMove.of(toPrefix, fromPrefix)), apply());

        assertEquals(1, report.getTotalMoved());
        assertTrue(report.isSuccess(), report.toString());
        assertEquals("tenant", client.getBucket(fromPrefix + "last", StringCodec.INSTANCE).get());
        assertEquals(0, client.getKeys().countExists(toPrefix + "last"));
    }

    @Test
    void overlappingFamiliesKeepNarrowKeysInTheirOwnFamily() {
        // The runtime state family "streaming:runtime:" also matches the checkpoint family
        // "streaming:runtime:checkpoint:": both are migrated in one run.
        String stateKey = fromPrefix + "job1:state";
        String ckptFamily = fromPrefix + "checkpoint:";
        String ckptKey = ckptFamily + "job1:1";
        client.getBucket(stateKey, StringCodec.INSTANCE).set("state");
        client.getBucket(ckptKey, StringCodec.INSTANCE).set("checkpoint");

        MigrationReport report = migrator().migrate(List.of(
                TenantKeyMigrator.KeyMove.of(fromPrefix, toPrefix),
                TenantKeyMigrator.KeyMove.of(ckptFamily, ckptFamily + "acme:")), apply());

        assertEquals(2, report.getTotalMoved());
        assertTrue(report.isSuccess(), report.toString());

        // the checkpoint key lands under its own family, not under the state family
        assertEquals("state", client.getBucket(toPrefix + "job1:state", StringCodec.INSTANCE).get());
        assertEquals("checkpoint", client.getBucket(ckptFamily + "acme:job1:1", StringCodec.INSTANCE).get());
        assertEquals(0, client.getKeys().countExists(toPrefix + "checkpoint:job1:1", ckptKey, stateKey));
    }
}
