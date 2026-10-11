package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.starter.properties.RedisStreamingProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Security-hardening v1 (docs/Security-Hardening-Design.md 方案 A): ACL username
 * forwarding, {@code ${env:VAR}} credential placeholders, and the
 * {@link ConfigCustomizer} hook that runs after the built-in single-server
 * settings and before {@code Redisson.create}.
 */
class RedisStreamingAutoConfigurationSecurityTest {

    private static Config buildClientConfig(RedisStreamingProperties props, ObjectProvider<ConfigCustomizer> customizers) {
        try (MockedStatic<Redisson> redisson = mockStatic(Redisson.class)) {
            RedissonClient client = mock(RedissonClient.class);
            ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);
            redisson.when(() -> Redisson.create(any(Config.class))).thenReturn(client);

            RedissonClient out = new RedisStreamingAutoConfiguration().redissonClient(props, customizers);
            assertSame(client, out);

            redisson.verify(() -> Redisson.create(captor.capture()));
            return captor.getValue();
        }
    }

    /** An ObjectProvider without any registered customizer beans. */
    @SuppressWarnings("unchecked")
    static ObjectProvider<ConfigCustomizer> emptyCustomizers() {
        ObjectProvider<ConfigCustomizer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ConfigCustomizer> customizers(ConfigCustomizer... beans) {
        ObjectProvider<ConfigCustomizer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(beans));
        return provider;
    }

    @Test
    @SuppressWarnings("deprecation")
    void aclUsernameIsForwardedToRedisson() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setAddress("redis://127.0.0.1:6379");
        props.getRedis().setUsername("app-user");
        props.getRedis().setPassword("s3cr3t");

        Config config = buildClientConfig(props, emptyCustomizers());

        assertEquals("app-user", config.useSingleServer().getUsername());
        assertEquals("s3cr3t", config.useSingleServer().getPassword());
    }

    @Test
    @SuppressWarnings("deprecation")
    void blankUsernameLeavesDefaultUser() {
        RedisStreamingProperties blank = new RedisStreamingProperties();
        blank.getRedis().setUsername("   ");
        assertNull(buildClientConfig(blank, emptyCustomizers()).useSingleServer().getUsername());

        RedisStreamingProperties nulled = new RedisStreamingProperties();
        assertNull(buildClientConfig(nulled, emptyCustomizers()).useSingleServer().getUsername());
    }

    @Test
    void resolverPassesPlainValuesThrough() {
        assertEquals("s3cr3t", RedisStreamingAutoConfiguration.resolveCredential("s3cr3t", k -> null));
        assertNull(RedisStreamingAutoConfiguration.resolveCredential(null, k -> "x"));
        assertEquals("  ", RedisStreamingAutoConfiguration.resolveCredential("  ", k -> null));
        // not a placeholder: missing closing brace / wrong prefix pass through untouched
        assertEquals("${env:VAR", RedisStreamingAutoConfiguration.resolveCredential("${env:VAR", k -> null));
        assertEquals("${other:VAR}", RedisStreamingAutoConfiguration.resolveCredential("${other:VAR}", k -> null));
    }

    @Test
    void resolverReadsEnvironmentVariable() {
        assertEquals("from-env", RedisStreamingAutoConfiguration.resolveCredential(
                "${env:APP_DB_PASS}", k -> "APP_DB_PASS".equals(k) ? "from-env" : null));
    }

    @Test
    void missingEnvironmentVariableFailsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RedisStreamingAutoConfiguration.resolveCredential("${env:NO_SUCH_VAR}", k -> null));
        assertTrue(ex.getMessage().contains("NO_SUCH_VAR"), () -> ex.getMessage());
    }

    @Test
    @SuppressWarnings("deprecation")
    void customizerRunsAfterBuiltinSettingsAndCanOverride() {
        RedisStreamingProperties props = new RedisStreamingProperties();
        props.getRedis().setAddress("redis://127.0.0.1:6379");
        props.getRedis().setPassword("plain");
        props.getRedis().setUsername("app-user");

        // the customizer sees the built-in settings and can adjust anything after them
        Config config = buildClientConfig(props, customizers(cfg -> {
            assertEquals("app-user", cfg.useSingleServer().getUsername(),
                    "customizer must run after the built-in credential wiring");
            cfg.useSingleServer().setDatabase(7);
        }));

        assertEquals(7, config.useSingleServer().getDatabase(),
                "customizer mutation must land in the config handed to Redisson.create");
        assertEquals("app-user", config.useSingleServer().getUsername());
    }

    @Test
    @SuppressWarnings("deprecation")
    void multipleCustomizersRunInOrder() {
        RedisStreamingProperties props = new RedisStreamingProperties();

        Config config = buildClientConfig(props, customizers(
                cfg -> cfg.useSingleServer().setDatabase(1),
                cfg -> cfg.useSingleServer().setDatabase(cfg.useSingleServer().getDatabase() + 1)
        ));

        assertEquals(2, config.useSingleServer().getDatabase(), "customizers compose in declaration order");
    }
}
