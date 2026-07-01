package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds an "RSRP" row immediately below the "Band/Width" row (above "SINR") on the
 * LTE CA Matrix DL page (e8.b), showing the RSRP value for PCell and each SCell.
 *
 * LTE has a single RSRP value per cell (no SS-RSRP / CSI-RSRP distinction).
 *
 * Architecture mirrors NrSaRsrpRowHook / NrSaCsiSnrRowHook:
 *   e8.b.n0() dispatches to k0(d1.g) [Z==1/2], inline code [Z==3], or l0(d1.g) [Z>=4],
 *   all paths end with v6.b.k0(k2.a).
 *
 * Strategy:
 *   Hook e8.b.n0() to set a ThreadLocal<Integer> with the carrier count (field e8.b.Z).
 *   Hook static v6.b.k0(k2.a) — when ThreadLocal is set, inject the RSRP row.
 *
 * Row geometry per carrier path:
 *
 *   Path A  Z==1 or Z==2  (1 SCell, k0())  — single-height rows (h=1.0)
 *     Band/Width at row 10.  SINR at row 11.
 *     → Insert RSRP at row 11, shift ≥11 by +2.0.
 *     Label  col=0  w=27
 *     PCell  col=30 w=34  key=LTE_RSRP_PCell     index=-1
 *     SCell1 col=65 w=34  key=LTE_RSRP_SCell1    index=-1
 *
 *   Path B  Z==3  (2 SCells, inline n0())  — double-height rows (h=2.0)
 *     Band/Width at row 11 (h=2).  SINR at row 13 (h=2).
 *     → Insert RSRP at row 13, shift ≥13 by +2.0 (one logical h=2 row).
 *     Label      row=13  h=2.0  col=0  w=27
 *     PCell bar  row=13.3 h=1.4  col=30 w=34  key=LTE_RSRP_PCell  index=-1
 *     SCell1 bar row=13.0 h=1.0  col=65 w=34  key=LTE_RSRP_SCell1 index=-1
 *     SCell2 bar row=14.0 h=1.0  col=65 w=34  key=LTE_RSRP_SCell2 index=-1
 *
 *   Path C  Z>=4  (3 SCells, l0())  — single-height rows (h=1.0)
 *     Band/Width at rows 11–12 (h=1 each).  SINR at rows 13–14 (h=1 each).
 *     → Insert RSRP at row 13, shift ≥13 by +2.0 (two h=1 sub-rows).
 *     Label      row=13  h=2.0  col=0  w=27
 *     PCell  bar row=13  h=1.0  col=30 w=34  key=LTE_RSRP_PCell  index=-1
 *     SCell1 bar row=14  h=1.0  col=30 w=34  key=LTE_RSRP_SCell1 index=-1
 *     SCell2 bar row=13  h=1.0  col=65 w=34  key=LTE_RSRP_SCell2 index=-1
 *     SCell3 bar row=14  h=1.0  col=65 w=34  key=LTE_RSRP_SCell3 index=-1
 *
 * Property keys:
 *   PCell : LTE::Downlink_Measurements::LTE_RSRP_PCell            index=-1  format="%.1f dBm"
 *   SCell1: LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1      index=-1  format="%.1f dBm"
 *   SCell2: LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2      index=-1  format="%.1f dBm"
 *   SCell3: LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3      index=-1  format="%.1f dBm"
 */
public class LteRsrpRowHook {

    private static final String TAG = "NSGBandHook";

    /** Set by the e8.b.n0() flag hook while n0() executes; null otherwise. */
    static final ThreadLocal<Integer> carrierCountInN0 = new ThreadLocal<>();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) → v6.e  (label)
    private Method k2aSMethod; // s(float row, float h, float col, float w) → v6.f  (bar)

    // v6.e label fields (actual bytecode names)
    private Field veF; // text   (JADX: f8116f)
    private Field veG; // align  (JADX: f8117g)
    private Field veH; // span

    // v6.f bar data-binding field
    private Field vfF8120g; // g (JADX: f8120g) — data binding

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field sysAFieldA; // final String key
    private Field sysAFieldB; // final String format
    private Field sysAFieldC; // int index

    // Unsafe for allocateInstance (com.qtrun.sys.b ctor stripped by ProGuard)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // e8.b carrier count field — actual bytecode name "Z"
    private Field e8bCarrierCountField;

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    private boolean ready = false;

    public LteRsrpRowHook(XposedInterface xposed, ClassLoader loader) {
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

            // e8.b.Z = carrier count (int field, bytecode name "Z")
            Class<?> e8bClass = loader.loadClass("e8.b");
            e8bCarrierCountField = e8bClass.getDeclaredField("Z");
            e8bCarrierCountField.setAccessible(true);

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
        Log.i(TAG, "LteRsrpRowHook: installed");
    }

    // -----------------------------------------------------------------------
    // Hook 1: e8.b.n0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private void installN0FlagHook() {
        try {
            Class<?> e8bClass = loader.loadClass("e8.b");
            Method   n0Method = e8bClass.getMethod("n0");

            xposed.hook(n0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int carriers = -1;
                    try {
                        carriers = (int) e8bCarrierCountField.get(chain.getThisObject());
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
    // Hook 2: static v6.b.k0(k2.a) — inject RSRP row when called from e8.b.n0()
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
            // carriers >= 4: Path C

            // Path A: Band/Width row=10, SINR row=11
            //   → insert RSRP at 11, shift ≥11 by +2 (label h=1, one bar row each side)
            // Path B: Band/Width row=11 (h=2), SINR row=13 (h=2)
            //   → insert RSRP label h=2 at 13, shift ≥13 by +2
            // Path C: Band/Width rows 11–12 (h=1 each), SINR rows 13–14 (h=1 each)
            //   → insert RSRP label h=2 at 13, shift ≥13 by +2

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

    /**
     * Path A: Z==1 or Z==2 (1 SCell), single-height rows (h=1.0).
     *
     * RSRP row at rsrpRow:
     *   label col=0 w=27
     *   PCell bar col=30 w=34  key=LTE_RSRP_PCell  index=-1
     *   SCell1 bar col=65 w=34  key=LTE_RSRP_SCell1 index=-1
     */
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

    /**
     * Path B: Z==3 (2 SCells), double-height rows (label h=2.0, PCell barOffset=+0.3 h=1.4).
     * SCell bars stack at rsrpRow and rsrpRow+1 in col=65.
     *
     * RSRP label row=rsrpRow h=2.0 col=0 w=27
     * PCell bar  row=rsrpRow+0.3 h=1.4 col=30 w=34
     * SCell1 bar row=rsrpRow     h=1.0 col=65 w=34
     * SCell2 bar row=rsrpRow+1   h=1.0 col=65 w=34
     */
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

    /**
     * Path C: Z>=4 (3 SCells), single-height rows (h=1.0).
     * Left panel (col=30): PCell at rsrpRow, SCell1 at rsrpRow+1.
     * Right panel (col=65): SCell2 at rsrpRow, SCell3 at rsrpRow+1.
     *
     * RSRP label row=rsrpRow h=2.0 col=0 w=27
     * PCell  bar row=rsrpRow   h=1.0 col=30 w=34
     * SCell1 bar row=rsrpRow+1 h=1.0 col=30 w=34
     * SCell2 bar row=rsrpRow   h=1.0 col=65 w=34
     * SCell3 bar row=rsrpRow+1 h=1.0 col=65 w=34
     */
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
