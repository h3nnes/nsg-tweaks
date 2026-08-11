package com.nsgmod.band;

import android.util.Log;

/**
 * Detects whether the running NSG build is the qtrun or google-play flavor.
 *
 * <p>The detection is based on the obfuscated About-page fragment classes used by each flavor:
 * <ul>
 *   <li>qtrun v4.8.8: {@code t7.t0}</li>
 *   <li>qtrun v4.8.9: {@code zp}</li>
 *   <li>gplay v4.8.8: {@code c6.e1}</li>
 * </ul>
 *
 * <p>Because the class names are hardcoded in the app, we probe for them with the NSG
 * {@link ClassLoader} at module init time and cache the result.
 */
public final class FlavorDetector {

    private static final String TAG = "NSGBandHook";

    public enum Flavor {
        QTRUN, GPLAY, UNKNOWN
    }

    private static volatile Flavor cachedFlavor = null;

    private FlavorDetector() {
    }

    /**
     * Detect the NSG flavor using the supplied class loader.
     *
     * <p>The result is cached after the first call.
     */
    public static Flavor detect(ClassLoader loader) {
        if (cachedFlavor != null) {
            return cachedFlavor;
        }
        synchronized (FlavorDetector.class) {
            if (cachedFlavor != null) {
                return cachedFlavor;
            }
            cachedFlavor = doDetect(loader);
            Log.i(TAG, "FlavorDetector: detected flavor = " + cachedFlavor);
            return cachedFlavor;
        }
    }

    private static Flavor doDetect(ClassLoader loader) {
        if (canLoad(loader, "c6.e1")) {
            return Flavor.GPLAY;
        }
        if (canLoad(loader, "zp")) {
            return Flavor.QTRUN;
        }
        if (canLoad(loader, "t7.t0")) {
            return Flavor.QTRUN;
        }
        String[] gplayAnchors = {"c6.q0", "c6.h1"};
        for (String anchor : gplayAnchors) {
            if (canLoad(loader, anchor)) {
                return Flavor.GPLAY;
            }
        }
        String[] qtrunAnchors = {"ee", "z00", "se0"};
        for (String anchor : qtrunAnchors) {
            if (canLoad(loader, anchor)) {
                return Flavor.QTRUN;
            }
        }
        Log.w(TAG, "FlavorDetector: could not determine flavor, defaulting to QTRUN");
        return Flavor.QTRUN;
    }

    private static boolean canLoad(ClassLoader loader, String className) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isGplay(ClassLoader loader) {
        return detect(loader) == Flavor.GPLAY;
    }

    public static boolean isQtrun(ClassLoader loader) {
        return detect(loader) == Flavor.QTRUN;
    }
}
