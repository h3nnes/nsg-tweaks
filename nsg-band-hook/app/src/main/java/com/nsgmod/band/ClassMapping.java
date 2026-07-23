package com.nsgmod.band;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical (qtrun v4.8.8) class names, method names and field names to
 * their runtime equivalents for the current NSG flavor.
 *
 * <p>On qtrun the mapping is identity. On google-play the obfuscated names moved
 * to different packages/classes and ProGuard also renamed many methods/fields.
 * This class supplies the known high-confidence equivalents and returns
 * {@code null} for classes that are unavailable on gplay.
 */
public final class ClassMapping {

    private static final String TAG = "NSGBandHook";

    private static final Map<String, String> GPLAY_OVERRIDES;
    private static final Set<String> GPLAY_IDENTITY;

    /** Method-name overrides for gplay: key = "logicalClass|logicalMethod". */
    private static final Map<String, String> GPLAY_METHOD_OVERRIDES;
    /** Field-name overrides for gplay: key = "logicalClass|logicalField". */
    private static final Map<String, String> GPLAY_FIELD_OVERRIDES;
    /** Integer-constant overrides for gplay: key = "logicalClass|logicalConstant". */
    private static final Map<String, Integer> GPLAY_CONSTANT_OVERRIDES;

    static {
        Set<String> identity = new HashSet<>();
        // Classes confirmed identical in both qtrun and gplay v4.8.8.
        identity.add("y7.a");
        identity.add("w1.a");
        identity.add("d.d");
        GPLAY_IDENTITY = Collections.unmodifiableSet(identity);

        Map<String, String> map = new HashMap<>();

        // Package renames (qtrun -> gplay).
        // a8 cell-fragment family -> j6
        map.put("a8.f", "j6.f");
        map.put("a8.f$a", "j6.f$a");
        map.put("a8.h", "j6.h");
        map.put("a8.h$a", "j6.h$a");
        map.put("a8.i", "j6.i");
        map.put("a8.i$a", "j6.i$a");
        map.put("a8.d", "j6.d");
        map.put("a8.d$b", "j6.d$b");
        map.put("a8.b", "j6.b");
        map.put("a8.b$a", "j6.b$a");
        map.put("a8.b$b", "j6.b$b");

        // v6 grid-builder family -> e5 (entire package moved in gplay)
        map.put("v6.a", "e5.a");
        map.put("v6.b", "e5.b");
        map.put("v6.e", "e5.e");
        map.put("v6.f", "e5.f");
        map.put("v6.g", "e5.g");
        map.put("v6.d", "e5.d");

        // k2.a grid builder -> b6.e (gplay k2.a is a different interface)
        map.put("k2.a", "b6.e");

        // LTE / NR-SA / NR-NSA matrix fragments moved to n6 / q6 / p6 on gplay.
        // qtrun e8 package is ACRA on gplay; h8 package is ACRA on gplay.
        map.put("e8.b", "n6.b");
        map.put("e8.a", "n6.a");
        map.put("h8.b", "q6.b");

        // k8 adapter/playback family -> t6
        map.put("k8.c", "t6.c");
        map.put("k8.f", "t6.f");

        // t7 settings/menu family -> c6
        map.put("t7.g0", "c6.q0");
        map.put("t7.e0", "c6.n0");
        map.put("t7.p", "c6.v");
        map.put("t7.w0", "c6.h1");
        map.put("t7.e", "c6.f");

        // u7 signaling -> d6
        map.put("u7.f", "d6.g");
        map.put("u7.a", "d6.a");

        // d7/d8 KPI fragments -> m5/m6
        map.put("d7.i", "m5.h");
        map.put("d7.i$c", "m5.h$c");
        map.put("d7.i$k", "m5.h$k");
        map.put("d8.i", "m6.i");

        // misc synthetic / utility renames
        map.put("a4.h", "c6.h");
        map.put("ma.a", "z8.a");

        // g8 matrix fragments -> p6 (g8.b intentionally left unmapped per special case).
        map.put("g8.h", "p6.h");
        map.put("g8.i", "p6.i");

        // NR-NSA / NR-SA CA Matrix UL fragments moved to p6 / q6 on gplay.
        map.put("g8.b", "p6.b");
        map.put("h8.c", "q6.c");

        // Experiments settings fragment moved to c6.z on gplay.
        map.put("t7.t", "c6.z");

        // AdvancedAdapter moved from x6.b to g5.b on gplay.
        map.put("x6.b", "g5.b");

        GPLAY_OVERRIDES = Collections.unmodifiableMap(map);

        Map<String, String> methodMap = new HashMap<>();
        // k2.a builder methods: qtrun r/s/t -> gplay n/o/p; j -> f
        methodMap.put("k2.a|r", "n");
        methodMap.put("k2.a|s", "o");
        methodMap.put("k2.a|t", "p");
        methodMap.put("k2.a|j", "f");
        // v6.b static factory: qtrun k0(k2.a) -> gplay j0(b6.e)
        methodMap.put("v6.b|k0", "j0");
        // PlaybackControlsFragment seekbar sync: qtrun i0(float) -> gplay h0(float)
        methodMap.put("k8.f|i0", "h0");
        // LTE CA Matrix DL builder method: qtrun e8.b.n0() -> gplay n6.b.m0()
        methodMap.put("e8.b|n0", "m0");
        // NR-NSA EUTRA CA Matrix DL builder method: qtrun g8.i.n0() -> gplay p6.i.m0()
        methodMap.put("g8.i|n0", "m0");
        // NR-SA CA Matrix DL builder method: qtrun h8.b.o0() -> gplay q6.b.n0()
        methodMap.put("h8.b|o0", "n0");
        methodMap.put("h8.b|Q", "Q");
        // NR-NSA / NR-SA CA Matrix UL builder method: qtrun l0(Context) -> gplay k0(Context)
        methodMap.put("g8.b|l0", "k0");
        methodMap.put("h8.c|l0", "k0");
        // NR-SA KPI fragment builder method: qtrun d8.i.l0(Context) -> gplay m6.i.k0(Context)
        methodMap.put("d8.i|l0", "k0");
        // LTE CA Matrix UL builder method: qtrun e8.a.l0(Context) -> gplay n6.a.k0(Context)
        methodMap.put("e8.a|l0", "k0");
        // Cell-DB WHERE-clause builder: qtrun ma.a.j(...) -> gplay z8.a.h(...)
        methodMap.put("ma.a|j", "h");
        // Timestamp formatter: qtrun ma.a.m(Date) -> gplay v8.a.c(Date)
        methodMap.put("ma.a|m", "c");
        // Note: ma.a in qtrun is Installation.kt; on gplay its functionality is split.
        // The timestamp formatter lives in v8.a.c(Date), so hooks that need it fall
        // back to v8.a directly (see GranularSeekBarHook / RtPlayHook).
        // Experiments settings fragment: qtrun t7.t.i0(String) -> gplay c6.z.h0(String)
        methodMap.put("t7.t|i0", "h0");
        // ActivityResultLauncher launch: qtrun d.d.c(Object) -> gplay d.d.a(Object)
        methodMap.put("d.d|c", "a");
        GPLAY_METHOD_OVERRIDES = Collections.unmodifiableMap(methodMap);

        Map<String, String> fieldMap = new HashMap<>();
        // v6.d internal element list: qtrun "a" -> gplay "b"
        fieldMap.put("v6.d|a", "b");
        // a8.d$b sources array: qtrun "d" -> gplay "e"
        fieldMap.put("a8.d$b|d", "e");
        // a8.b$b sources array: qtrun "e" -> gplay "f"
        fieldMap.put("a8.b$b|e", "f");
        // k8.c current sample key: qtrun "c" -> gplay "d"
        fieldMap.put("k8.c|c", "d");
        // k8.c per-bucket count: qtrun "d" -> gplay "e"
        fieldMap.put("k8.c|d", "e");
        // u7.a synthetic click-listener fields: qtrun a/b -> gplay b/c
        fieldMap.put("u7.a|a", "b");
        fieldMap.put("u7.a|b", "c");
        // com.qtrun.sys.Workspace static/instance fields:
        // qtrun singleton j, moduleIndex a, listeners b, dispatcher d, modules e,
        //           timestamp attr f, current key g, max key h, current date i
        // gplay   singleton k, moduleIndex b, listeners c, dispatcher e, modules f,
        //           timestamp attr g, current key h, max key i, current date j
        fieldMap.put("com.qtrun.sys.Workspace|j", "k");
        fieldMap.put("com.qtrun.sys.Workspace|a", "b");
        fieldMap.put("com.qtrun.sys.Workspace|b", "c");
        fieldMap.put("com.qtrun.sys.Workspace|c", "d");
        fieldMap.put("com.qtrun.sys.Workspace|d", "e");
        fieldMap.put("com.qtrun.sys.Workspace|e", "f");
        fieldMap.put("com.qtrun.sys.Workspace|f", "g");
        fieldMap.put("com.qtrun.sys.Workspace|g", "h");
        fieldMap.put("com.qtrun.sys.Workspace|h", "i");
        fieldMap.put("com.qtrun.sys.Workspace|i", "j");
        // a4.h synthetic load-completion fields (gplay c6.h): qtrun a/b/c -> gplay b/d/c
        fieldMap.put("a4.h|a", "b");
        fieldMap.put("a4.h|b", "d");
        fieldMap.put("a4.h|c", "c");
        // t7.e synthetic file-picker fields (gplay c6.f): qtrun a/b -> gplay b/c
        fieldMap.put("t7.e|a", "b");
        fieldMap.put("t7.e|b", "c");
        // CirclePageIndicator fields on gplay: a->b, e->f, g->h, l->m, q->r (k stays the same)
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|a", "b");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|e", "f");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|g", "h");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|l", "m");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|q", "r");
        // com.qtrun.sys.a (Attribute) fields are identical in both flavors, but keep
        // explicit identity entries so hooks can use runtimeFieldName safely.
        fieldMap.put("com.qtrun.sys.a|a", "a");
        fieldMap.put("com.qtrun.sys.a|b", "b");
        fieldMap.put("com.qtrun.sys.a|c", "c");
        fieldMap.put("com.qtrun.sys.a|d", "d");
        GPLAY_FIELD_OVERRIDES = Collections.unmodifiableMap(fieldMap);

        Map<String, Integer> constantMap = new HashMap<>();
        // a4.h / c6.h synthetic switch case used for log-file load completion.
        // qtrun R8 assigns case 5; gplay R8 assigns case 0.
        constantMap.put("a4.h|LOAD_COMPLETE_CASE", 0);
        GPLAY_CONSTANT_OVERRIDES = Collections.unmodifiableMap(constantMap);
    }

    private ClassMapping() {
    }

    /**
     * Returns the runtime class name for the given logical class name, or {@code null}
     * if the class is not mapped for the current flavor.
     */
    public static String runtimeName(String logicalName, ClassLoader loader) {
        FlavorDetector.Flavor flavor = FlavorDetector.detect(loader);
        if (flavor == FlavorDetector.Flavor.QTRUN) {
            return logicalName;
        }
        if (flavor != FlavorDetector.Flavor.GPLAY) {
            return logicalName;
        }

        if (GPLAY_OVERRIDES.containsKey(logicalName)) {
            return GPLAY_OVERRIDES.get(logicalName);
        }

        // Classes confirmed identical in both flavors.
        if (GPLAY_IDENTITY.contains(logicalName)) {
            return logicalName;
        }

        // Classes that are not obfuscated / are stable across flavors (AndroidX, SDK,
        // com.qtrun.sys, com.qtrun.nsg, etc.) keep their logical name on gplay.
        if (isLikelyStable(logicalName)) {
            return logicalName;
        }

        return null;
    }

    private static boolean isLikelyStable(String logicalName) {
        return logicalName.startsWith("android.")
                || logicalName.startsWith("androidx.")
                || logicalName.startsWith("java.")
                || logicalName.startsWith("javax.")
                || logicalName.startsWith("com.qtrun.")
                || logicalName.startsWith("kotlin.")
                || logicalName.startsWith("org.");
    }

    /**
     * Returns the runtime method name for the given logical class and method,
     * or {@code logicalMethod} if no gplay override exists.
     */
    public static String runtimeMethodName(String logicalClass, String logicalMethod,
                                           ClassLoader loader) {
        if (FlavorDetector.detect(loader) != FlavorDetector.Flavor.GPLAY) {
            return logicalMethod;
        }
        String key = logicalClass + "|" + logicalMethod;
        if (GPLAY_METHOD_OVERRIDES.containsKey(key)) {
            return GPLAY_METHOD_OVERRIDES.get(key);
        }
        return logicalMethod;
    }

    /**
     * Returns the runtime field name for the given logical class and field,
     * or {@code logicalField} if no gplay override exists.
     */
    public static String runtimeFieldName(String logicalClass, String logicalField,
                                        ClassLoader loader) {
        if (FlavorDetector.detect(loader) != FlavorDetector.Flavor.GPLAY) {
            return logicalField;
        }
        String key = logicalClass + "|" + logicalField;
        if (GPLAY_FIELD_OVERRIDES.containsKey(key)) {
            return GPLAY_FIELD_OVERRIDES.get(key);
        }
        return logicalField;
    }

    /**
     * Returns the runtime int constant for the given logical key, or
     * {@code qtrunValue} if no gplay override exists.
     */
    public static int runtimeIntConstant(String logicalKey, int qtrunValue,
                                          ClassLoader loader) {
        if (FlavorDetector.detect(loader) != FlavorDetector.Flavor.GPLAY) {
            return qtrunValue;
        }
        if (GPLAY_CONSTANT_OVERRIDES.containsKey(logicalKey)) {
            return GPLAY_CONSTANT_OVERRIDES.get(logicalKey);
        }
        return qtrunValue;
    }

    /**
     * Looks up a method on {@code runtimeClass} using the flavor-aware method name.
     * Useful when the gplay ProGuard renaming changed a method name but the
     * parameter types stayed the same.
     */
    public static Method getMethod(Class<?> runtimeClass, String logicalClass,
                                   String logicalMethod, ClassLoader loader,
                                   Class<?>... parameterTypes) throws NoSuchMethodException {
        String runtimeMethod = runtimeMethodName(logicalClass, logicalMethod, loader);
        return runtimeClass.getMethod(runtimeMethod, parameterTypes);
    }

    /**
     * Looks up a declared method on {@code runtimeClass} using the flavor-aware
     * method name.
     */
    public static Method getDeclaredMethod(Class<?> runtimeClass, String logicalClass,
                                           String logicalMethod, ClassLoader loader,
                                           Class<?>... parameterTypes) throws NoSuchMethodException {
        String runtimeMethod = runtimeMethodName(logicalClass, logicalMethod, loader);
        return runtimeClass.getDeclaredMethod(runtimeMethod, parameterTypes);
    }

    /**
     * Returns {@code true} if the logical class is mapped and can be loaded.
     */
    public static boolean hasClass(String logicalName, ClassLoader loader) {
        return loadClass(logicalName, loader) != null;
    }

    /**
     * Loads the class for the given logical name using the supplied class loader.
     *
     * <p>Returns {@code null} and logs a warning if the class is not mapped or cannot
     * be loaded.
     */
    public static Class<?> loadClass(String logicalName, ClassLoader loader) {
        String runtimeName = runtimeName(logicalName, loader);
        if (runtimeName == null) {
            Log.w(TAG, "ClassMapping: no mapping for logical class " + logicalName
                    + " on flavor " + FlavorDetector.detect(loader));
            return null;
        }
        try {
            return Class.forName(runtimeName, false, loader);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "ClassMapping: could not load runtime class " + runtimeName
                    + " for logical " + logicalName + ": " + e);
            return null;
        }
    }
}
