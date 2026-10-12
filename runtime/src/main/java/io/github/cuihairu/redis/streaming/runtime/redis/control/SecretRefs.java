package io.github.cuihairu.redis.streaming.runtime.redis.control;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secret-reference convention for {@link JobSpec} config entries
 * (Security-Hardening-Design 方案 C2).
 *
 * <p>Config keys starting with {@value #SECRET_PREFIX} hold a <em>reference</em> to secret
 * material instead of the material itself, so plaintext secrets never need to reach spec
 * storage, history or audit. Supported reference schemes:</p>
 * <ul>
 *   <li>{@code env:NAME} — {@link System#getenv(String)}; a missing variable fails the deploy</li>
 *   <li>{@code sys:NAME} — {@link System#getProperty(String)}; a missing property fails the deploy</li>
 * </ul>
 *
 * <p>A {@code secret.*} value without a scheme passes through as a literal (the control
 * plane warns on write): the convention is advisory, so existing specs keep working.
 * Resolution happens on the execution side, in {@link RedisJobLauncher} before the pipeline
 * factory sees the config; a failed resolution fails the deploy with a message naming the
 * key and the missing source — never the value.</p>
 */
public final class SecretRefs {

    /** Config key prefix marking a value as a secret reference. */
    public static final String SECRET_PREFIX = "secret.";

    /** Placeholder substituted for secret values wherever they are displayed or hashed. */
    public static final String REDACTED = "***";

    private SecretRefs() {
    }

    /** @return true when {@code key} marks a secret reference entry */
    public static boolean isSecretKey(String key) {
        return key != null && key.startsWith(SECRET_PREFIX);
    }

    /** @return true when {@code value} uses a supported reference scheme ({@code env:}/{@code sys:}) */
    public static boolean isReference(String value) {
        return value != null && (value.startsWith("env:") || value.startsWith("sys:"));
    }

    /**
     * Resolve one {@code secret.*} value. {@code env:}/{@code sys:} references are looked up
     * (missing source → {@link IllegalArgumentException} naming the key and scheme, never the
     * value); a scheme-less value is returned as-is.
     */
    public static String resolve(String key, String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("env:")) {
            String name = value.substring("env:".length());
            String resolved = name.isEmpty() ? null : System.getenv(name);
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "Config key '" + key + "' references missing environment variable '" + name + "'");
            }
            return resolved;
        }
        if (value.startsWith("sys:")) {
            String name = value.substring("sys:".length());
            String resolved = name.isEmpty() ? null : System.getProperty(name);
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "Config key '" + key + "' references missing system property '" + name + "'");
            }
            return resolved;
        }
        return value;
    }

    /**
     * Config with every {@code secret.*} value resolved to its referenced material; other
     * entries are copied unchanged. {@code null} config stays {@code null}.
     */
    public static Map<String, String> resolveConfig(Map<String, String> config) {
        if (config == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.entrySet()) {
            out.put(e.getKey(), isSecretKey(e.getKey()) ? resolve(e.getKey(), e.getValue()) : e.getValue());
        }
        return out;
    }

    /**
     * The form of a config value safe to embed in content hashes and log lines: secret
     * references are kept verbatim — they are pointers, not material, and a reference
     * change must stay visible to drift detection; secret literals (convention
     * violations) are replaced by {@link #REDACTED}; non-secret values pass through.
     */
    public static String displayValue(String key, String value) {
        return isSecretKey(key) && !isReference(value) ? REDACTED : value;
    }

    /**
     * Config safe for display and hashing: {@code secret.*} values go through
     * {@link #displayValue} (literals masked, references kept visible), other entries are
     * copied unchanged. {@code null} config stays {@code null}.
     */
    public static Map<String, String> redactedConfig(Map<String, String> config) {
        if (config == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.entrySet()) {
            out.put(e.getKey(), displayValue(e.getKey(), e.getValue()));
        }
        return out;
    }

    /**
     * The spec a pipeline factory should build from: the given spec with secret references
     * resolved. Returns the original instance when the config holds no {@code secret.*}
     * keys; otherwise a copy with the resolved config — the original is never mutated, so
     * control-plane callers keep holding references, not secret material.
     */
    public static JobSpec resolvedSpec(JobSpec spec) {
        if (spec == null || spec.getConfig() == null
                || spec.getConfig().keySet().stream().noneMatch(SecretRefs::isSecretKey)) {
            return spec;
        }
        return spec.toBuilder()
                .config(resolveConfig(spec.getConfig()))
                .build();
    }
}
