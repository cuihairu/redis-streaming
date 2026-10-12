package io.github.cuihairu.redis.streaming.config;

/**
 * Pluggable at-rest encryption for config-center content (Security-Hardening-Design 方案 C).
 *
 * <p>When a cipher is installed on {@code RedisConfigService}, the content of configs whose
 * dataId starts with the configured secure prefix (default {@code secure.}) is encrypted
 * before it is written to Redis (the config hash and its history records) and decrypted
 * transparently on every read. Keys outside the prefix, and instances without a cipher,
 * store plaintext exactly as before — the default is a no-op.</p>
 *
 * <p>The cipher operates on strings only; key/secret management is the implementation's
 * concern (typically environment variables or JVM flags — keys must not live in Redis
 * alongside the data they protect). The framework ships no concrete implementation.</p>
 */
public interface ContentCipher {

    /**
     * Encrypt plaintext config content before storage.
     *
     * @param plain the plaintext content to store
     * @return the ciphertext representation to write to Redis
     */
    String encrypt(String plain);

    /**
     * Decrypt ciphertext back to the original content.
     *
     * @param encrypted the ciphertext previously returned by {@link #encrypt(String)}
     * @return the original plaintext content
     * @throws Exception if the ciphertext cannot be decrypted (wrong key, corrupted data)
     */
    String decrypt(String encrypted) throws Exception;
}
