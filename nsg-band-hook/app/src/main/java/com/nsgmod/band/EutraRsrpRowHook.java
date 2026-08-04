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

    private static final int   DEEP_BLUE        = 0xff1080e0;
    private static final float RANK_ROW         = 25.0f;
    private static final float RANK_SHIFT_AMOUNT = 4.0f;
    private static final float RANK_BAR_MAX     = 100.0f;
    private static final float MCS_BAR_MAX     = 32.0f;

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

    // v6.f bar color/max setter: f(int color, float max)
    private Method vfFMethod;

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
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> veClass  = ClassMapping.loadClass("v6.e", loader);
            Class<?> vfClass  = ClassMapping.loadClass("v6.f", loader);

            k2aRMethod = ClassMapping.getMethod(k2aClass, "k2.a", "r", loader,
                    float.class, float.class, float.class, float.class);
            k2aSMethod = ClassMapping.getMethod(k2aClass, "k2.a", "s", loader,
                    float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);

            vfFMethod = ClassMapping.getDeclaredMethod(vfClass, "v6.f", "f", loader,
                    int.class, float.class);
            vfFMethod.setAccessible(true);

            sysBClass = ClassMapping.loadClass("com.qtrun.sys.b", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
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
            Class<?> g8iClass = ClassMapping.loadClass("g8.i", loader);
            if (g8iClass == null) {
                Log.i(TAG, "EutraRsrpRowHook: g8.i not available on this flavor, skipping");
                return;
            }
            g8iCarrierCountField = g8iClass.getDeclaredField("Z");
            g8iCarrierCountField.setAccessible(true);

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = ClassMapping.loadClass("v6.a", loader);
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
            Class<?> g8iClass = ClassMapping.loadClass("g8.i", loader);
            if (g8iClass == null) {
                Log.i(TAG, "EutraRsrpRowHook: g8.i not available, skipping n0 hook");
                return;
            }
            Method   n0Method = ClassMapping.getMethod(g8iClass, "g8.i", "n0", loader);

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
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Method   k0Method = ClassMapping.getMethod(v6bClass, "v6.b", "k0", loader, k2aClass);

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

            // Path B/C: shift for Rank3/Rank4 insertion (rows >= 25 by +4).
            if (!isPathA && list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= RANK_ROW) {
                        vaRowField.set(elem, elemRow + RANK_SHIFT_AMOUNT);
                    }
                }
            }

            // MCS Cwd 0/1 insertion: between CQI and Mod.
            float mcsRow;
            float mcsShiftFrom;
            float mcsShiftAmount;
            if (isPathA) {
                mcsRow        = 21.0f;
                mcsShiftFrom  = 21.0f;
                mcsShiftAmount = 1.0f;
            } else {
                mcsRow        = 37.0f;
                mcsShiftFrom  = 37.0f;
                mcsShiftAmount = 2.0f;
            }
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= mcsShiftFrom) {
                        vaRowField.set(elem, elemRow + mcsShiftAmount);
                    }
                }
            }

            // Insert RSRP rows
            if (isPathA) {
                injectRsrpRowPathA(k2aObj, rsrpRow);
            } else if (isPathB) {
                injectRsrpRowPathB(k2aObj, rsrpRow);
            } else {
                injectRsrpRowPathC(k2aObj, rsrpRow);
            }

            // Path B/C: insert Rank3/Rank4 usage rows at row 25
            if (isPathB) {
                injectRankUsageRowPathB(k2aObj, RANK_ROW);
            } else if (!isPathA) {
                injectRankUsageRowPathC(k2aObj, RANK_ROW);
            }

            // Insert MCS Cwd 0/1 rows (after all shifts)
            if (isPathA) {
                injectMcsRowPathA(k2aObj, mcsRow);
            } else if (isPathB) {
                injectMcsRowPathB(k2aObj, mcsRow);
            } else {
                injectMcsRowPathC(k2aObj, mcsRow);
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
    // MCS Cwd 0/1 row injection — between CQI and Mod
    // -----------------------------------------------------------------------

    private void injectMcsRowPathA(Object k2aObj, float row) throws Exception {
        final float h = 1.0f;
        Object label = k2aRMethod.invoke(k2aObj, row, h, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "MCS Cwd 0/1");
            veG.set(label, 0);
            veH.set(label, 1);
        }
        injectMcsBar(k2aObj, row, h, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(k2aObj, row, h, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(k2aObj, row, h, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(k2aObj, row, h, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
    }

    private void injectMcsRowPathB(Object k2aObj, float row) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;
        Object label = k2aRMethod.invoke(k2aObj, row, labelH, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "MCS Cwd 0/1");
            veG.set(label, 0);
            veH.set(label, 1);
        }
        injectMcsBar(k2aObj, row + pcellOff, pcellBarH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(k2aObj, row + pcellOff, pcellBarH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(k2aObj, row, scellBarH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(k2aObj, row, scellBarH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(k2aObj, row + 1.0f, scellBarH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(k2aObj, row + 1.0f, scellBarH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
    }

    private void injectMcsRowPathC(Object k2aObj, float row) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;
        Object label = k2aRMethod.invoke(k2aObj, row, labelH, 0.0f, 27.0f);
        if (label != null) {
            veF.set(label, "MCS Cwd 0/1");
            veG.set(label, 0);
            veH.set(label, 1);
        }
        injectMcsBar(k2aObj, row, barH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(k2aObj, row, barH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(k2aObj, row, barH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(k2aObj, row, barH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
    }

    private void injectMcsBar(Object k2aObj, float row, float h, float col, float w,
                              String key) throws Exception {
        Object bar = k2aSMethod.invoke(k2aObj, row, h, col, w);
        if (bar != null) {
            vfF8120g.set(bar, makeMcsProp(key));
            vfFMethod.invoke(bar, DEEP_BLUE, MCS_BAR_MAX);
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

    private Object makeRankProp(String key, int index) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
        sysAFieldA.set(prop, key);
        sysAFieldB.set(prop, "%.1f %%");
        sysAFieldC.set(prop, index);
        return prop;
    }

    private Object makeMcsProp(String key) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
        sysAFieldA.set(prop, key);
        sysAFieldB.set(prop, "%d");
        sysAFieldC.set(prop, -1);
        return prop;
    }

    private void injectRankUsageRowPathB(Object k2aObj, float startRow) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;

        Object rank3Label = k2aRMethod.invoke(k2aObj, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) {
            veF.set(rank3Label, "Rank3 Usage");
            veG.set(rank3Label, 0);
            veH.set(rank3Label, 1);
        }
        Object rank3PCell = k2aSMethod.invoke(k2aObj, startRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (rank3PCell != null) {
            vfF8120g.set(rank3PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL", -1));
            vfFMethod.invoke(rank3PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell1 = k2aSMethod.invoke(k2aObj, startRow, scellBarH, 65.0f, 34.0f);
        if (rank3SCell1 != null) {
            vfF8120g.set(rank3SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL", -1));
            vfFMethod.invoke(rank3SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell2 = k2aSMethod.invoke(k2aObj, startRow + 1.0f, scellBarH, 65.0f, 34.0f);
        if (rank3SCell2 != null) {
            vfF8120g.set(rank3SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL", -1));
            vfFMethod.invoke(rank3SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }

        float rank4Row = startRow + 2.0f;
        Object rank4Label = k2aRMethod.invoke(k2aObj, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) {
            veF.set(rank4Label, "Rank4 Usage");
            veG.set(rank4Label, 0);
            veH.set(rank4Label, 1);
        }
        Object rank4PCell = k2aSMethod.invoke(k2aObj, rank4Row + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (rank4PCell != null) {
            vfF8120g.set(rank4PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL", -1));
            vfFMethod.invoke(rank4PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell1 = k2aSMethod.invoke(k2aObj, rank4Row, scellBarH, 65.0f, 34.0f);
        if (rank4SCell1 != null) {
            vfF8120g.set(rank4SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL", -1));
            vfFMethod.invoke(rank4SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell2 = k2aSMethod.invoke(k2aObj, rank4Row + 1.0f, scellBarH, 65.0f, 34.0f);
        if (rank4SCell2 != null) {
            vfF8120g.set(rank4SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL", -1));
            vfFMethod.invoke(rank4SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
    }

    private void injectRankUsageRowPathC(Object k2aObj, float startRow) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;

        Object rank3Label = k2aRMethod.invoke(k2aObj, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) {
            veF.set(rank3Label, "Rank3 Usage");
            veG.set(rank3Label, 0);
            veH.set(rank3Label, 1);
        }
        Object rank3PCell = k2aSMethod.invoke(k2aObj, startRow, barH, 30.0f, 34.0f);
        if (rank3PCell != null) {
            vfF8120g.set(rank3PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL", -1));
            vfFMethod.invoke(rank3PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell1 = k2aSMethod.invoke(k2aObj, startRow + 1.0f, barH, 30.0f, 34.0f);
        if (rank3SCell1 != null) {
            vfF8120g.set(rank3SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL", -1));
            vfFMethod.invoke(rank3SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell2 = k2aSMethod.invoke(k2aObj, startRow, barH, 65.0f, 34.0f);
        if (rank3SCell2 != null) {
            vfF8120g.set(rank3SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL", -1));
            vfFMethod.invoke(rank3SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell3 = k2aSMethod.invoke(k2aObj, startRow + 1.0f, barH, 65.0f, 34.0f);
        if (rank3SCell3 != null) {
            vfF8120g.set(rank3SCell3, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL", -1));
            vfFMethod.invoke(rank3SCell3, DEEP_BLUE, RANK_BAR_MAX);
        }

        float rank4Row = startRow + 2.0f;
        Object rank4Label = k2aRMethod.invoke(k2aObj, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) {
            veF.set(rank4Label, "Rank4 Usage");
            veG.set(rank4Label, 0);
            veH.set(rank4Label, 1);
        }
        Object rank4PCell = k2aSMethod.invoke(k2aObj, rank4Row, barH, 30.0f, 34.0f);
        if (rank4PCell != null) {
            vfF8120g.set(rank4PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL", -1));
            vfFMethod.invoke(rank4PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell1 = k2aSMethod.invoke(k2aObj, rank4Row + 1.0f, barH, 30.0f, 34.0f);
        if (rank4SCell1 != null) {
            vfF8120g.set(rank4SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL", -1));
            vfFMethod.invoke(rank4SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell2 = k2aSMethod.invoke(k2aObj, rank4Row, barH, 65.0f, 34.0f);
        if (rank4SCell2 != null) {
            vfF8120g.set(rank4SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL", -1));
            vfFMethod.invoke(rank4SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell3 = k2aSMethod.invoke(k2aObj, rank4Row + 1.0f, barH, 65.0f, 34.0f);
        if (rank4SCell3 != null) {
            vfF8120g.set(rank4SCell3, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL", -1));
            vfFMethod.invoke(rank4SCell3, DEEP_BLUE, RANK_BAR_MAX);
        }
    }
}
