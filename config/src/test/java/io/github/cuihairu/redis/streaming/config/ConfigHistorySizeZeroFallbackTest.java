package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for B-28 (fallback path): with historySize=0 the old trim
 * {@code LTRIM 0 maxhist-1} degraded to {@code LTRIM 0 -1} — Redis' "keep everything" —
 * the exact opposite of "keep no history". The Java fallback must not write a history
 * record at all when historySize is 0.
 */
class ConfigHistorySizeZeroFallbackTest {

    @SuppressWarnings("unchecked")
    @Test
    void historySizeZeroWritesNoHistoryRecord() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RList<String> history = mock(RList.class);
        when(redisson.getList(anyString(), any(StringCodec.class))).thenReturn((RList) history);

        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(0);
        RedisConfigService service = new RedisConfigService(redisson, cfg);

        invokeSaveConfigHistory(service, "old-content");

        verify(history, never()).add(anyInt(), anyString());
        verify(history, never()).add(anyString());
        verify(history, never()).trim(anyInt(), anyInt());
    }

    @SuppressWarnings("unchecked")
    @Test
    void historySizeOneStillTrimsToSingleRecord() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RList<String> history = mock(RList.class);
        when(redisson.getList(anyString(), any(StringCodec.class))).thenReturn((RList) history);
        when(history.size()).thenReturn(2);

        ConfigServiceConfig cfg = new ConfigServiceConfig();
        cfg.setHistorySize(1);
        RedisConfigService service = new RedisConfigService(redisson, cfg);

        invokeSaveConfigHistory(service, "old-content");

        verify(history).add(eq(0), anyString());
        verify(history).trim(0, 0);
    }

    private static void invokeSaveConfigHistory(RedisConfigService service, String oldContent) throws Exception {
        Method m = RedisConfigService.class.getDeclaredMethod("saveConfigHistory",
                String.class, String.class, String.class, String.class, LocalDateTime.class, String.class);
        m.setAccessible(true);
        m.invoke(service, "data", "group", oldContent, "v1", LocalDateTime.now(), "UPDATED");
    }
}
