package io.github.cuihairu.redis.streaming.runtime.redis.control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log redaction guardrail (Security-Hardening-Design 日志脱敏护栏).
 *
 * <p>The framework's uniform convention: credential material must never reach log output. The
 * control plane already keeps secret literals out of spec storage, history and audit —
 * {@code secret.*} values are references, resolved only on the execution side
 * ({@link SecretRefs}). This class closes the remaining vector: a log line that interpolates
 * a config map, a whole {@link JobSpec}, or a free-form message.</p>
 *
 * <p>Classification is by key name, not only by the {@code secret.*} prefix: a user who puts a
 * password under {@code spring.redis.password} never opted into the reference scheme, so the
 * guardrail must not wait for the prefix. A credential value is masked unless it is an
 * {@code env:/sys:} reference — a pointer, not material, and one whose visibility drift
 * detection depends on.</p>
 */
public final class LogRedaction {

    /** Placeholder substituted for any credential value that reaches a log line. */
    public static final String REDACTED = SecretRefs.REDACTED;

    /**
     * Key fragments (matched case-insensitively, as substrings) that mark a value as credential
     * material regardless of the {@code secret.*} convention.
     */
    private static final List<String> CREDENTIAL_KEY_FRAGMENTS = List.of(
            "password", "passwd", "secret", "token", "credential", "apikey", "api_key",
            "accesskey", "access_key", "privatekey", "private_key", "auth");

    /** A {@code key=value} or {@code key: value} pair with a single-token value. */
    private static final Pattern PAIR_PATTERN = Pattern.compile(
            "([A-Za-z0-9_.\\-]+)(\\s*[=:]\\s*)([^\\s,;)\\]}]+)");

    private LogRedaction() {
    }

    /** @return true when {@code key} marks a credential value by name (not just by secret.* prefix) */
    public static boolean isCredentialKey(String key) {
        if (key == null) {
            return false;
        }
        if (SecretRefs.isSecretKey(key)) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String fragment : CREDENTIAL_KEY_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A copy of {@code entries} safe to interpolate into a log line: credential values are
     * masked unless they are {@code env:/sys:} references, and non-credential entries pass
     * through. The source map is never mutated; {@code null} stays {@code null}.
     */
    public static Map<String, String> redact(Map<String, String> entries) {
        if (entries == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            out.put(e.getKey(), isCredentialKey(e.getKey()) && !SecretRefs.isReference(e.getValue())
                    ? REDACTED : e.getValue());
        }
        return out;
    }

    /**
     * A log-safe rendering of {@code spec}: every field except {@code config}, whose entries go
     * through {@link #redact(Map)}. This is what {@link JobSpec#toString()} uses, so a
     * {@code log.debug("... {}", spec)} can never leak a secret literal.
     */
    public static String renderSpec(JobSpec spec) {
        if (spec == null) {
            return "null";
        }
        return "JobSpec(jobName=" + spec.getJobName()
                + ", tenant=" + spec.getTenant()
                + ", pipelineFactory=" + spec.getPipelineFactory()
                + ", config=" + redact(spec.getConfig())
                + ", parallelism=" + spec.getParallelism()
                + ", version=" + spec.getVersion()
                + ", description=" + spec.getDescription()
                + ", updatedBy=" + spec.getUpdatedBy()
                + ", updatedAt=" + spec.getUpdatedAt()
                + ", specHash=" + spec.getSpecHash()
                + ')';
    }

    /**
     * Masks credential values embedded in a free-form message. Both {@code key=value} and
     * {@code key: value} pairs whose key {@link #isCredentialKey(String) is a credential key}
     * have their value replaced; everything else is left untouched.
     */
    public static String redact(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = PAIR_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            if (!isCredentialKey(key)) {
                continue;
            }
            if (SecretRefs.isReference(m.group(3))) {
                continue; // a pointer, safe to keep
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2) + REDACTED));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
