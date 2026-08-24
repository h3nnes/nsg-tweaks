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
    private Method k2aTMethod; // t(float row, float h, float col, float w) → v6.g  (text)

    // v6.e label fields (actual bytecode names)
    private Field veF; // text   (JADX: f8116f)
    private Field veG; // align  (JADX: f8117g)
    private Field veH; // span

    // v6.f bar data-binding field
    private Field vfF8120g; // g (JADX: f8120g) — data binding

    // v6.f bar color/max/fixed fields (flavor-dependent)
    private Field barFixedField;
    private Field barColorField;
    private Field barMaxField;

    // v6.f bar color/max setter: f(int color, float max) enables fixed-color bar mode
    private Method vfFMethod;

    // v6.g (eh0) fields for cloning
    private Field vgBindingsField;
    private Field vgColorField;
    private Field vgSepField;
    private Field vgAppField;
    private Field vgGravField;

    // v00 (d7.i$k) class and case field
    private Class<?> v00Class;
    private Field v00CaseField;

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

    // k2.a list + v6.a fields
    private Field k2aListField;
    private Field vaRowField;
    private Field vaHeightField;
    private Field vaColField;
    private Field vaWidthField;

    // Element type classes for instanceof checks
    private Class<?> ch0Class;
    private Class<?> dh0Class;
    private Class<?> eh0Class;

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
            Class<?> vgClass  = ClassMapping.loadClass("v6.g", loader);

            k2aRMethod = ClassMapping.getMethod(k2aClass, "k2.a", "r", loader,
                    float.class, float.class, float.class, float.class);
            k2aSMethod = ClassMapping.getMethod(k2aClass, "k2.a", "s", loader,
                    float.class, float.class, float.class, float.class);
            k2aTMethod = ClassMapping.getMethod(k2aClass, "k2.a", "t", loader,
                    float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);

            vfFMethod = ClassMapping.getDeclaredMethod(vfClass, "v6.f", "f", loader,
                    int.class, float.class);
            vfFMethod.setAccessible(true);

            boolean isGplay = FlavorDetector.detect(loader) == FlavorDetector.Flavor.GPLAY;
            if (isGplay) {
                barFixedField = vfClass.getDeclaredField("i");
                barColorField = vfClass.getDeclaredField("j");
                barMaxField = vfClass.getDeclaredField("k");
            } else {
                barFixedField = vfClass.getDeclaredField("h");
                barColorField = vfClass.getDeclaredField("i");
                barMaxField = vfClass.getDeclaredField("j");
            }
            barFixedField.setAccessible(true);
            barColorField.setAccessible(true);
            barMaxField.setAccessible(true);

            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);
            vgColorField = vgClass.getDeclaredField("i");
            vgColorField.setAccessible(true);
            vgSepField = vgClass.getDeclaredField("h");
            vgSepField.setAccessible(true);
            vgAppField = vgClass.getDeclaredField("j");
            vgAppField.setAccessible(true);
            vgGravField = vgClass.getDeclaredField("k");
            vgGravField.setAccessible(true);

            v00Class = ClassMapping.loadClass("d7.i$k", loader);
            if (v00Class != null) {
                try {
                    v00CaseField = v00Class.getDeclaredField("e");
                    v00CaseField.setAccessible(true);
                } catch (NoSuchFieldException ignored) {
                }
            }

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

            Class<?> e8bClass = ClassMapping.loadClass("e8.b", loader);
            e8bCarrierCountField = e8bClass.getDeclaredField(ClassMapping.runtimeFieldName("e8.b", "Z", loader));
            e8bCarrierCountField.setAccessible(true);

            k2aListField = k2aClass.getDeclaredField(ClassMapping.runtimeFieldName("k2.a", "d", loader));
            k2aListField.setAccessible(true);
            Class<?> vaClass = ClassMapping.loadClass("v6.a", loader);
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);
            vaHeightField = vaClass.getDeclaredField("c");
            vaHeightField.setAccessible(true);
            vaColField = vaClass.getDeclaredField("d");
            vaColField.setAccessible(true);
            vaWidthField = vaClass.getDeclaredField("e");
            vaWidthField.setAccessible(true);

            ch0Class = veClass;
            dh0Class = vfClass;
            eh0Class = vgClass;

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
            boolean isPathD = (carriers == 5);
            boolean isPathE = (carriers >= 6);
            boolean isPathDE = isPathD || isPathE;

            if (isPathDE) {
                transformGrid(k2aObj, carriers);
            }

            float rsrpRow;
            float shiftFrom;
            float shiftAmount;

            if (isPathA) {
                rsrpRow     = 11.0f;
                shiftFrom   = 11.0f;
                shiftAmount = 1.0f;
            } else if (isPathDE) {
                rsrpRow     = 15.0f;
                shiftFrom   = 15.0f;
                shiftAmount = 3.0f;
            } else {
                rsrpRow     = 13.0f;
                shiftFrom   = 13.0f;
                shiftAmount = 2.0f;
            }

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

            float rankRow = RANK_ROW;
            float rankShift = RANK_SHIFT_AMOUNT;
            if (isPathDE) {
                rankRow = 33.0f;
                rankShift = 6.0f;
            }
            if (!isPathA && list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= rankRow) {
                        vaRowField.set(elem, elemRow + rankShift);
                    }
                }
            }

            float mcsRow;
            float mcsShiftFrom;
            float mcsShiftAmount;
            if (isPathA) {
                mcsRow        = 21.0f;
                mcsShiftFrom  = 21.0f;
                mcsShiftAmount = 1.0f;
            } else if (isPathDE) {
                mcsRow        = 51.0f;
                mcsShiftFrom  = 51.0f;
                mcsShiftAmount = 3.0f;
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

            if (isPathA) {
                injectRsrpRowPathA(k2aObj, rsrpRow);
            } else if (isPathB) {
                injectRsrpRowPathB(k2aObj, rsrpRow);
            } else if (isPathD) {
                injectRsrpRowPathD(k2aObj, rsrpRow);
            } else if (isPathE) {
                injectRsrpRowPathE(k2aObj, rsrpRow);
            } else {
                injectRsrpRowPathC(k2aObj, rsrpRow);
            }

            if (isPathB) {
                injectRankUsageRowPathB(k2aObj, rankRow);
            } else if (isPathD) {
                injectRankUsageRowPathD(k2aObj, rankRow);
            } else if (isPathE) {
                injectRankUsageRowPathE(k2aObj, rankRow);
            } else if (!isPathA) {
                injectRankUsageRowPathC(k2aObj, rankRow);
            }

            if (isPathA) {
                injectMcsRowPathA(k2aObj, mcsRow);
            } else if (isPathB) {
                injectMcsRowPathB(k2aObj, mcsRow);
            } else if (isPathD) {
                injectMcsRowPathD(k2aObj, mcsRow);
            } else if (isPathE) {
                injectMcsRowPathE(k2aObj, mcsRow);
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

    @SuppressWarnings("unchecked")
    private void transformGrid(Object k2aObj, int carriers) throws Exception {
        java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
        if (list == null) return;

        boolean isPathD = (carriers == 5);
        boolean isPathE = (carriers >= 6);

        java.util.ArrayList<Object> originals = new java.util.ArrayList<>(list);

        if (isPathE) {
            for (Object elem : originals) {
                float row = (float) vaRowField.get(elem);
                if (row < 9.0f) continue;
                float col = (float) vaColField.get(elem);
                if (col < 50.0f) continue;
                int oldRowInt = (int) row;
                boolean isFirstSubRow = ((oldRowInt - 9) % 2) == 0;
                if (isFirstSubRow) {
                    replaceScellInElement(elem, 2, 3);
                } else {
                    replaceScellInElement(elem, 3, 4);
                }
            }
        }

        Object[][] templates = new Object[13][4];
        for (Object elem : originals) {
            float row = (float) vaRowField.get(elem);
            if (row < 9.0f) continue;
            int oldRowInt = (int) row;
            int l = (oldRowInt - 9) / 2;
            boolean isFirstSubRow = ((oldRowInt - 9) % 2) == 0;
            if (isFirstSubRow) continue;
            if (l < 0 || l >= 13) continue;
            float col = (float) vaColField.get(elem);
            if (Math.abs(col - 30.0f) < 0.1f) templates[l][0] = elem;
            else if (Math.abs(col - 47.0f) < 0.1f) templates[l][1] = elem;
            else if (Math.abs(col - 65.0f) < 0.1f) templates[l][2] = elem;
            else if (Math.abs(col - 82.0f) < 0.1f) templates[l][3] = elem;
        }

        for (Object elem : originals) {
            float row = (float) vaRowField.get(elem);
            if (row < 9.0f) continue;
            float col = (float) vaColField.get(elem);
            int oldRowInt = (int) row;
            int l = (oldRowInt - 9) / 2;
            boolean isFirstSubRow = ((oldRowInt - 9) % 2) == 0;

            float newRow;
            if (isPathE) {
                newRow = isFirstSubRow ? 9 + 3*l : 10 + 3*l;
                if (col < 5.0f) {
                    float h = (float) vaHeightField.get(elem);
                    if (h >= 1.9f) vaHeightField.set(elem, 3.0f);
                }
            } else {
                if (col < 5.0f) {
                    newRow = 9 + 3*l;
                    float h = (float) vaHeightField.get(elem);
                    if (h >= 1.9f) vaHeightField.set(elem, 3.0f);
                } else if (col < 50.0f) {
                    if (isFirstSubRow) {
                        newRow = 9.15f + 3*l;
                        vaHeightField.set(elem, 1.2f);
                    } else {
                        newRow = 10.65f + 3*l;
                        vaHeightField.set(elem, 1.2f);
                    }
                } else {
                    newRow = isFirstSubRow ? 9 + 3*l : 10 + 3*l;
                }
            }
            vaRowField.set(elem, newRow);
        }

        for (int l = 0; l < 13; l++) {
            float newRow = 11.0f + 3*l;
            float newH = 1.0f;

            if (isPathE) {
                if (templates[l][0] != null)
                    cloneElement(k2aObj, templates[l][0], newRow, 30.0f, newH, 1, 2);
                if (templates[l][1] != null)
                    cloneElement(k2aObj, templates[l][1], newRow, 47.0f, newH, 1, 2);
                if (templates[l][2] != null)
                    cloneElement(k2aObj, templates[l][2], newRow, 65.0f, newH, 4, 5);
                if (templates[l][3] != null)
                    cloneElement(k2aObj, templates[l][3], newRow, 82.0f, newH, 4, 5);
            } else {
                if (templates[l][2] != null)
                    cloneElement(k2aObj, templates[l][2], newRow, 65.0f, newH, 3, 4);
                if (templates[l][3] != null)
                    cloneElement(k2aObj, templates[l][3], newRow, 82.0f, newH, 3, 4);
            }
        }

        if (isPathE) {
            updateHeaderText(originals, 30.0f, "PCC/SCC1/2");
            updateHeaderText(originals, 65.0f, "SCC 3/4/5");
        } else {
            updateHeaderText(originals, 30.0f, "PCC/SCC1");
            updateHeaderText(originals, 65.0f, "SCC 2/3/4");
        }
    }

    private void replaceScellInElement(Object elem, int oldIdx, int newIdx) throws Exception {
        String oldStr = "SCell" + oldIdx;
        String newStr = "SCell" + newIdx;
        if (dh0Class.isInstance(elem)) {
            Object binding = vfF8120g.get(elem);
            if (binding != null) replaceScellInBinding(binding, oldStr, newStr);
        } else if (eh0Class.isInstance(elem)) {
            java.util.ArrayList<?> bindings = (java.util.ArrayList<?>) vgBindingsField.get(elem);
            if (bindings != null) {
                for (Object binding : bindings) {
                    if (binding != null) replaceScellInBinding(binding, oldStr, newStr);
                }
            }
        }
    }

    private void replaceScellInBinding(Object binding, String oldStr, String newStr) throws Exception {
        String key = (String) sysAFieldA.get(binding);
        if (key != null && key.contains(oldStr)) {
            sysAFieldA.set(binding, key.replace(oldStr, newStr));
        }
    }

    private void cloneElement(Object k2aObj, Object template, float newRow, float newCol, float newH,
                              int oldScellIdx, int newScellIdx) throws Exception {
        String oldStr = "SCell" + oldScellIdx;
        String newStr = "SCell" + newScellIdx;
        float w = (float) vaWidthField.get(template);

        if (ch0Class.isInstance(template)) {
            Object newElem = k2aRMethod.invoke(k2aObj, newRow, newH, newCol, w);
            if (newElem != null) {
                veF.set(newElem, veF.get(template));
                veG.set(newElem, veG.get(template));
                veH.set(newElem, veH.get(template));
            }
        } else if (dh0Class.isInstance(template)) {
            Object newElem = k2aSMethod.invoke(k2aObj, newRow, newH, newCol, w);
            if (newElem != null) {
                Object binding = vfF8120g.get(template);
                if (binding != null) {
                    Object newBinding = cloneBinding(binding, oldStr, newStr);
                    vfF8120g.set(newElem, newBinding);
                }
                boolean fixed = barFixedField.getBoolean(template);
                if (fixed) {
                    int color = barColorField.getInt(template);
                    float max = barMaxField.getFloat(template);
                    vfFMethod.invoke(newElem, color, max);
                }
            }
        } else if (eh0Class.isInstance(template)) {
            Object newElem = k2aTMethod.invoke(k2aObj, newRow, newH, newCol, w);
            if (newElem != null) {
                java.util.ArrayList<?> bindings = (java.util.ArrayList<?>) vgBindingsField.get(template);
                if (bindings != null) {
                    java.util.ArrayList<Object> newBindings = new java.util.ArrayList<>();
                    for (Object binding : bindings) {
                        newBindings.add(cloneBinding(binding, oldStr, newStr));
                    }
                    vgBindingsField.set(newElem, newBindings);
                }
                vgSepField.set(newElem, vgSepField.get(template));
                vgColorField.set(newElem, vgColorField.get(template));
                vgAppField.set(newElem, vgAppField.get(template));
                vgGravField.set(newElem, vgGravField.get(template));
            }
        }
    }

    private Object cloneBinding(Object binding, String oldStr, String newStr) throws Exception {
        Class<?> bindingClass = binding.getClass();
        Object newBinding = unsafeAllocateInstance.invoke(unsafe, bindingClass);
        Class<?> c = bindingClass;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)) continue;
                f.setAccessible(true);
                Object value = f.get(binding);
                if (value instanceof String) {
                    String s = (String) value;
                    if (s.contains(oldStr)) {
                        s = s.replace(oldStr, newStr);
                    }
                    f.set(newBinding, s);
                } else {
                    f.set(newBinding, value);
                }
            }
            c = c.getSuperclass();
        }
        return newBinding;
    }

    private void updateHeaderText(java.util.ArrayList<?> list, float col, String text) throws Exception {
        for (Object elem : list) {
            float row = (float) vaRowField.get(elem);
            if (Math.abs(row - 8.0f) > 0.1f) continue;
            float c = (float) vaColField.get(elem);
            if (Math.abs(c - col) > 0.1f) continue;
            if (ch0Class.isInstance(elem)) {
                veF.set(elem, text);
            }
        }
    }

    private void injectRsrpRowPathD(Object k2aObj, float rsrpRow) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;

        Object label = k2aRMethod.invoke(k2aObj, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) { veF.set(label, "RSRP"); veG.set(label, 0); veH.set(label, 1); }

        Object pCellBar = k2aSMethod.invoke(k2aObj, rsrpRow + 0.15f, leftH, 30.0f, 34.0f);
        if (pCellBar != null) vfF8120g.set(pCellBar, makeProp("LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));

        Object sCell1Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.65f, leftH, 30.0f, 34.0f);
        if (sCell1Bar != null) vfF8120g.set(sCell1Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));

        Object sCell2Bar = k2aSMethod.invoke(k2aObj, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell2Bar != null) vfF8120g.set(sCell2Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));

        Object sCell3Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) vfF8120g.set(sCell3Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));

        Object sCell4Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 2.0f, barH, 65.0f, 34.0f);
        if (sCell4Bar != null) vfF8120g.set(sCell4Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell4", -1));
    }

    private void injectRsrpRowPathE(Object k2aObj, float rsrpRow) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;

        Object label = k2aRMethod.invoke(k2aObj, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) { veF.set(label, "RSRP"); veG.set(label, 0); veH.set(label, 1); }

        Object pCellBar = k2aSMethod.invoke(k2aObj, rsrpRow, barH, 30.0f, 34.0f);
        if (pCellBar != null) vfF8120g.set(pCellBar, makeProp("LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));

        Object sCell1Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, barH, 30.0f, 34.0f);
        if (sCell1Bar != null) vfF8120g.set(sCell1Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));

        Object sCell2Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 2.0f, barH, 30.0f, 34.0f);
        if (sCell2Bar != null) vfF8120g.set(sCell2Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));

        Object sCell3Bar = k2aSMethod.invoke(k2aObj, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) vfF8120g.set(sCell3Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));

        Object sCell4Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell4Bar != null) vfF8120g.set(sCell4Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell4", -1));

        Object sCell5Bar = k2aSMethod.invoke(k2aObj, rsrpRow + 2.0f, barH, 65.0f, 34.0f);
        if (sCell5Bar != null) vfF8120g.set(sCell5Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell5", -1));
    }

    private void injectRankUsageRowPathD(Object k2aObj, float startRow) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;

        Object rank3Label = k2aRMethod.invoke(k2aObj, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) { veF.set(rank3Label, "Rank3 Usage"); veG.set(rank3Label, 0); veH.set(rank3Label, 1); }
        injectRankBar(k2aObj, startRow + 0.15f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL");
        injectRankBar(k2aObj, startRow + 1.65f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL");
        injectRankBar(k2aObj, startRow, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL");
        injectRankBar(k2aObj, startRow + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL");
        injectRankBar(k2aObj, startRow + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell4_DL");

        float rank4Row = startRow + 3.0f;
        Object rank4Label = k2aRMethod.invoke(k2aObj, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) { veF.set(rank4Label, "Rank4 Usage"); veG.set(rank4Label, 0); veH.set(rank4Label, 1); }
        injectRankBar(k2aObj, rank4Row + 0.15f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL");
        injectRankBar(k2aObj, rank4Row + 1.65f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL");
        injectRankBar(k2aObj, rank4Row, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL");
        injectRankBar(k2aObj, rank4Row + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL");
        injectRankBar(k2aObj, rank4Row + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell4_DL");
    }

    private void injectRankUsageRowPathE(Object k2aObj, float startRow) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;

        Object rank3Label = k2aRMethod.invoke(k2aObj, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) { veF.set(rank3Label, "Rank3 Usage"); veG.set(rank3Label, 0); veH.set(rank3Label, 1); }
        injectRankBar(k2aObj, startRow, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL");
        injectRankBar(k2aObj, startRow + 1.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL");
        injectRankBar(k2aObj, startRow + 2.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL");
        injectRankBar(k2aObj, startRow, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL");
        injectRankBar(k2aObj, startRow + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell4_DL");
        injectRankBar(k2aObj, startRow + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell5_DL");

        float rank4Row = startRow + 3.0f;
        Object rank4Label = k2aRMethod.invoke(k2aObj, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) { veF.set(rank4Label, "Rank4 Usage"); veG.set(rank4Label, 0); veH.set(rank4Label, 1); }
        injectRankBar(k2aObj, rank4Row, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL");
        injectRankBar(k2aObj, rank4Row + 1.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL");
        injectRankBar(k2aObj, rank4Row + 2.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL");
        injectRankBar(k2aObj, rank4Row, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL");
        injectRankBar(k2aObj, rank4Row + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell4_DL");
        injectRankBar(k2aObj, rank4Row + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell5_DL");
    }

    private void injectRankBar(Object k2aObj, float row, float h, float col, float w, String key) throws Exception {
        Object bar = k2aSMethod.invoke(k2aObj, row, h, col, w);
        if (bar != null) {
            vfF8120g.set(bar, makeRankProp(key, -1));
            vfFMethod.invoke(bar, DEEP_BLUE, RANK_BAR_MAX);
        }
    }

    private void injectMcsRowPathD(Object k2aObj, float row) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;
        Object label = k2aRMethod.invoke(k2aObj, row, labelH, 0.0f, 27.0f);
        if (label != null) { veF.set(label, "MCS Cwd 0/1"); veG.set(label, 0); veH.set(label, 1); }
        injectMcsBar(k2aObj, row + 0.15f, leftH, 30.0f, 16.5f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(k2aObj, row + 0.15f, leftH, 47.0f, 17.0f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(k2aObj, row + 1.65f, leftH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(k2aObj, row + 1.65f, leftH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(k2aObj, row, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(k2aObj, row, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell4_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell4_DL");
    }

    private void injectMcsRowPathE(Object k2aObj, float row) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;
        Object label = k2aRMethod.invoke(k2aObj, row, labelH, 0.0f, 27.0f);
        if (label != null) { veF.set(label, "MCS Cwd 0/1"); veG.set(label, 0); veH.set(label, 1); }
        injectMcsBar(k2aObj, row, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(k2aObj, row, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(k2aObj, row, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(k2aObj, row, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell4_DL");
        injectMcsBar(k2aObj, row + 1.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell4_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell5_DL");
        injectMcsBar(k2aObj, row + 2.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell5_DL");
    }
}
