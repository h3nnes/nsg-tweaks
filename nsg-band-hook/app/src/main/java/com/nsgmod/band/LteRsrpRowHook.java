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
 * Architecture mirrors SACAMatrixDLHook / NrSaCsiSnrRowHook:
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

    /** NSG R.color.color_deep_blue = #ff1080e0 (ARGB), used for Rank3/Rank4 bars. */
    private static final int DEEP_BLUE = 0xff1080e0;
    /** Row at which Rank3/Rank4 usage rows are inserted (after the RSRP shift). */
    private static final float RANK_ROW = 25.0f;
    /** Rank3/Rank4 insertion shifts existing rows >= RANK_ROW by this amount
     *  (two rowspan-2 logical rows = 4 sub-rows). */
    private static final float RANK_SHIFT_AMOUNT = 4.0f;
    /** Max value for Rank usage bars (percentage). */
    private static final float RANK_BAR_MAX = 100.0f;
    private static final float MCS_BAR_MAX = 32.0f;

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

    // v6.f bar color/max setter: f(int color, float max) enables fixed-color bar mode
    private Method vfFMethod;

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

            // v6.f.f(int color, float max) — sets bar color + max, enables bar mode.
            // Method name is "f" on both qtrun (v6.f) and gplay (e5.f).
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

            // e8.b.Z = carrier count (int field, bytecode name "Z")
            Class<?> e8bClass = ClassMapping.loadClass("e8.b", loader);
            e8bCarrierCountField = e8bClass.getDeclaredField("Z");
            e8bCarrierCountField.setAccessible(true);

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
        Log.i(TAG, "LteRsrpRowHook: installed");
    }

    // -----------------------------------------------------------------------
    // Hook 1: e8.b.n0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private void installN0FlagHook() {
        try {
            Class<?> e8bClass = ClassMapping.loadClass("e8.b", loader);
            Method   n0Method = ClassMapping.getMethod(e8bClass, "e8.b", "n0", loader);

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
            // carriers >= 4: Path C

            // Path A: Band/Width row=10, SINR row=11
            //   → insert RSRP at 11, shift ≥11 by +2 (label h=1, one bar row each side)
            // Path B: Band/Width row=11 (h=2), SINR row=13 (h=2)
            //   → insert RSRP label h=2 at 13, shift ≥13 by +2
            // Path C: Band/Width rows 11–12 (h=1 each), SINR rows 13–14 (h=1 each)
            //   → insert RSRP label h=2 at 13, shift ≥13 by +2
            //
            // Path B/C also inject Rank3/Rank4 usage rows at row 25 (between
            // Spatial Rank and Thpt Cwd0), shifting rows ≥25 by +4.

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

            // Shift all existing elements at or after RSRP insertion point
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

            // Path B/C: shift for Rank3/Rank4 insertion (rows ≥25 by +4).
            // Runs after the RSRP shift so Thpt Cwd0 (orig 23-24 → 25-26 after
            // RSRP shift) moves to 29-30, leaving rows 25-28 free for Rank3/Rank4.
            if (!isPathA && list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= RANK_ROW) {
                        vaRowField.set(elem, elemRow + RANK_SHIFT_AMOUNT);
                    }
                }
            }

            // MCS Cwd 0/1 insertion: between CQI and Mod.
            // Path A: CQI at 20 (orig 19 +RSRP+1), Mod at 21 → insert MCS at 21, shift ≥21 by +1
            // Path B/C: CQI at 35-36, Mod at 37-38 → insert MCS at 37, shift ≥37 by +2
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

            // Path B/C: insert Rank3/Rank4 usage rows at row 25 (after both shifts)
            if (isPathB) {
                injectRankUsageRowPathB(k2aObj, RANK_ROW);
            } else if (!isPathA) {
                // Path C (Z >= 4)
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
    // Rank3/Rank4 usage row injection (Path B and Path C only)
    // -----------------------------------------------------------------------

    /**
     * Path B: Z==3 (2 SCells), double-height rows (label h=2.0, PCell barOffset=+0.3 h=1.4).
     * SCell bars stack at startRow and startRow+1 in col=65.  Rank4 at startRow+2.
     *
     * Rank3 Usage label row=startRow     h=2.0 col=0  w=27
     * Rank3 PCell bar  row=startRow+0.3  h=1.4 col=30 w=34  key=LTE_Rank3_Usage_PCell
     * Rank3 SCell1 bar row=startRow      h=1.0 col=65 w=34  key=LTE_Rank3_Usage_SCell1
     * Rank3 SCell2 bar row=startRow+1    h=1.0 col=65 w=34  key=LTE_Rank3_Usage_SCell2
     *
     * Rank4 Usage label row=startRow+2   h=2.0 col=0  w=27
     * Rank4 PCell bar  row=startRow+2.3  h=1.4 col=30 w=34  key=LTE_Rank4_Usage_PCell
     * Rank4 SCell1 bar row=startRow+2    h=1.0 col=65 w=34  key=LTE_Rank4_Usage_SCell1
     * Rank4 SCell2 bar row=startRow+3    h=1.0 col=65 w=34  key=LTE_Rank4_Usage_SCell2
     *
     * Bars use v6.f.f(DEEP_BLUE, 100.0f) for fixed-color percentage bars.
     */
    private void injectRankUsageRowPathB(Object k2aObj, float startRow) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;

        // Rank3 Usage
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

        // Rank4 Usage at startRow + 2
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

    /**
     * Path C: Z>=4 (3 SCells), single-height rows (h=1.0).
     * Left panel (col=30): PCell at startRow, SCell1 at startRow+1.
     * Right panel (col=65): SCell2 at startRow, SCell3 at startRow+1.  Rank4 at startRow+2.
     *
     * Rank3 Usage label row=startRow   h=2.0 col=0  w=27
     * Rank3 PCell bar  row=startRow    h=1.0 col=30 w=34  key=LTE_Rank3_Usage_PCell
     * Rank3 SCell1 bar row=startRow+1  h=1.0 col=30 w=34  key=LTE_Rank3_Usage_SCell1
     * Rank3 SCell2 bar row=startRow    h=1.0 col=65 w=34  key=LTE_Rank3_Usage_SCell2
     * Rank3 SCell3 bar row=startRow+1  h=1.0 col=65 w=34  key=LTE_Rank3_Usage_SCell3
     *
     * Rank4 Usage label row=startRow+2 h=2.0 col=0  w=27
     * Rank4 PCell bar  row=startRow+2  h=1.0 col=30 w=34  key=LTE_Rank4_Usage_PCell
     * Rank4 SCell1 bar row=startRow+3  h=1.0 col=30 w=34  key=LTE_Rank4_Usage_SCell1
     * Rank4 SCell2 bar row=startRow+2  h=1.0 col=65 w=34  key=LTE_Rank4_Usage_SCell2
     * Rank4 SCell3 bar row=startRow+3  h=1.0 col=65 w=34  key=LTE_Rank4_Usage_SCell3
     *
     * Bars use v6.f.f(DEEP_BLUE, 100.0f) for fixed-color percentage bars.
     */
    private void injectRankUsageRowPathC(Object k2aObj, float startRow) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;

        // Rank3 Usage
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

        // Rank4 Usage at startRow + 2
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
}
