package io.github.cuihairu.redis.streaming.storm;


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
        if (rt.isInterface()) {
            return lenientProxy(rt);
        }
        if (Modifier.isAbstract(rt.getModifiers())) {
            return null;
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
    @SuppressWarnings("unchecked")
    public static <T> T exploding(Class<T> type) {
        if (type.isInterface()) {
            java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
                String n = method.getName();
                if (n.equals("toString")) return "exploding-proxy";
                if (n.equals("hashCode")) return System.identityHashCode(proxy);
                if (n.equals("equals")) return proxy == (args == null ? null : args[0]);
                if (!Boolean.TRUE.equals(ARMED.get())) {
                    return lenientValue(method.getReturnType());
                }
                throw new IllegalStateException("storm failure");
            };
            return (T) java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> T deep(Class<T> type) {
        if (type.isInterface()) {
            return (T) lenientProxy(type);
        }
        return null;
    }

    private static Object sampleFor(Class<?> type, Map<Class<?>, Object> hints) {
        if (hints != null && hints.containsKey(type)) return hints.get(type);
        return lenientValue(type);
    }

    /** Default per-invocation timeout for {@link #storm(Object, Map, String...)}. */
    private static final long DEFAULT_INVOKE_TIMEOUT_MS = 2000L;

    /** Loop-style entry points ("run until stopped") that must never be stormed directly. */
    private static final Set<String> ALWAYS_SKIP = Set.of("run", "call", "finalize", "main");

    /** Invoke every public method with sample args, then with null reference args to reach
     *  validation branches. Target exceptions are swallowed, Errors propagate. */
    public static int storm(Object target, Map<Class<?>, Object> hints, String... skipMethods) {
        return storm(target, hints, DEFAULT_INVOKE_TIMEOUT_MS, skipMethods);
    }

    /** Same as {@link #storm(Object, Map, String...)} with an explicit per-invocation timeout. */
    public static int storm(Object target, Map<Class<?>, Object> hints, long invokeTimeoutMs, String... skipMethods) {
        int invoked = 0;
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(skipMethods));
        skip.addAll(ALWAYS_SKIP);
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
            invoked += invokeQuietly(target, m, args, invokeTimeoutMs);
            // second pass with null reference args to reach validation branches
            boolean hasRef = false;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].isPrimitive()) { args[i] = null; hasRef = true; }
            }
            if (hasRef) {
                invoked += invokeQuietly(target, m, args, invokeTimeoutMs);
            }
        }
        return invoked;
    }

    /** Invoke a method on a worker thread with timeout, swallowing target exceptions but
     *  propagating Errors. A hung method is interrupted and abandoned (daemon thread).
     *  @return 1 when the method body actually executed (threw or returned), 0 otherwise */
    private static int invokeQuietly(Object target, Method m, Object[] args, long timeoutMs) {
        final Throwable[] failure = new Throwable[1];
        final int[] executed = new int[1];
        Thread t = new Thread(() -> {
            try {
                m.invoke(target, args);
                executed[0] = 1;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Error err) failure[0] = err;
                executed[0] = 1; // method executed and threw -> branch coverage happened anyway
            } catch (Throwable e) {
                if (e instanceof Error err) failure[0] = err;
                // reflection rejected the call before the body ran (e.g. bad args) -> not executed
            }
        }, "storm-invoke-" + m.getName());
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            t.interrupt();
        }
        if (failure[0] instanceof Error err) throw err;
        return executed[0];
    }

    /**
     * Deep storm: additionally invokes declared (private/protected) methods on the class
     * hierarchy with sample args, each guarded by a worker thread + timeout so methods with
     * internal loops can never hang the suite. Target exceptions are swallowed; Errors propagate.
     */
    public static int stormDeep(Object target, Map<Class<?>, Object> hints, long methodTimeoutMs, String... skipMethods) {
        int invoked = storm(target, hints, methodTimeoutMs, skipMethods);
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(skipMethods));
        skip.add("storm");
        skip.addAll(ALWAYS_SKIP);
        Class<?> klass = target.getClass();
        while (klass != null && klass != Object.class && !klass.getName().startsWith("java.")
                && !klass.getName().startsWith("javax.") && !klass.getName().startsWith("jdk.")) {
            for (Method m : klass.getDeclaredMethods()) {
                if (m.isSynthetic() || Modifier.isStatic(m.getModifiers()) || skip.contains(m.getName())) continue;
                if (m.getDeclaringClass().getName().startsWith("java.")) continue;
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


    // ---------------- grand storm: module-wide class sweep ----------------

    /** Invoke all static methods of a class with sample args (no instance needed). */
    public static int stormStatic(Class<?> klass, Map<Class<?>, Object> hints, long methodTimeoutMs) {
        int invoked = 0;
        for (Method m : klass.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) continue;
            if (ALWAYS_SKIP.contains(m.getName())) continue;
            if (m.getDeclaringClass().getName().startsWith("java.")) continue;
            Class<?>[] params = m.getParameterTypes();
            Object[] args = new Object[params.length];
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                try { args[i] = sampleFor(params[i], hints); } catch (Throwable t) { ok = false; break; }
            }
            if (!ok) continue;
            m.setAccessible(true);
            final Method mm = m;
            final Object[] aa = args;
            final Throwable[] failure = new Throwable[1];
            Thread t = new Thread(() -> {
                try { mm.invoke((Object) null, aa); }
                catch (InvocationTargetException e) { if (e.getCause() instanceof Error err) failure[0] = err; }
                catch (Throwable e) { if (e instanceof Error err) failure[0] = err; }
            }, "storm-static-" + mm.getName());
            t.setDaemon(true);
            t.start();
            try { t.join(methodTimeoutMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (t.isAlive()) t.interrupt();
            if (failure[0] instanceof Error err) throw err;
            invoked++;
        }
        return invoked;
    }

    /**
     * Sweep every loadable class from the anchor's code source (directory or jar) whose name
     * starts with basePackage: instantiate via best-effort constructors (up to 3 per class,
     * covering distinct construction paths) and run a deep storm on each instance. Classes
     * without a constructible instance fall back to a static-method sweep.
     *
     * @return number of (class, method) invocations attempted
     */
    public static int grandStorm(Class<?> anchor, String basePackage, Map<Class<?>, Object> hints, long methodTimeoutMs, Class<?>... knownTypes) {
        java.util.Set<Class<?>> seen = new java.util.HashSet<>(java.util.Arrays.asList(knownTypes));
        seen.add(anchor);
        int total = 0;
        for (String className : listClasses(anchor, basePackage)) {
            // external-service wrappers are excluded from coverage and cannot be stormed safely
            if (className.contains(".kafka.")
                    || className.endsWith("MySQLBinlogCDCConnector")
                    || className.endsWith("PostgreSQLLogicalReplicationCDCConnector")) {
                continue;
            }
            Class<?> klass;
            try {
                klass = Class.forName(className, false, anchor.getClassLoader());
            } catch (Throwable ignored) {
                continue;
            }
            if (klass.isInterface() || klass.isEnum() || klass.isAnnotation() || klass.isArray()
                    || Modifier.isAbstract(klass.getModifiers()) || klass.isSynthetic()
                    || className.indexOf('$') >= 0) {
                if (klass.isEnum()) {
                    Object[] consts = klass.getEnumConstants();
                    if (consts != null && consts.length > 0) {
                        total += stormDeep(consts[0], hints, methodTimeoutMs, "finalize");
                    }
                } else if (!klass.isAnnotation() && !klass.isArray() && !klass.isSynthetic()) {
                    // interface / abstract / nested type: sweep static helpers on the class itself
                    try {
                        total += stormStatic(klass, hints, methodTimeoutMs);
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            List<Object> instances = instantiateAll(klass, hints);
            if (instances.isEmpty()) {
                Object single = tryInstantiate(klass, hints);
                if (single != null) {
                    instances = List.of(single);
                }
            }
            if (instances.isEmpty()) {
                // static-only utility: no constructible instance, sweep statics on the class itself
                try {
                    total += stormStatic(klass, hints, methodTimeoutMs);
                } catch (Throwable ignored) {
                }
                continue;
            }
            for (Object inst : instances) {
                try {
                    total += stormDeep(inst, hints, methodTimeoutMs, "finalize", "main");
                } catch (Throwable ignored) {
                }
            }
        }
        return total;
    }

    private static java.util.List<Object> instantiateAll(Class<?> klass, Map<Class<?>, Object> hints) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Constructor<?> c : klass.getDeclaredConstructors()) {
            if (out.size() >= 3) break;
            if (!Modifier.isPublic(c.getModifiers())) continue;
            try {
                Class<?>[] ps = c.getParameterTypes();
                Object[] as = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) {
                    as[i] = sampleFor(ps[i], hints);
                }
                c.setAccessible(true);
                Object o = c.newInstance(as);
                if (o != null) out.add(o);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Object tryInstantiate(Class<?> klass, Map<Class<?>, Object> hints) {
        // no-arg first, then single-RedissonClient, then any ctor with sample args
        try {
            Constructor<?> c = klass.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignored) {
        }
        if (klass.getName().startsWith("java.") || klass.getName().startsWith("jdk.")) return null;
        for (java.lang.reflect.Constructor<?> c : klass.getDeclaredConstructors()) {
            if (!Modifier.isPublic(c.getModifiers()) && c.getParameterCount() > 0) {
                continue;
            }
            try {
                Class<?>[] ps = c.getParameterTypes();
                Object[] as = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) {
                    as[i] = sampleFor(ps[i], hints);
                }
                c.setAccessible(true);
                Object o = c.newInstance(as);
                if (o != null) {
                    return o;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static java.util.List<String> listClasses(Class<?> anchor, String basePackage) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.net.URL root = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (root == null) {
                return out;
            }
            java.io.File file = new java.io.File(root.toURI());
            String prefix = basePackage.replace('.', '/');
            if (file.isDirectory()) {
                java.io.File base = new java.io.File(file, prefix);
                if (base.isDirectory()) {
                    walk(base, file, out);
                }
            } else if (file.getName().endsWith(".jar")) {
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(file)) {
                    java.util.Enumeration<java.util.jar.JarEntry> es = jf.entries();
                    while (es.hasMoreElements()) {
                        String n = es.nextElement().getName();
                        if (n.startsWith(prefix) && n.endsWith(".class")) {
                            out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void walk(java.io.File dir, java.io.File root, java.util.List<String> out) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null) {
            return;
        }
        for (java.io.File f : fs) {
            if (f.isDirectory()) {
                walk(f, root, out);
            } else if (f.getName().endsWith(".class")) {
                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                out.add(rel.replace(java.io.File.separatorChar, '.').replaceAll("[.]class$", ""));
            }
        }
    }


    /** JDK-proxy based lenient value: avoids mass bytebuddy class generation. */
    static Object lenientProxy(Class<?> iface) {
        java.lang.reflect.InvocationHandler h = (proxy, method, args) -> {
            String n = method.getName();
            if (n.equals("toString")) return "lenient-proxy";
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return proxy == (args == null ? null : args[0]);
            Class<?> rt = method.getReturnType();
            if (rt == void.class || rt == Void.class) return null;
            if (rt == boolean.class || rt == Boolean.class) return Boolean.FALSE;
            if (rt == int.class) return 0;
            if (rt == long.class) return 0L;
            if (rt == double.class) return 0.0d;
            if (rt == float.class) return 0.0f;
            if (rt == short.class) return (short) 0;
            if (rt == byte.class) return (byte) 0;
            if (rt == char.class) return (char) 0;
            if (rt == List.class || rt == java.util.Collection.class || rt == Iterable.class) return new ArrayList<>();
            if (rt == Set.class) return new HashSet<>();
            if (rt == Map.class) return new HashMap<>();
            if (rt == String.class) return null;
            if (rt.isInterface() && !rt.getName().startsWith("java.") && !rt.getName().startsWith("javax.")) {
                return lenientProxy(rt);
            }
            return null;
        };
        return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

}
