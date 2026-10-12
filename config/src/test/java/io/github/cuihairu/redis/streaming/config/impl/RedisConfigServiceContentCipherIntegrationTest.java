package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.ContentCipher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-Redis proof for at-rest content encryption: the stored hash holds only
 * marker-wrapped ciphertext for secure-prefix dataIds (plaintext elsewhere), reads and
 * history decrypt transparently, and plaintext entries written before a cipher existed
 * keep working and convert on their next publish.
 */
@Tag("integration")
class RedisConfigServiceContentCipherIntegrationTest {

    private static final String MARK = "enc:v1:";

    private static String rev(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    private static final ContentCipher REVERSE = new ContentCipher() {
        @Override
        public String encrypt(String plain) {
            return rev(plain);
        }

        @Override
        public String decrypt(String encrypted) {
            return rev(encrypted);
        }
    };

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void secureConfigIsEncryptedAtRestAndDecryptedOnRead() {
        RedissonClient redis = client();
        String prefix = "cfg-cipher-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigServiceConfig cfg = new ConfigServiceConfig(prefix, true);
        cfg.setHistorySize(10);
        cfg.setSecurePrefix("secret.");
        RedisConfigService service = new RedisConfigService(redis, cfg, REVERSE);
        service.start();
        try {
            assertTrue(service.publishConfig("secret.db", "G", "s3cr3t!", null));
            assertTrue(service.publishConfig("plain.db", "G", "hello", null));

            // read the raw hash back, bypassing the service: ciphertext only for the
            // secure prefix, plaintext untouched elsewhere
            RMap<String, String> secretHash = redis.getMap(cfg.getConfigKey("G", "secret.db"),
                    org.redisson.client.codec.StringCodec.INSTANCE);
            String stored = secretHash.get("content");
            assertTrue(stored.startsWith(MARK), stored);
            assertFalse(stored.contains("s3cr3t!"), stored);
            assertEquals(rev("s3cr3t!"), stored.substring(MARK.length()));

            RMap<String, String> plainHash = redis.getMap(cfg.getConfigKey("G", "plain.db"),
                    org.redisson.client.codec.StringCodec.INSTANCE);
            assertEquals("hello", plainHash.get("content"));

            // reads decrypt
            assertEquals("s3cr3t!", service.getConfig("secret.db", "G"));
            assertEquals("hello", service.getConfig("plain.db", "G"));

            // history records decrypt too (the first version was pushed by the re-publish)
            assertTrue(service.publishConfig("secret.db", "G", "s3cr3t-v2", null));
            List<ConfigHistory> hist = service.getConfigHistory("secret.db", "G", 10);
            assertFalse(hist.isEmpty(), "the replaced version should be in history");
            for (ConfigHistory h : hist) {
                if (h.getContent() != null) {
                    assertTrue(h.getContent().startsWith("s3cr3t"), h.getContent());
                }
            }
        } finally {
            service.stop();
            redis.shutdown();
        }
    }

    @Test
    void legacyPlaintextSurvivesCipherInstallationAndConvertsOnRepublish() {
        RedissonClient redis = client();
        String prefix = "cfg-legacy-" + UUID.randomUUID().toString().substring(0, 8);

        // write plaintext through a cipher-less service
        RedisConfigService plainService = new RedisConfigService(redis, new ConfigServiceConfig(prefix, true));
        plainService.start();
        assertTrue(plainService.publishConfig("secret.k", "G", "old-plain", null));
        plainService.stop();

        // a ciphered instance reads the legacy entry as-is, then republishing encrypts it
        ConfigServiceConfig cipheredCfg = new ConfigServiceConfig(prefix, true);
        cipheredCfg.setSecurePrefix("secret.");
        RedisConfigService ciphered = new RedisConfigService(redis, cipheredCfg, REVERSE);
        ciphered.start();
        try {
            assertEquals("old-plain", ciphered.getConfig("secret.k", "G"));

            assertTrue(ciphered.publishConfig("secret.k", "G", "new-secret", null));
            RMap<String, String> hash = redis.getMap(cipheredCfg.getConfigKey("G", "secret.k"),
                    org.redisson.client.codec.StringCodec.INSTANCE);
            String stored = hash.get("content");
            assertTrue(stored.startsWith(MARK), stored);
            assertEquals(rev("new-secret"), stored.substring(MARK.length()));
            assertEquals("new-secret", ciphered.getConfig("secret.k", "G"));
        } finally {
            ciphered.stop();
            redis.shutdown();
        }
    }
}
