package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the private helpers of
 * {@code RedisStreamBuilder$RedisKeyedStreamBuilder$RedisWindowedStreamImpl}:
 * {@code decodeKey}, {@code decodeNumber}, {@code parseWindow}, {@code windowMember},
 * {@code windowCloseTime} and {@code fireDueWindows} (via reflection and a mocked due-set).
 * No Redis required.
 */
class RedisWindowedStreamHelperUnitTest {

    private static final String D = "\u0001";

    private static WindowedStream<Object, Object> windowed(RedisRuntimeConfig cfg) {
        RedissonClient redis = mock(RedissonClient.class);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
        return env.fromMqTopic("topicA", "groupA")
                .map(m -> (Object) m.getPayload())
                .keyBy(v -> v)
                .window(TumblingWindow.ofMillis(1000));
    }

    private static Method method(Object target, String name, Class<?>... params) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Object invoke(Object target, Method m, Object... args) throws Exception {
        try {
            return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static Object accessor(Object record, String name) throws Exception {
        Method m = record.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    private static RedisRuntimeConfig cfg(java.util.function.Consumer<RedisRuntimeConfig.Builder> tweaks) {
        RedisRuntimeConfig.Builder b = RedisRuntimeConfig.builder()
                .jobName("it-rti-win-helper")
                .stateKeyPrefix("it-rti-win-helper");
        tweaks.accept(b);
        return b.build();
    }

    @Test
    void decodeKeyCoversAllEncodingBranches() throws Exception {
        WindowedStream<Object, Object> w = windowed(cfg(b -> {}));
        Method decodeKey = method(w, "decodeKey", String.class);

        assertNull(invoke(w, decodeKey, (Object) null));
        assertEquals("hello", invoke(w, decodeKey, "s:hello" + D + "100" + D + "200"));
        assertEquals(5L, invoke(w, decodeKey, "n:5" + D + "100" + D + "200"));
        assertEquals(5.5d, invoke(w, decodeKey, "n:5.5" + D + "100" + D + "200"));
        assertEquals("xx", invoke(w, decodeKey, "n:xx" + D + "100" + D + "200"));

        Object untypedMap = invoke(w, decodeKey, "j:{\"a\":1}" + D + "100" + D + "200");
        assertTrue(untypedMap instanceof Map, "untyped json key decodes to a Map: " + untypedMap);

        AtomicReference<Class<?>> keyClass = (AtomicReference<Class<?>>) keyClassRef(w);
        keyClass.set(LinkedHashMapHolder.class);
        Object typed = invoke(w, decodeKey, "j:{\"name\":\"k\"}" + D + "100" + D + "200");
        assertEquals(new LinkedHashMapHolder("k"), typed);
        assertEquals("not-json", invoke(w, decodeKey, "j:not-json" + D + "100" + D + "200"));

        keyClass.set(null);
        assertEquals("not-json", invoke(w, decodeKey, "j:not-json" + D + "100" + D + "200"));

        assertEquals("xyz", invoke(w, decodeKey, "t:xyz" + D + "100" + D + "200"));
        assertEquals("plainkey", invoke(w, decodeKey, "plainkey" + D + "100" + D + "200"));
        assertEquals("no-separators", invoke(w, decodeKey, "no-separators"));
    }

    @Test
    void decodeNumberObjectHandlesNullNumberStringAndGarbage() throws Exception {
        WindowedStream<Object, Object> w = windowed(cfg(b -> {}));
        Method decodeNumber = method(w, "decodeNumber", Object.class);
        assertEquals(0L, invoke(w, decodeNumber, (Object) null));
        assertEquals(7L, invoke(w, decodeNumber, 7L));
        assertEquals(1.25d, (Double) invoke(w, decodeNumber, "1.25"), 1e-9);
        assertEquals(9L, invoke(w, decodeNumber, "9"));
        assertEquals(0L, invoke(w, decodeNumber, "oops"));
    }

    @Test
    void parseWindowAndWindowMemberRoundTrip() throws Exception {
        WindowedStream<Object, Object> w = windowed(cfg(b -> {}));
        Method parseWindow = method(w, "parseWindow", String.class);
        Method windowMember = method(w, "windowMember", String.class, long.class, long.class);

        String member = (String) invoke(w, windowMember, "s:k", 100L, 200L);
        assertEquals("e:s:k" + D + "100" + D + "200", member);
        assertEquals("e:" + D + "5" + D + "10", invoke(w, windowMember, null, 5L, 10L));

        Object parsed = invoke(w, parseWindow, member);
        assertEquals("s:k", accessor(parsed, "keyField"));
        assertEquals(100L, accessor(parsed, "start"));
        assertEquals(200L, accessor(parsed, "end"));

        Object fallback = invoke(w, parseWindow, "junk-without-separators");
        assertEquals("junk-without-separators", accessor(fallback, "keyField"));
        assertEquals(0L, accessor(fallback, "start"));
        assertEquals(0L, accessor(fallback, "end"));

        Object partial = invoke(w, parseWindow, "a" + D + "b");
        assertEquals("a" + D + "b", accessor(partial, "keyField"));

        Object badNumbers = invoke(w, parseWindow, "a" + D + "xx" + D + "3");
        assertEquals("a" + D + "xx" + D + "3", accessor(badNumbers, "keyField"));

        Object nullMember = invoke(w, parseWindow, (Object) null);
        assertNull(accessor(nullMember, "keyField"));
    }

    @Test
    void windowMemberEscapesSeparatorAndBackslashInKeys() throws Exception {
        WindowedStream<Object, Object> w = windowed(cfg(b -> {}));
        Method parseWindow = method(w, "parseWindow", String.class);
        Method windowMember = method(w, "windowMember", String.class, long.class, long.class);

        // RT-L7: a key containing the D separator must not shift the start/end fields
        String member = (String) invoke(w, windowMember, "s:a" + D + "b", 1L, 2L);
        Object parsed = invoke(w, parseWindow, member);
        assertEquals("s:a" + D + "b", accessor(parsed, "keyField"));
        assertEquals(1L, accessor(parsed, "start"));
        assertEquals(2L, accessor(parsed, "end"));

        // backslashes and literal escape sequences round-trip exactly
        for (String tricky : new String[]{"s:back\\slash", "s:esc\\1ape", "s:\\\\", "e:colon-prefix"}) {
            Object roundTripped = invoke(w, parseWindow,
                    (String) invoke(w, windowMember, tricky, 3L, 4L));
            assertEquals(tricky, accessor(roundTripped, "keyField"), "key " + tricky + " must round-trip");
        }

        // legacy members (written before escaping existed) keep parsing as-is
        Object legacy = invoke(w, parseWindow, "s:old\\key" + D + "7" + D + "9");
        assertEquals("s:old\\key", accessor(legacy, "keyField"));
        assertEquals(7L, accessor(legacy, "start"));
        assertEquals(9L, accessor(legacy, "end"));
    }

    @Test
    void windowCloseTimeAppliesLatenessAndClampsOverflow() throws Exception {
        WindowedStream<Object, Object> late = windowed(cfg(b -> b.windowAllowedLateness(Duration.ofMillis(100))));
        Method windowCloseTime = method(late, "windowCloseTime", long.class);
        assertEquals(2100L, invoke(late, windowCloseTime, 2000L));
        assertEquals(Long.MAX_VALUE, invoke(late, windowCloseTime, Long.MAX_VALUE));

        WindowedStream<Object, Object> strict = windowed(cfg(b -> b.windowAllowedLateness(Duration.ZERO)));
        Method strictClose = method(strict, "windowCloseTime", long.class);
        assertEquals(2000L, invoke(strict, windowCloseTime, 2000L));
    }

    @Test
    void fireDueWindowsRespectsWatermarkBoundsAndMaxFires() throws Exception {
        WindowedStream<Object, Object> w = windowed(cfg(b -> b.windowMaxFiresPerRecord(2)));
        Method fireDueWindows = findFireDueWindows(w);
        // (due, watermark, partitionId, stateName, bucketTriggers, handler) — the trigger map
        // and handler are the last two params since the WindowAssigner.Trigger wiring (todo B3)
        Class<?> handlerType = fireDueWindows.getParameterTypes()[fireDueWindows.getParameterCount() - 1];
        List<String> fired = new ArrayList<>();
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[]{handlerType},
                (proxy, m, args) -> {
                    if ("fire".equals(m.getName())) {
                        fired.add(args[0] + "|" + args[1] + "|" + args[2]);
                    }
                    return null;
                });
        // per-invocation fresh trigger map; the stock EventTimeTrigger consults CONTINUE on
        // element and FIRE_AND_PURGE at close, so every due entry below reaches the handler
        java.util.function.Function<RScoredSortedSet<String>, Object[]> call = due -> new Object[]{due, 50L, 0, "s",
                new java.util.HashMap<String, io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Trigger<Object>>(),
                handler};

        // empty due-set
        invoke(w, fireDueWindows, call.apply(mockSet()));
        assertEquals(List.of(), fired);

        // score beyond watermark -> untouched
        RScoredSortedSet<String> future = mockSet();
        when(future.firstEntry()).thenReturn(new ScoredEntry<>(100.0, "m"));
        invoke(w, fireDueWindows, call.apply(future));
        assertEquals(List.of(), fired);

        // happy path: fires parsed window, then due-set empties
        RScoredSortedSet<String> due = mockSet();
        when(due.firstEntry()).thenReturn(new ScoredEntry<>(10.0, "m"), (ScoredEntry<String>) null);
        when(due.pollFirstEntry()).thenReturn(new ScoredEntry<>(10.0, "s:k" + D + "0" + D + "10"));
        invoke(w, fireDueWindows, call.apply(due));
        assertEquals(List.of("s:k" + D + "0" + D + "10|0|10"), fired);

        // pollFirstEntry null -> stop without firing
        fired.clear();
        RScoredSortedSet<String> vanishing = mockSet();
        when(vanishing.firstEntry()).thenReturn(new ScoredEntry<>(10.0, "m"));
        when(vanishing.pollFirstEntry()).thenReturn((ScoredEntry<String>) null);
        invoke(w, fireDueWindows, call.apply(vanishing));
        assertEquals(List.of(), fired);

        // pollFirstEntry with null value -> stop
        RScoredSortedSet<String> nullValued = mockSet();
        when(nullValued.firstEntry()).thenReturn(new ScoredEntry<>(10.0, (String) null));
        when(nullValued.pollFirstEntry()).thenReturn(new ScoredEntry<>(10.0, (String) null));
        invoke(w, fireDueWindows, call.apply(nullValued));
        assertEquals(List.of(), fired);

        // max fires per record clamp (config = 2, three due windows)
        fired.clear();
        RScoredSortedSet<String> many = mockSet();
        when(many.firstEntry()).thenReturn(new ScoredEntry<>(1.0, "m"));
        when(many.pollFirstEntry()).thenReturn(
                new ScoredEntry<>(1.0, "s:k" + D + "1" + D + "2"),
                new ScoredEntry<>(2.0, "s:k" + D + "3" + D + "4"),
                new ScoredEntry<>(3.0, "s:k" + D + "5" + D + "6"));
        invoke(w, fireDueWindows, call.apply(many));
        assertEquals(2, fired.size());
    }

    private static Method findFireDueWindows(Object target) throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if ("fireDueWindows".equals(m.getName()) && m.getParameterCount() == 6) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new IllegalStateException("fireDueWindows not found");
    }

    private static Object keyClassRef(Object target) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField("keyClassRef");
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    private static RScoredSortedSet<String> mockSet() {
        return mock(RScoredSortedSet.class);
    }

    public static final class LinkedHashMapHolder {
        private String name;

        public LinkedHashMapHolder() {
        }

        public LinkedHashMapHolder(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof LinkedHashMapHolder h && java.util.Objects.equals(name, h.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hashCode(name);
        }
    }
}
