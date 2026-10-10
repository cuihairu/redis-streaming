package io.github.cuihairu.redis.streaming.runtime.redis.control;

import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis happy path for {@link RedisJobLauncher}: a trivial pipeline factory
 * (MQ source + no-op sink) is launched via {@code executeAsync()} and cancelled
 * through the returned {@link RedisJobClient}. Skips when Redis is unreachable.
 */
@Tag("integration")
class RedisJobLauncherIntegrationTest {

    @Test
    void launchesTrivialPipelineAndCancelsIt() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        if (!reachable(redisUrl)) {
            return;
        }
        RedissonClient redis = createClient();
        try {
            RedisJobLauncher launcher = new RedisJobLauncher(redis);
            String topic = "it-launcher-" + UUID.randomUUID().toString().substring(0, 8);
            launcher.registerFactory("trivial", (spec, env) ->
                    env.fromMqTopic(topic, "grp-" + spec.getJobName())
                            .addSink(m -> { }));

            JobSpec spec = JobSpec.builder()
                    .jobName("launcher-it")
                    .pipelineFactory("trivial")
                    .config(Map.of())
                    .parallelism(1)
                    .version(1L)
                    .specHash("h")
                    .build();

            try (RedisJobClient job = launcher.launch(spec)) {
                assertNotNull(job);
            }
        } finally {
            redis.shutdown();
        }
    }

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
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
