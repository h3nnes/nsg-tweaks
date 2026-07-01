package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds an "RSRP" row immediately below the "Band/Width" row (above "SINR") on the
 * NR-NSA EUTRA CA Matrix DL page (g8.i), showing the RSRP value for PCell and each SCell.
 *
 * g8.i is a parallel class to e8.b (LTE CA Matrix DL) with identical row geometry and
 * method/field names. Only the class name differs.
 *
 * Architecture mirrors LteRsrpRowHook exactly:
 *   Hook g8.i.n0() to set a ThreadLocal<Integer> with the carrier count (field g8.i.Z).
 *   Hook static v6.b.k0(k2.a) — when ThreadLocal is set, inject the RSRP row.
 *
 * Row geometry per carrier path (identical to LteRsrpRowHook):
 *
 *   Path A  Z==1 or Z==2  (1 SCell, k0())  — single-height rows (h=1.0)
 *     Band/Width at row 10.  SINR at row 11.
 *     → Insert RSRP at row 11, shift ≥11 by +1.0.
 *
 *   Path B  Z==3  (2 SCells, inline n0())  — double-height rows (h=2.0)
 *     Band/Width at row 11 (h=2).  SINR at row 13 (h=2).
 *     → Insert RSRP at row 13, shift ≥13 by +2.0.
 *
 *   Path C  Z>=4  (3 SCells, l0())  — double-height label (h=2.0), single-height bars (h=1.0)
 *     Band/Width label at row 11 (h=2.0), bars at rows 11/12.  SINR label at row 13 (h=2.0), bars at rows 13/14.
 *     → Insert RSRP at row 13, shift ≥13 by +2.0.
 *
 * Property keys: identical to LteRsrpRowHook (same LTE RSRP keys).
 */
public class EutraRsrpRowHook {

    private static final String TAG = "NSGBandHook";

    /** Set by the g8.i.n0() flag hook while n0() executes; null otherwise. */
    static final ThreadLocal<Integer> carrierCountInN0 = new ThreadLocal<>();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod;
    private Method k2aSMethod;

    // v6.e label fields
    private Field veF;
    private Field veG;
    private Field veH;

    // v6.f bar data-binding field
    private Field vfF8120g;

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field sysAFieldA;
    private Field sysAFieldB;
    private Field sysAFieldC;

    // Unsafe for allocateInstance
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // g8.i carrier count field — bytecode name "Z"
    private Field g8iCarrierCountField;

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    private boolean ready = false;

    public EutraRsrpRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = loader.loadClass("k2.a");
            Class<?> veClass  = loader.loadClass("v6.e");
            Class<?> vfClass  = loader.loadClass("v6.f");

            k2aRMethod = k2aClass.getMethod("r", float.class, float.class, float.class, float.class);
            k2aSMethod = k2aClass.getMethod("s", float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);

            sysBClass = loader.loadClass("com.qtrun.sys.b");
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");   // Android/Dalvik
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe"); // OpenJDK fallback
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            // g8.i.Z = carrier count (int field, bytecode name "Z")
            Class<?> g8iClass = loader.loadClass("g8.i");
            g8iCarrierCountField = g8iClass.getDeclaredField("Z");
            g8iCarrierCountField.setAccessible(true);

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = loader.loadClass("v6.a");
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "skipping install — reflection not ready");
            return;
        }
        installN0FlagHook();
        installV6bK0Hook();
        Log.i(TAG, "EutraRsrpRowHook: installed");
    }

    // -----------------------------------------------------------------------
    // Hook 1: g8.i.n0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private void installN0FlagHook() {
        try {
            Class<?> g8iClass = loader.loadClass("g8.i");
            Method   n0Method = g8iClass.getMethod("n0");

            xposed.hook(n0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int carriers = -1;
                    try {
                        carriers = (int) g8iCarrierCountField.get(chain.getThisObject());
                    } catch (Exception e) {
                        Log.w(TAG, "could not read carrier count: " + e);
                    }
                    carrierCountInN0.set(carriers);
                    try {
                        return chain.proceed();
                    } finally {
                        carrierCountInN0.remove();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "n0 flag hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: static v6.b.k0(k2.a) — inject RSRP row when called from g8.i.n0()
    // -----------------------------------------------------------------------

    private void installV6bK0Hook() {
        try {
            Class<?> v6bClass = loader.loadClass("v6.b");
            Class<?> k2aClass = loader.loadClass("k2.a");
            Method   k0Method = v6bClass.getMethod("k0", k2aClass);

            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object  k2aArg   = chain.getArg(0);
                    Integer carriers = carrierCountInN0.get();
                    boolean inN0     = carriers != null;
                    if (inN0 && k2aArg != null) {
                        injectRsrpRow(k2aArg, carriers);
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "v6.b.k0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Injection: insert RSRP row below Band/Width, above SINR
    // -----------------------------------------------------------------------

    private void injectRsrpRow(Object k2aObj, int carriers) {
        try {
            boolean isPathA = (carriers == 1 || carriers == 2);
            boolean isPathB = (carriers == 3);

            float rsrpRow;
            float shiftFrom;
            float shiftAmount;

            if (isPathA) {
                rsrpRow     = 11.0f;
                shiftFrom   = 11.0f;
                shiftAmount = 1.0f;  // one h=1.0 row inserted → shift by 1
            } else {
                // Path B: one h=2.0 row; Path C: two h=1.0 rows → shift by 2
                rsrpRow     = 13.0f;
                shiftFrom   = 13.0f;
                shiftAmount = 2.0f;
            }

            // Shift all existing elements at or after insertion point
            java.util.ArrayList<?> list =
                    (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= shiftFrom) {
                        vaRowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            if (isPathA) {
                injectRsrpRowPathA(k2aObj, rsrpRow);
            } else if (isPathB) {
                injectRsrpRowPathB(k2aObj, rsrpRow);
            } else {
                injectRsrpRowPathC(k2aObj, rsrpRow);
            }

        } catch (Exception e) {
            Log.w(TAG, "injectRsrpRow failed: " + e);
        }
    }

    private void injectRsrpRowPathA(Object k2aObj, float rsrpRow) throws Exception {
        final float h = 1.0f;

        Object label = k2aRMethod.invoke(k2aObj, rsrpRow, h, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "RSRP");
            veG.set(label, 0);
            veH.set(label, 1);
        }

        Object pCellBar = k2aSMethod.invoke(k2aObj, rsrpRow, h, 30.0f, 34.0f);
        if (pCellBar != null) {
            vfF8120g.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = k2aSMethod.invoke(k2aObj, rsrpRow, h, 65.0f, 34.0f);
        if (sCell1Bar != null) {
            vfF8120g.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }
    }

    private void injectRsrpRowPathB(Object k2aObj, float rsrpRow) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;

        Object label = k2aRMethod.invoke(k2aObj, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "RSRP");
            veG.set(label, 0);
            veH.set(label, 1);
        }

        Object pCellBar = k2aSMethod.invoke(k2aObj, rsrpRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (pCellBar != null) {
            vfF8120g.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = k2aSMethod.invoke(k2aObj, rsrpRow, scellBarH, 65.0f, 34.0f);
        if (sCell1Bar != null) {
            vfF8120g.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }

        Object sCell2Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, scellBarH, 65.0f, 34.0f);
        if (sCell2Bar != null) {
            vfF8120g.set(sCell2Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));
        }
    }

    private void injectRsrpRowPathC(Object k2aObj, float rsrpRow) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;

        Object label = k2aRMethod.invoke(k2aObj, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "RSRP");
            veG.set(label, 0);
            veH.set(label, 1);
        }

        Object pCellBar = k2aSMethod.invoke(k2aObj, rsrpRow, barH, 30.0f, 34.0f);
        if (pCellBar != null) {
            vfF8120g.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, barH, 30.0f, 34.0f);
        if (sCell1Bar != null) {
            vfF8120g.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }

        Object sCell2Bar = k2aSMethod.invoke(k2aObj, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell2Bar != null) {
            vfF8120g.set(sCell2Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));
        }

        Object sCell3Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) {
            vfF8120g.set(sCell3Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));
        }
    }

    // -----------------------------------------------------------------------
    // Helper: allocate com.qtrun.sys.b via Unsafe and set key/format/index
    // -----------------------------------------------------------------------

    private Object makeProp(String key, int index) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
        sysAFieldA.set(prop, key);
        sysAFieldB.set(prop, "%.1f dBm");
        sysAFieldC.set(prop, index);
        return prop;
    }
}
