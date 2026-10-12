package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigService;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.ContentCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * At-rest content encryption (Security-Hardening-Design C1): secure-prefix dataIds are
 * stored marker-wrapped and encrypted in the config hash and its history records; every
 * read path (getConfig, history) hands back plaintext; plaintext and cipher-less
 * deployments behave exactly as before.
 */
class RedisConfigServiceContentCipherTest {

    private static final String MARK = "enc:v1:";

    private RedissonClient redisson;
    private RScript script;
    private RMap configMap;
    private RList historyList;
    private final AtomicReference<String> entryJson = new AtomicReference<>();

    /** Reversible, dependency-free cipher: reverse the characters. */
    private static String rev(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    private final ContentCipher reverseCipher = new ContentCipher() {
        @Override
        public String encrypt(String plain) {
            return rev(plain);
        }

        @Override
        public String decrypt(String encrypted) throws Exception {
            return rev(encrypted);
        }
    };

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        configMap = mock(RMap.class);
        historyList = mock(RList.class);

        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                    for (Object a : inv.getArguments()) {
                        if (a instanceof String s && s.startsWith("{")) {
                            entryJson.set(s);
                        }
                    }
                    return 0L;
                });
        when(redisson.getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(configMap);
        when(redisson.getList(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(historyList);
        when(redisson.getTopic(anyString(), any())).thenReturn(mock(RTopic.class));
    }

    private ConfigService service(ContentCipher cipher) {
        ConfigServiceConfig cfg = new ConfigServiceConfig();
        return new RedisConfigService(redisson, cfg, cipher);
    }

    private void start(ConfigService service) {
        service.start();
    }

    @Test
    void secureConfigIsStoredMarkedAndReadBackPlaintext() {
        ConfigService service = service(reverseCipher);
        start(service);

        assertTrue(service.publishConfig("secure.db-password", "g", "s3cr3t", null));

        // stored form: marker + encrypted (reversed) content — not the plaintext
        assertTrue(entryJson.get().contains(rev("s3cr3t")), entryJson.get());
        assertFalse(entryJson.get().contains("s3cr3t"), entryJson.get());

        when(configMap.get("content")).thenReturn(MARK + rev("s3cr3t"));
        assertEquals("s3cr3t", service.getConfig("secure.db-password", "g"));
    }

    @Test
    void nonSecureDataIdStaysPlaintext() {
        ConfigService service = service(reverseCipher);
        start(service);

        assertTrue(service.publishConfig("app.plain", "g", "visible", null));

        assertTrue(entryJson.get().contains("visible"), entryJson.get());
        when(configMap.get("content")).thenReturn("visible");
        assertEquals("visible", service.getConfig("app.plain", "g"));
    }

    @Test
    void nullCipherKeepsLegacyPlaintextBehavior() {
        ConfigService service = service(null);
        start(service);

        assertTrue(service.publishConfig("secure.db-password", "g", "s3cr3t", null));

        assertTrue(entryJson.get().contains("s3cr3t"), entryJson.get());
        when(configMap.get("content")).thenReturn("s3cr3t");
        assertEquals("s3cr3t", service.getConfig("secure.db-password", "g"));
    }

    @Test
    void legacyPlaintextIsReadBackAsIsWithCipherInstalled() {
        ConfigService service = service(reverseCipher);
        start(service);

        // published before a cipher existed: unmarked stored value must not be "decrypted"
        when(configMap.get("content")).thenReturn("s3cr3t");
        assertEquals("s3cr3t", service.getConfig("secure.db-password", "g"));
    }

    @Test
    void markedContentWithoutCipherIsReturnedRawWithWarning() {
        ConfigService service = service(null);
        start(service);

        when(configMap.get("content")).thenReturn(MARK + rev("s3cr3t"));
        // cannot decrypt — but must not fail every read of the config either
        assertEquals(MARK + rev("s3cr3t"), service.getConfig("secure.db-password", "g"));
    }

    @Test
    void cipherFailureFailsThePublishLoudly() {
        ContentCipher broken = new ContentCipher() {
            @Override
            public String encrypt(String plain) {
                throw new IllegalStateException("cipher key not loaded");
            }

            @Override
            public String decrypt(String encrypted) {
                throw new IllegalStateException("cipher key not loaded");
            }
        };
        ConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig(), broken);
        start(service);

        // encryption runs before the storage try block: silently storing plaintext would
        // defeat the cipher, so the publish fails with the cipher's error instead
        assertThrows(RuntimeException.class,
                () -> service.publishConfig("secure.db-password", "g", "s3cr3t", null));
    }
    @Test
    void cipherFailureFailsTheRead() {
        ContentCipher rejecting = new ContentCipher() {
            @Override
            public String encrypt(String plain) {
                return plain;
            }

            @Override
            public String decrypt(String encrypted) throws Exception {
                throw new IllegalArgumentException("payload tampered");
            }
        };
        ConfigService service = service(rejecting);
        start(service);

        when(configMap.get("content")).thenReturn(MARK + "payload");
        // a tampered or wrong-key ciphertext fails the read loudly, instead of leaking
        // ciphertext to the consumer as if it were config content
        assertThrows(RuntimeException.class, () -> service.getConfig("secure.db-password", "g"));
    }
    @Test
    void historyIsDeliveredDecrypted() throws Exception {
        ConfigService service = service(reverseCipher);
        start(service);

        String record = "{\"dataId\":\"secure.db-password\",\"group\":\"g\",\"content\":\""
                + MARK + rev("s3cr3t") + "\",\"version\":\"v1\",\"operation\":\"UPDATED\","
                + "\"operator\":\"system\",\"changeTime\":1}";
        when(historyList.size()).thenReturn(1);
        when(historyList.range(0, 0)).thenReturn(List.of(record));

        assertEquals("s3cr3t", service.getConfigHistory("secure.db-password", "g", 1).get(0).getContent());
    }

}
