package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for {@link RedisJobLauncher} factory resolution and env wiring
 * (with a mocked Redisson client — the no-pipelines {@code executeAsync} guard
 * fires before any Redis call, so no server is needed).
 */
class RedisJobLauncherTest {

    private RedissonClient redisson;
    private RedisJobLauncher launcher;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        launcher = new RedisJobLauncher(redisson);
    }

    private JobSpec spec(String name, String factory, int parallelism) {
        return JobSpec.builder()
                .jobName(name)
                .pipelineFactory(factory)
                .config(Map.of())
                .parallelism(parallelism)
                .version(1L)
                .specHash("h")
                .build();
    }

    @Test
    void unknownFactoryThrowsWithFactoryAndJobName() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> launcher.launch(spec("j1", "ghost", 2)));
        assertTrue(e.getMessage().contains("ghost"));
        assertTrue(e.getMessage().contains("j1"));
    }

    @Test
    void registerFactoryReplacesPreviousEntry() throws Exception {
        AtomicReference<JobSpec> seen = new AtomicReference<>();
        launcher.registerFactory("f", (s, env) -> { throw new IllegalStateException("first"); });
        launcher.registerFactory("f", (s, env) -> seen.set(s));

        assertThrows(IllegalStateException.class, () -> launcher.launch(spec("j1", "f", 1)));
        // replacement factory ran (spec captured) and still fails on the empty-pipeline guard
        assertThrows(IllegalStateException.class, () -> launcher.launch(spec("j1", "f", 1)));
        assertNotNull(seen.get());
    }

    @Test
    void nullFactoryOrNameRejected() {
        assertThrows(NullPointerException.class, () -> launcher.registerFactory(null, (s, env) -> { }));
        assertThrows(NullPointerException.class, () -> launcher.registerFactory("f", null));
    }

    @Test
    void factoryReceivesSpecAndFreshEnvironmentBeforeExecute() throws Exception {
        AtomicReference<JobSpec> seenSpec = new AtomicReference<>();
        AtomicReference<RedisStreamExecutionEnvironment> seenEnv = new AtomicReference<>();
        launcher.registerFactory("f", (s, env) -> {
            seenSpec.set(s);
            seenEnv.set(env);
        });

        JobSpec s = spec("j1", "f", 4);
        // factory registered nothing -> executeAsync guard fires before Redis usage
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> launcher.launch(s));
        assertTrue(e.getMessage().contains("No pipelines registered"));
        assertSame(s, seenSpec.get());
        assertNotNull(seenEnv.get());
    }

    @Test
    void factoryReceivesResolvedSecretReferencesAndCallerSpecStaysUntouched() throws Exception {
        String prop = "c2s.launcher.token";
        System.setProperty(prop, "resolved-value");
        try {
            AtomicReference<JobSpec> seen = new AtomicReference<>();
            launcher.registerFactory("f", (s, env) -> seen.set(s));

            JobSpec s = JobSpec.builder()
                    .jobName("j1").pipelineFactory("f")
                    .config(new java.util.LinkedHashMap<>(Map.of(
                            "secret.token", "sys:" + prop, "k", "v")))
                    .parallelism(1).version(1L).build();

            // factory registered nothing -> executeAsync guard fires, but the factory ran
            assertThrows(IllegalStateException.class, () -> launcher.launch(s));
            assertEquals("resolved-value", seen.get().getConfig().get("secret.token"));
            assertEquals("v", seen.get().getConfig().get("k"));
            // the caller's spec keeps holding the reference
            assertEquals("sys:" + prop, s.getConfig().get("secret.token"));
        } finally {
            System.clearProperty(prop);
        }
    }

    @Test
    void missingSecretReferenceFailsTheLaunchBeforeAnyRedisUse() {
        launcher.registerFactory("f", (s, env) -> { });
        JobSpec s = JobSpec.builder()
                .jobName("j1").pipelineFactory("f")
                .config(Map.of("secret.token", "sys:c2s.launcher.missing"))
                .parallelism(1).version(1L).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> launcher.launch(s));
        assertTrue(e.getMessage().contains("secret.token"));
    }

    @Test
    void configForCarriesTheDeclaredInflightCapOntoMqOptions() {
        JobSpec s = spec("j1", "f", 2).toBuilder().tenant("acme").maxInFlight(3).build();

        RedisRuntimeConfig config = RedisJobLauncher.configFor(s);

        assertEquals("j1", config.getJobName());
        assertEquals(2, config.getPipelineParallelism());
        assertEquals(3, config.getMqOptions().getMaxInFlight());
        assertEquals("acme", config.getMqOptions().getTenant());
    }

    @Test
    void configForKeepsInflightOpenWhenTheSpecDeclaresNone() {
        RedisRuntimeConfig config = RedisJobLauncher.configFor(spec("j1", "f", 1));

        assertEquals(0, config.getMqOptions().getMaxInFlight());
    }
}
