package io.github.cuihairu.redis.streaming.reliability.migration;

import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Direction;
import io.github.cuihairu.redis.streaming.reliability.migration.TenantKeyFamilies.Family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the tenant key migration command line: argument parsing, exit
 * codes, the dry-run default and the swallow refusal that keeps a subset migration from
 * moving a neighbour family's keys. The client factory and the streams are injected, so
 * none of these need a running Redis.
 */
class TenantKeyMigrationCliTest {

    private RKeys keys;
    private RedissonClient client;
    private Function<String, RedissonClient> factory;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() {
        keys = mock(RKeys.class);
        client = mock(RedissonClient.class);
        when(client.getKeys()).thenReturn(keys);
        factory = url -> client;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private static PrintStream sink(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private String shown(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    // ===== parsing =====

    @Test
    void parseKeepsDryRunAndAllFamiliesAsTheDefaults() {
        TenantKeyMigrationCli.Options options = TenantKeyMigrationCli.parse(new String[]{"--tenant", "acme"});

        assertFalse(options.apply());
        assertFalse(options.allowOverlap());
        assertEquals(TenantKeyMigrationCli.DEFAULT_REDIS_URL, options.redisUrl());
        assertEquals("acme", options.tenant());
        assertEquals(Direction.ADOPT, options.direction());
        assertTrue(options.families().isEmpty());
        assertTrue(options.prefixes().isEmpty());
        assertEquals(100, options.scanCount());
    }

    @Test
    void parseAcceptsEveryFlag() {
        TenantKeyMigrationCli.Options options = TenantKeyMigrationCli.parse(new String[]{
                "--tenant", "acme", "--apply", "--dir", "out",
                "--family", "mq-data", "--family", "checkpoint",
                "--prefix", "mq-data=myapp:stream", "--redis", "redis://prod:6379",
                "--scan-count", "500"});

        assertTrue(options.apply());
        assertEquals(Direction.EXIT, options.direction());
        assertEquals(List.of(Family.MQ_DATA, Family.CHECKPOINT), options.families());
        assertEquals(Map.of(Family.MQ_DATA, "myapp:stream"), options.prefixes());
        assertEquals("redis://prod:6379", options.redisUrl());
        assertEquals(500, options.scanCount());
    }

    @Test
    void parseRejectsAnUnknownOption() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--tenant", "acme", "--force"}));
        assertTrue(thrown.getMessage().contains("unknown option: --force"));
    }

    @Test
    void parseRejectsAMissingTenant() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--apply"}));
        assertTrue(thrown.getMessage().contains("--tenant is required"));
    }

    @Test
    void parseRejectsTheDefaultTenant() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--tenant", "default"}));
        assertTrue(thrown.getMessage().contains("owns no key segment"));
    }

    @Test
    void parseRejectsATenantNameOutsideTheGrammar() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--tenant", "a:b"}));
    }

    @Test
    void parseRejectsAnUnknownFamilyAndDirection() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--family", "topic"}));
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--dir", "outwards"}));
    }

    @Test
    void parseRejectsABadPrefixShape() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--prefix", "mq-data"}));
        assertTrue(thrown.getMessage().contains("--prefix expects family=prefix"));
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--prefix", "=stream"}));
    }

    @Test
    void parseRejectsAMissingValueAndABadScanCount() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--redis"}));
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--scan-count", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> TenantKeyMigrationCli.parse(new String[]{"--scan-count", "-1"}));
    }

    // ===== usage errors never touch redis =====

    @Test
    void aParseErrorExitsTwoAndSkipsRedis() {
        Function<String, RedissonClient> neverFactory = mock(Function.class);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--force"}, neverFactory,
                sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_USAGE, code);
        verify(neverFactory, never()).apply(anyString());
        assertTrue(shown(err).startsWith("error:"));
        assertTrue(shown(err).contains("unknown option: --force"));
        assertTrue(shown(err).contains("usage:"));
        assertTrue(shown(out).isEmpty());
    }

    @Test
    void helpExitsZeroWithoutTouchingRedis() {
        Function<String, RedissonClient> neverFactory = mock(Function.class);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--help"}, neverFactory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(neverFactory, never()).apply(anyString());
        assertTrue(shown(out).startsWith("usage:"));
        assertTrue(shown(out).contains("mq-data"));
        assertTrue(shown(out).contains("--allow-overlap"));
        assertTrue(shown(err).isEmpty());
    }

    // ===== dry run (the default) =====

    @Test
    void dryRunIsTheDefaultAndExitsZero() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of("streaming:mq:meta:x", "stream:topic:orders:p:0"));

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme"}, factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(keys, never()).renamenx(anyString(), anyString());
        String report = shown(out);
        assertTrue(report.startsWith("[dry-run]"));
        assertTrue(report.contains("wouldMove=" + 2));
        assertTrue(report.contains("success=true"));
        assertTrue(report.contains("streaming:mq -> streaming:mq:acme"));
    }

    // ===== apply =====

    @Test
    void applyMovesTheKeysAndExitsZero() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of("streaming:mq:meta:x"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--apply"}, factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(keys).renamenx("streaming:mq:meta:x", "streaming:mq:acme:meta:x");
        String report = shown(out);
        assertTrue(report.startsWith("[applied]"));
        assertTrue(report.contains("moved=1"));
        assertTrue(report.contains("success=true"));
    }

    @Test
    void aNotCleanRunExitsOne() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of("streaming:mq:meta:x"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(false);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--apply"}, factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_MIGRATION_NOT_CLEAN, code);
        String report = shown(out);
        assertTrue(report.contains("success=false"));
        assertTrue(report.contains("target already exists"));
        assertTrue(shown(err).isEmpty());
    }

    @Test
    void aClientFailureExitsOneWithAnErrorLine() {
        when(client.getKeys()).thenThrow(new IllegalStateException("connection reset"));

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme"}, factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_MIGRATION_NOT_CLEAN, code);
        assertTrue(shown(err).contains("connection reset"));
        verify(client).shutdown();
    }

    // ===== subset safety =====

    @Test
    void aSubsetThatWouldSwallowAneighbourIsRefusedBeforeTouchingRedis() {
        Function<String, RedissonClient> neverFactory = mock(Function.class);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--family", "runtime-state"}, neverFactory,
                sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_USAGE, code);
        verify(neverFactory, never()).apply(anyString());
        String error = shown(err);
        assertTrue(error.contains("--allow-overlap"));
        assertTrue(error.contains("runtime-state"));
        assertTrue(error.contains("checkpoint"));
        assertTrue(error.contains("sink-dedup"));
        assertTrue(error.contains("control-plane"));
    }

    @Test
    void allowOverlapProceedsWhereASubsetWouldSwallow() {
        when(keys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of("streaming:runtime:job1:s"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        int code = TenantKeyMigrationCli.run(new String[]{
                "--tenant", "acme", "--apply", "--family", "runtime-state", "--allow-overlap"},
                factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(keys).renamenx("streaming:runtime:job1:s", "streaming:runtime:acme:job1:s");
    }

    @Test
    void theConfigRegistryPairIsRefusedToo() {
        Function<String, RedissonClient> neverFactory = mock(Function.class);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--family", "config"}, neverFactory,
                sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_USAGE, code);
        verify(neverFactory, never()).apply(anyString());
        assertTrue(shown(err).contains("registry"));
    }

    // ===== direction and prefix overrides =====

    @Test
    void exitDirectionMovesTheTenantSegmentOut() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of("stream:topic:acme:orders:p:0"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        int code = TenantKeyMigrationCli.run(
                new String[]{"--tenant", "acme", "--dir", "out", "--apply", "--family", "mq-data"},
                factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(keys).renamenx("stream:topic:acme:orders:p:0", "stream:topic:orders:p:0");
        assertTrue(shown(out).contains("direction=out"));
    }

    @Test
    void aPrefixOverrideReachesTheMovesForACustomizedDeployment() {
        when(keys.getKeys(any(KeysScanOptions.class)))
                .thenReturn(List.of("myapp:stream:orders:p:0"));
        when(keys.renamenx(anyString(), anyString())).thenReturn(true);

        int code = TenantKeyMigrationCli.run(new String[]{
                "--tenant", "acme", "--apply", "--family", "mq-data",
                "--prefix", "mq-data=myapp:stream"}, factory, sink(out), sink(err));

        assertEquals(TenantKeyMigrationCli.EXIT_OK, code);
        verify(keys).renamenx("myapp:stream:orders:p:0", "myapp:stream:acme:orders:p:0");
        assertTrue(shown(out).contains("myapp:stream -> myapp:stream:acme"));
    }

    @Test
    void usageListsTheExitCodes() {
        assertTrue(TenantKeyMigrationCli.usage().contains("0 clean"));
        assertTrue(TenantKeyMigrationCli.usage().contains("2 usage error"));
    }
}
