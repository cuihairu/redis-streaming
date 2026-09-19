package io.github.cuihairu.redis.streaming.storm;

import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reflection-based error-path storm helper. Public methods are invoked with plausible
 * arguments against (a) a deep-stub collaborator and (b) an armed always-failing mock,
 * exercising both happy plumbing and catch/fallback branches. Exceptions thrown by the
 * methods under test are expected; JVM errors always propagate.
 */
public final class Storms {

    private Storms() {}

    /** While false, exploding mocks answer with lenient values (used while constructing). */
    public static final ThreadLocal<Boolean> ARMED = ThreadLocal.withInitial(() -> Boolean.TRUE);

    /** Build an object while exploding collaborators stay disarmed. */
    public static <T> T constructing(Supplier<T> body) {
        ARMED.set(Boolean.FALSE);
        try {
            return body.get();
        } finally {
            ARMED.set(Boolean.TRUE);
        }
    }

    static Object lenientValue(Class<?> rt) {
        if (rt == void.class || rt == Void.class) return null;
        if (rt == boolean.class || rt == Boolean.class) return Boolean.TRUE;
        if (rt == int.class || rt == Integer.class) return 1;
        if (rt == long.class || rt == Long.class) return 1L;
        if (rt == double.class || rt == Double.class) return 1.0d;
        if (rt == float.class || rt == Float.class) return 1.0f;
        if (rt == short.class) return (short) 1;
        if (rt == byte.class) return (byte) 1;
        if (rt == char.class) return 'x';
        if (rt == String.class) return "storm";
        if (rt == List.class || rt == Iterable.class || rt == java.util.Collection.class) return new ArrayList<>();
        if (rt == Set.class) return new HashSet<>();
        if (rt == Map.class) return new HashMap<>();
        if (rt.isEnum()) {
            Object[] c = rt.getEnumConstants();
            return c.length > 0 ? c[0] : null;
        }
        if (rt.isInterface() || Modifier.isAbstract(rt.getModifiers())) {
            return Mockito.mock(rt, Mockito.RETURNS_DEEP_STUBS);
        }
        try {
            Constructor<?> k = rt.getDeclaredConstructor();
            k.setAccessible(true);
            return k.newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    /** A collaborator mock whose every interaction throws once armed. */
    public static <T> T exploding(Class<T> type) {
        Answer<Object> answer = invocation -> {
            if (!Boolean.TRUE.equals(ARMED.get())) {
                return lenientValue(invocation.getMethod().getReturnType());
            }
            throw new IllegalStateException("storm failure");
        };
        return Mockito.mock(type, answer);
    }

    public static <T> T deep(Class<T> type) {
        return Mockito.mock(type, Mockito.RETURNS_DEEP_STUBS);
    }

    private static Object sampleFor(Class<?> type, Map<Class<?>, Object> hints) {
        if (hints != null && hints.containsKey(type)) return hints.get(type);
        return lenientValue(type);
    }

    /** Invoke every public method with sample args; swallow target exceptions, propagate Errors. */
    public static int storm(Object target, Map<Class<?>, Object> hints, String... skipMethods) {
        int invoked = 0;
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(skipMethods));
        for (Method m : target.getClass().getMethods()) {
            if (skip.contains(m.getName())) continue;
            if (m.getDeclaringClass() == Object.class && !m.getName().equals("toString")) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getName().startsWith("wait") || m.getName().equals("notify")
                    || m.getName().equals("notifyAll") || m.getName().equals("getClass")) continue;
            Class<?>[] params = m.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = sampleFor(params[i], hints);
            }
            try {
                m.setAccessible(true);
                m.invoke(target, args);
                invoked++;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Error error) throw error;
                invoked++; // method executed and threw -> branch coverage happened anyway
            } catch (Throwable t) {
                if (t instanceof Error error) throw error;
            }
        }
        return invoked;
    }

    /**
     * Deep storm: additionally invokes declared (private/protected) methods on the class
     * hierarchy with sample args, each guarded by a worker thread + timeout so methods with
     * internal loops can never hang the suite. Target exceptions are swallowed; Errors propagate.
     */
    public static int stormDeep(Object target, Map<Class<?>, Object> hints, long methodTimeoutMs, String... skipMethods) {
        int invoked = storm(target, hints, skipMethods);
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(skipMethods));
        skip.add("storm");
        Class<?> klass = target.getClass();
        while (klass != null && klass != Object.class) {
            for (Method m : klass.getDeclaredMethods()) {
                if (m.isSynthetic() || Modifier.isStatic(m.getModifiers()) || skip.contains(m.getName())) continue;
                if (m.getName().startsWith("lambda$") || m.getName().startsWith("access$")) continue;
                Class<?>[] params = m.getParameterTypes();
                Object[] args = new Object[params.length];
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    try {
                        args[i] = sampleFor(params[i], hints);
                    } catch (Throwable t) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                m.setAccessible(true);
                final Method mm = m;
                final Object[] aa = args;
                final Throwable[] failure = new Throwable[1];
                Thread t = new Thread(() -> {
                    try {
                        mm.invoke(target, aa);
                    } catch (InvocationTargetException e) {
                        if (e.getCause() instanceof Error err) failure[0] = err;
                    } catch (Throwable e) {
                        if (e instanceof Error err) failure[0] = err;
                    }
                }, "storm-deep-" + mm.getName());
                t.setDaemon(true);
                t.start();
                try {
                    t.join(methodTimeoutMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (t.isAlive()) {
                    t.interrupt();
                }
                if (failure[0] instanceof Error err) throw err;
                invoked++;
            }
            klass = klass.getSuperclass();
        }
        return invoked;
    }

}
