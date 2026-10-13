package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the log redaction guardrail (Security-Hardening-Design 日志脱敏护栏):
 * credential values must never reach a log line, whether the value is interpolated as a
 * config map, a whole {@link JobSpec}, or a free-form message.
 */
class LogRedactionTest {

    @Test
    void credentialKeysAreClassifiedByNameNotOnlyBySecretPrefix() {
        assertTrue(LogRedaction.isCredentialKey("secret.db"));          // the convention prefix
        assertTrue(LogRedaction.isCredentialKey("spring.redis.password"));
        assertTrue(LogRedaction.isCredentialKey("redis.passwd"));
        assertTrue(LogRedaction.isCredentialKey("auth.token"));
        assertTrue(LogRedaction.isCredentialKey("aws.accessKey"));
        assertTrue(LogRedaction.isCredentialKey("apiKey"));
        assertTrue(LogRedaction.isCredentialKey("private_key"));
        assertFalse(LogRedaction.isCredentialKey("app.url"));
        assertFalse(LogRedaction.isCredentialKey("jobName"));
        assertFalse(LogRedaction.isCredentialKey("server"));
        assertFalse(LogRedaction.isCredentialKey(null));
        // The fragment match is deliberately broader than SecretRefs#isSecretKey (which
        // requires the "secret." prefix): "secretx" carries the fragment and is masked too.
        assertTrue(LogRedaction.isCredentialKey("secretx"));
    }

    @Test
    void redactMapMasksCredentialLiteralsAndKeepsReferencesAndPlainValues() {
        Map<String, String> out = LogRedaction.redact(new LinkedHashMap<>(Map.of(
                "secret.pw", "hunter2",
                "spring.redis.password", "hunter2",
                "secret.ref", "env:DB_PASS",
                "app.url", "http://x",
                "plain", "v")));
        assertEquals(LogRedaction.REDACTED, out.get("secret.pw"));
        assertEquals(LogRedaction.REDACTED, out.get("spring.redis.password"));
        assertEquals("env:DB_PASS", out.get("secret.ref"));   // a pointer, safe and useful to show
        assertEquals("http://x", out.get("app.url"));
        assertEquals("v", out.get("plain"));
    }

    @Test
    void redactMapReturnsAnIndependentCopy() {
        Map<String, String> in = new LinkedHashMap<>(Map.of("password", "hunter2"));
        Map<String, String> out = LogRedaction.redact(in);
        assertEquals("hunter2", in.get("password"), "the source map must never be mutated");
        assertEquals(LogRedaction.REDACTED, out.get("password"));
    }

    @Test
    void redactMapHandlesNullAndEmpty() {
        assertNull(LogRedaction.redact((Map<String, String>) null));
        assertTrue(LogRedaction.redact(Map.of()).isEmpty());
    }

    @Test
    void renderSpecMasksSecretLiteralsButKeepsReferences() {
        JobSpec spec = JobSpec.builder()
                .jobName("j1").pipelineFactory("f").tenant("t")
                .config(new LinkedHashMap<>(Map.of(
                        "secret.pw", "hunter2", "secret.ref", "env:DB_PASS", "app.url", "http://x")))
                .parallelism(2).version(5L).build();

        String rendered = LogRedaction.renderSpec(spec);
        assertFalse(rendered.contains("hunter2"), "a secret literal must never reach the log");
        assertTrue(rendered.contains("env:DB_PASS"), "a reference is a pointer and stays visible");
        assertTrue(rendered.contains("http://x"));
        assertTrue(rendered.contains("j1"));
    }

    @Test
    void jobSpecToStringIsLogSafe() {
        JobSpec spec = JobSpec.builder()
                .jobName("j1").pipelineFactory("f")
                .config(Map.of("spring.redis.password", "hunter2", "secret.ref", "env:DB_PASS"))
                .build();

        String s = spec.toString();
        assertFalse(s.contains("hunter2"), "JobSpec#toString must redact credential literals");
        assertTrue(s.contains("env:DB_PASS"));
        assertTrue(s.contains("j1"));
    }

    @Test
    void redactMessageMasksCredentialPairsInBothFormats() {
        assertEquals("connect password=*** host=127.0.0.1",
                LogRedaction.redact("connect password=hunter2 host=127.0.0.1"));
        assertEquals("token: ***", LogRedaction.redact("token: hunter2"));
        assertEquals("url=http://x password=***",
                LogRedaction.redact("url=http://x password=hunter2"));
    }

    @Test
    void redactMessageLeavesNonCredentialTextAndReferencesAlone() {
        assertEquals("job j1 deployed parallelism=2",
                LogRedaction.redact("job j1 deployed parallelism=2"));
        assertEquals("secret.ref=env:DB_PASS", LogRedaction.redact("secret.ref=env:DB_PASS"));
        assertNull(LogRedaction.redact((String) null));
    }
}
