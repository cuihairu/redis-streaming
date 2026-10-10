package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.runtime.redis.control.ControlPlaneAccessDeniedException;
import io.github.cuihairu.redis.streaming.runtime.redis.control.ControlPlaneAuthorizer;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobAgent;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobControlOp;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobLauncher;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobPipelineFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.control.JobSpec;
import io.github.cuihairu.redis.streaming.runtime.redis.control.RedisJobControlPlane;
import io.github.cuihairu.redis.streaming.runtime.redis.control.RedisJobLauncher;
import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link RedisStreamingRuntimeAutoConfiguration}: opt-in gating, bean
 * overrides and bean-name factory registration (mocked Redisson client — the
 * empty-pipeline executeAsync guard fires before any Redis call).
 */
class RedisStreamingRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisStreamingRuntimeAutoConfiguration.class))
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class));

    @Test
    void disabledByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(RedisJobControlPlane.class);
            assertThat(ctx).doesNotHaveBean(JobAgent.class);
            assertThat(ctx).doesNotHaveBean(ControlPlaneAuthorizer.class);
        });
    }

    @Test
    void controlPlaneEnabledExposesPlaneAndDefaultAuthorizer() {
        runner.withPropertyValues(
                        "redis-streaming.runtime.control-plane.enabled=true",
                        "redis-streaming.runtime.control-plane.prefix=it:cp:",
                        "redis-streaming.runtime.control-plane.audit-max-entries=50",
                        "redis-streaming.runtime.control-plane.history-max-entries=3")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedisJobControlPlane.class);
                    assertThat(ctx.getBean(RedisJobControlPlane.class).prefix()).isEqualTo("it:cp:");
                    // default authorizer allows everything
                    ControlPlaneAuthorizer authorizer = ctx.getBean(ControlPlaneAuthorizer.class);
                    authorizer.authorize("anyone", JobControlOp.SUBMIT, "any-job");
                    // agent stays off unless separately enabled
                    assertThat(ctx).doesNotHaveBean(JobAgent.class);
                    assertThat(ctx).doesNotHaveBean(JobLauncher.class);
                });
    }

    @Test
    void userAuthorizerBeanWins() {
        runner.withUserConfiguration(CustomAuthorizerConfig.class)
                .withPropertyValues("redis-streaming.runtime.control-plane.enabled=true")
                .run(ctx -> {
                    ControlPlaneAuthorizer authorizer = ctx.getBean(ControlPlaneAuthorizer.class);
                    assertThrows(ControlPlaneAccessDeniedException.class,
                            () -> authorizer.authorize("guest", JobControlOp.SUBMIT, "j"));
                });
    }

    @Test
    void agentDisabledWithoutControlPlane() {
        runner.withPropertyValues("redis-streaming.runtime.agent.enabled=true")
                .run(ctx -> {
                    // agent requires the control plane (outer gate), so nothing starts
                    assertThat(ctx).doesNotHaveBean(JobAgent.class);
                });
    }

    @Test
    void agentEnabledStartsReconcilerAndRegistersFactoriesByBeanName() {
        runner.withUserConfiguration(FactoryConfig.class)
                .withPropertyValues(
                        "redis-streaming.runtime.control-plane.enabled=true",
                        "redis-streaming.runtime.control-plane.prefix=it:cp:",
                        "redis-streaming.runtime.agent.enabled=true",
                        "redis-streaming.runtime.agent.instance-id=test-inst",
                        "redis-streaming.runtime.agent.poll-interval=PT1H")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(JobAgent.class);
                    assertThat(ctx).hasSingleBean(JobLauncher.class);

                    // the factory bean was registered under its Spring bean name and the
                    // launcher wires spec -> env -> factory (empty pipelines guard fires first)
                    JobLauncher launcher = ctx.getBean(JobLauncher.class);
                    JobSpec spec = JobSpec.builder()
                            .jobName("j1")
                            .pipelineFactory("myFactory")
                            .parallelism(1)
                            .build();
                    AtomicReference<JobSpec> seen = FactoryConfig.seen;
                    seen.set(null);
                    assertThrows(IllegalStateException.class, () -> launcher.launch(spec));
                    assertThat(seen.get()).isSameAs(spec);
                    assertThat(launcher).isInstanceOf(RedisJobLauncher.class);

                    // claim prefix derives from the control plane prefix
                    assertThat(ctx.getBean(RedisJobControlPlane.class).prefix()).isEqualTo("it:cp:");

                    ctx.getBean(JobAgent.class).close(); // stop reconcile loop before context teardown
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAuthorizerConfig {
        @Bean
        public ControlPlaneAuthorizer controlPlaneAuthorizer() {
            return (actor, op, jobName) -> {
                throw new ControlPlaneAccessDeniedException("denied");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryConfig {
        static final AtomicReference<JobSpec> seen = new AtomicReference<>();

        @Bean
        public JobPipelineFactory myFactory() {
            return (spec, env) -> seen.set(spec);
        }
    }
}
