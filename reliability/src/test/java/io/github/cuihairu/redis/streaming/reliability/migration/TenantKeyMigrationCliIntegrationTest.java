package io.github.cuihairu.redis.streaming.reliability.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line against a real Redis, on a prefix that no framework module ever
 * writes to: the adoption move, the dry-run no-op, the resumable second run and the
 * rollback direction, with TTL and payload intact.
 */
@Tag("integration")
class TenantKeyMigrationCliIntegrationTest {

    private RedissonClient client;
    private String prefix;

    @BeforeEach
    void setUp() {
        client = Redisson.create(singleServer());
        // a prefix outside every framework namespace, so the run cannot touch live keys
        prefix = "tenant-cli-" + UUID.randomUUID().toString().substring(0, 8) + ":";
    }

    @AfterEach
    void tearDown() {
        client.getKeys().deleteByPattern(prefix + "*");
        client.shutdown();
    }

    private static Config singleServer() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return config;
    }

    /** The CLI builds and closes its own client per run. */
    private Function<String, RedissonClient> factory() {
        return url -> Redisson.create(singleServer());
    }

    private String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = TenantKeyMigrationCli.run(args, factory(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String shown = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertTrue(code == TenantKeyMigrationCli.EXIT_OK,
                "exit code " + code + " for: " + String.join(" ", args) + "\n" + shown);
        return out.toString(StandardCharsets.UTF_8);
    }

    private String[] adoption() {
        return new String[]{
                "--tenant", "acme", "--apply", "--family", "mq-data",
                "--prefix", "mq-data=" + prefix, "--redis", "redis://127.0.0.1:6379",
                "--scan-count", "50"};
    }

    private void seed() {
        client.getBucket(prefix + "last", StringCodec.INSTANCE).set("123");
        client.getKeys().expire(prefix + "last", 60, TimeUnit.SECONDS);
        client.getMap(prefix + "cfg", StringCodec.INSTANCE).put("group", "svc");
    }

    @Test
    void adoptionMovesKeysOfAnyTypeAndKeepsTtlAndPayload() {
        seed();

        String report = run(adoption());

        assertEquals(0, client.getKeys().countExists(prefix + "last", prefix + "cfg"));
        assertEquals("123", client.getBucket(prefix + "acme:last", StringCodec.INSTANCE).get());
        assertEquals("svc", client.getMap(prefix + "acme:cfg", StringCodec.INSTANCE).get("group"));
        long ttl = client.getKeys().remainTimeToLive(prefix + "acme:last");
        assertTrue(ttl > 0 && ttl <= 60_000, "TTL must survive the move, was " + ttl + "ms");
        assertTrue(report.contains("moved=2"));
        assertTrue(report.contains("success=true"));
    }

    @Test
    void aDryRunReportsWithoutMovingAnything() {
        seed();

        String report = run("--tenant", "acme", "--family", "mq-data",
                "--prefix", "mq-data=" + prefix, "--redis", "redis://127.0.0.1:6379");

        assertEquals(2, client.getKeys().countExists(prefix + "last", prefix + "cfg"));
        assertEquals(0, client.getKeys().countExists(prefix + "acme:last", prefix + "acme:cfg"));
        assertTrue(report.startsWith("[dry-run]"));
        assertTrue(report.contains("wouldMove=2"));
    }

    @Test
    void aSecondAdoptionRunIsANoOpBecauseTheTargetIsNestedInTheScan() {
        seed();
        run(adoption());

        String report = run(adoption());

        assertEquals("123", client.getBucket(prefix + "acme:last", StringCodec.INSTANCE).get());
        // no double tenant segment
        assertEquals(0, client.getKeys().countExists(prefix + "acme:acme:last"));
        assertTrue(report.contains("scanned=0"));
    }

    @Test
    void exitMovesTheTenantSegmentBackOut() {
        seed();
        run(adoption());

        String report = run("--tenant", "acme", "--dir", "out", "--apply", "--family", "mq-data",
                "--prefix", "mq-data=" + prefix, "--redis", "redis://127.0.0.1:6379");

        assertEquals("123", client.getBucket(prefix + "last", StringCodec.INSTANCE).get());
        assertEquals(0, client.getKeys().countExists(prefix + "acme:last"));
        assertTrue(report.contains("direction=out"));
        assertTrue(report.contains("success=true"));
    }

    @Test
    void aCollisionStillFailsTheRunAndKeepsBothSides() {
        client.getBucket(prefix + "acme:last", StringCodec.INSTANCE).set("existing");
        client.getBucket(prefix + "last", StringCodec.INSTANCE).set("migrating");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = TenantKeyMigrationCli.run(adoption(), factory(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(TenantKeyMigrationCli.EXIT_MIGRATION_NOT_CLEAN, code);
        assertEquals("existing", client.getBucket(prefix + "acme:last", StringCodec.INSTANCE).get());
        assertEquals("migrating", client.getBucket(prefix + "last", StringCodec.INSTANCE).get());
        assertNotNull(out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("target already exists"));
    }

    @Test
    void aSubsetThatWouldSwallowIsRefusedWithoutConnecting() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = TenantKeyMigrationCli.run(new String[]{
                "--tenant", "acme", "--family", "runtime-state"}, factory(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(TenantKeyMigrationCli.EXIT_USAGE, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--allow-overlap"));
        assertTrue(out.toString(StandardCharsets.UTF_8).isEmpty());
    }
}
