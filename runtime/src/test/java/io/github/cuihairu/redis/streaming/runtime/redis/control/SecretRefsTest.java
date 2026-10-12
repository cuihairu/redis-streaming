package io.github.cuihairu.redis.streaming.runtime.redis.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the secret-reference convention (Security-Hardening-Design 方案 C2):
 * scheme resolution (env:/sys:), fail-fast on missing sources, lenient pass-through of
 * literals, redaction for display/hash, and the resolved-spec copy semantics.
 */
class SecretRefsTest {

    private static final String PROP = "c2s.test.token";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROP);
    }

    @Test
    void sysReferenceResolvesFromSystemProperty() {
        System.setProperty(PROP, "v");
        assertEquals("v", SecretRefs.resolve("secret.token", "sys:" + PROP));
        assertEquals(Map.of("secret.token", "v"),
                SecretRefs.resolveConfig(Map.of("secret.token", "sys:" + PROP)));
    }

    @Test
    void envReferenceResolvesFromEnvironment() {
        // PATH exists in every environment this suite can run in
        assertEquals(System.getenv("PATH"), SecretRefs.resolve("secret.path", "env:PATH"));
    }

    @Test
    void missingSysPropertyFailsFastNamingKeyAndSource() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecretRefs.resolve("secret.token", "sys:c2s.does.not.exist"));
        assertTrue(e.getMessage().contains("secret.token"));
        assertTrue(e.getMessage().contains("c2s.does.not.exist"));
    }

    @Test
    void missingEnvVariableFailsFastNamingKeyAndSource() {
        String missing = "C2S_NOT_SET_" + UUID.randomUUID().toString().replace('-', 'X');
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecretRefs.resolve("secret.db", "env:" + missing));
        assertTrue(e.getMessage().contains(missing));
    }

    @Test
    void literalSecretValuePassesThroughUnchanged() {
        assertEquals("hunter2", SecretRefs.resolve("secret.pw", "hunter2"));
        assertEquals("", SecretRefs.resolve("secret.pw", ""));
        assertNull(SecretRefs.resolve("secret.pw", null));
    }

    @Test
    void nonSecretKeysAreNeverResolved() {
        Map<String, String> out = SecretRefs.resolveConfig(Map.of(
                "app.url", "env:NOT_A_REFERENCE",
                "plain", "sys:also.not"));
        assertEquals("env:NOT_A_REFERENCE", out.get("app.url"));
        assertEquals("sys:also.not", out.get("plain"));
    }

    @Test
    void nullConfigStaysNullThroughBothTransforms() {
        assertNull(SecretRefs.resolveConfig(null));
        assertNull(SecretRefs.redactedConfig(null));
    }

    @Test
    void redactedConfigMasksOnlySecretLiteralsAndKeepsReferencesVisible() {
        Map<String, String> out = SecretRefs.redactedConfig(Map.of(
                "secret.pw", "hunter2",
                "secret.ref", "env:DB_PASS",
                "app.url", "http://x"));
        assertEquals(SecretRefs.REDACTED, out.get("secret.pw"));
        assertEquals("env:DB_PASS", out.get("secret.ref")); // a pointer, safe and useful to show
        assertEquals("http://x", out.get("app.url"));
        assertEquals(SecretRefs.REDACTED, SecretRefs.displayValue("secret.pw", "hunter2"));
        assertEquals("sys:K", SecretRefs.displayValue("secret.k", "sys:K"));
        assertEquals("v", SecretRefs.displayValue("app.k", "v"));
    }

    @Test
    void keyClassification() {
        assertTrue(SecretRefs.isSecretKey("secret.db"));
        assertFalse(SecretRefs.isSecretKey("secretx")); // prefix includes the dot
        assertFalse(SecretRefs.isSecretKey(null));
        assertTrue(SecretRefs.isReference("env:X"));
        assertTrue(SecretRefs.isReference("sys:X"));
        assertFalse(SecretRefs.isReference("hunter2"));
        assertFalse(SecretRefs.isReference(null));
    }

    @Test
    void resolvedSpecReturnsOriginalWhenNoSecretKeys() {
        JobSpec spec = JobSpec.builder()
                .jobName("j1").pipelineFactory("f").config(Map.of("k", "v"))
                .parallelism(1).build();
        assertSame(spec, SecretRefs.resolvedSpec(spec));

        // null config is not copied either
        JobSpec noConfig = JobSpec.builder()
                .jobName("j1").pipelineFactory("f").parallelism(1).build();
        assertSame(noConfig, SecretRefs.resolvedSpec(noConfig));

        assertNull(SecretRefs.resolvedSpec(null));
    }

    @Test
    void resolvedSpecReturnsCopyWithResolvedConfigAndOriginalUntouched() {
        System.setProperty(PROP, "v");
        JobSpec original = JobSpec.builder()
                .jobName("j1").pipelineFactory("f").tenant("t")
                .config(new LinkedHashMap<>(Map.of("secret.token", "sys:" + PROP, "k", "v")))
                .parallelism(3).version(7L).description("d").updatedBy("u").updatedAt(5L).specHash("h")
                .build();

        JobSpec resolved = SecretRefs.resolvedSpec(original);

        assertEquals("v", resolved.getConfig().get("secret.token"));
        assertEquals("v", resolved.getConfig().get("k"));
        assertEquals("j1", resolved.getJobName());
        assertEquals("t", resolved.getTenant());
        assertEquals("f", resolved.getPipelineFactory());
        assertEquals(3, resolved.getParallelism());
        assertEquals(7L, resolved.getVersion());
        assertEquals("d", resolved.getDescription());
        assertEquals("u", resolved.getUpdatedBy());
        assertEquals(5L, resolved.getUpdatedAt());
        assertEquals("h", resolved.getSpecHash());

        // the deployed spec keeps holding the reference, not the material
        assertEquals("sys:" + PROP, original.getConfig().get("secret.token"));
    }
}
