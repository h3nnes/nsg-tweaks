package com.nsgmod.band;

import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical (qtrun v4.8.8) class names to runtime class names for the current NSG flavor.
 *
 * <p>On qtrun the mapping is identity. On google-play the obfuscated class names moved
 * to different packages; this class supplies the known high-confidence equivalents and
 * returns {@code null} for classes that are unavailable on gplay.
 */
public final class ClassMapping {

    private static final String TAG = "NSGBandHook";

    private static final Map<String, String> GPLAY_OVERRIDES;
    private static final Set<String> GPLAY_IDENTITY;

    static {
        Set<String> identity = new HashSet<>();
        // Classes confirmed identical in both qtrun and gplay v4.8.8.
        identity.add("v6.b");
        identity.add("v6.f");
        identity.add("v6.e");
        identity.add("v6.a");
        identity.add("v6.d");
        identity.add("k2.a");
        identity.add("e8.b");
        identity.add("e8.a");
        identity.add("h8.b");
        identity.add("h8.c");
        identity.add("a8.b");
        identity.add("y7.a");
        identity.add("u7.a");
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

        // d7/d8 KPI fragments -> m5/m6
        map.put("d7.i", "m5.h");
        map.put("d7.i$c", "m5.h$c");
        map.put("d7.i$k", "m5.h$k");
        map.put("d8.i", "m6.i");

        // misc synthetic / utility renames
        map.put("a4.h", "c6.h");
        map.put("ma.a", "v8.a");

        // g8 matrix fragments -> p6 (g8.b intentionally left unmapped per special case).
        map.put("g8.h", "p6.h");
        map.put("g8.i", "p6.i");

        // Classes intentionally unavailable on gplay.
        map.put("g8.b", null);
        map.put("h8.c", null);
        map.put("t7.t", null);

        // Classes with no confident gplay equivalent found.
        map.put("x6.b", null);

        GPLAY_OVERRIDES = Collections.unmodifiableMap(map);
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
