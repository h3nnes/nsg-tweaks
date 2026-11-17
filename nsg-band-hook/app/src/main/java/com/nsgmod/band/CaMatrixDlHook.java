package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds an "RSRP" row immediately below the "Band/Width" row (above "SINR") on both
 * CA Matrix DL pages — LTE "CAMatrix" (w00) and NR-NSA "NRNSA_LTE_CA_Matrix" (ua0) —
 * showing the RSRP value for PCell and each SCell. Further rows (Rank3/Rank4 usage,
 * MCS Cwd 0/1) are inserted the same way, and the 5/6+ carrier layouts are rebuilt.
 *
 * ua0 is a parallel class to w00 with identical row geometry and member names, so one
 * hook serves both pages.
 *
 * NSG v4.8.14 targets:
 *   w00 / ua0 (page fragments): j0() dispatches on carrier count Y to g0(w4) [Y==1/2],
 *     inline code [Y==3] or h0(w4) [Y>=4]; every path ends with static yg0.g0(r31).
 *   r31 (grid builder): list b (ArrayList of elements); M/N/O(row, h, col, w) add a
 *     label bh0 / bar ch0 / multi-value dh0 cell.
 *   xg0 (element base): row b, height c, col d, width e.
 *   bh0: text f, alignment g, span h.  ch0: binding g (m8), f(int,float) fixed colour/max.
 *   dh0: bindings f, separator h, colour i, appearance j, gravity k.
 *   l8 (attribute): key a, format b, index c; m8 extends l8.
 *
 * Strategy:
 *   Hook w00.j0() and ua0.j0() to set a shared ThreadLocal<Integer> with the carrier
 *   count (field Y of the page).
 *   Hook static yg0.g0(r31) once — when the ThreadLocal is set, inject the rows.
 *
 * Row geometry per carrier path:
 *
 *   Path A  Y==1 or Y==2  (1 SCell, g0(w4))  — single-height rows (h=1.0)
 *     Band/Width at row 10.  SINR at row 11.
 *     → Insert RSRP at row 11, shift ≥11 by +2.0.
 *     Label  col=0  w=27
 *     PCell  col=30 w=34  key=LTE_RSRP_PCell     index=-1
 *     SCell1 col=65 w=34  key=LTE_RSRP_SCell1    index=-1
 *
 *   Path B  Y==3  (2 SCells, inline j0())  — double-height rows (h=2.0)
 *     Band/Width at row 11 (h=2).  SINR at row 13 (h=2).
 *     → Insert RSRP at row 13, shift ≥13 by +2.0 (one logical h=2 row).
 *     Label      row=13  h=2.0  col=0  w=27
 *     PCell bar  row=13.3 h=1.4  col=30 w=34  key=LTE_RSRP_PCell  index=-1
 *     SCell1 bar row=13.0 h=1.0  col=65 w=34  key=LTE_RSRP_SCell1 index=-1
 *     SCell2 bar row=14.0 h=1.0  col=65 w=34  key=LTE_RSRP_SCell2 index=-1
 *
 *   Path C  Y>=4  (3 SCells, h0(w4))  — single-height rows (h=1.0)
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
public class CaMatrixDlHook {

    private static final String TAG = "NSGBandHook";

    /** Set by the w00/ua0 j0() flag hooks while j0() executes; null otherwise. */
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

    private static final String PAGE_LTE_CA_MATRIX = Nsg.PAGE_LTE_CA_MATRIX_DL;   // w00
    private static final String PAGE_NSA_LTE_CA_MATRIX = Nsg.PAGE_NSA_LTE_CA_MATRIX; // ua0
    /** Attribute class used for cell bindings. */
    private static final String ATTR_CLASS = "l8";    // key a, format b, index c
    private static final String BINDING_CLASS = "m8"; // l8 subclass bound to bar/multi cells

    // r31 grid builder
    private static Method addLabel; // r31.M(float row, float h, float col, float w) -> bh0 (label)
    private static Method addBar;   // r31.N(...) -> ch0 (bar)
    private static Method addMulti; // r31.O(...) -> dh0 (multi value)
    private static Field listField; // r31.b: ArrayList of grid elements

    // xg0 element base fields
    private static Field rowField;    // xg0.b
    private static Field heightField; // xg0.c
    private static Field colField;    // xg0.d
    private static Field widthField;  // xg0.e

    // bh0 label fields
    private static Field labelText;  // bh0.f
    private static Field labelAlign; // bh0.g
    private static Field labelSpan;  // bh0.h

    // ch0 bar fields
    private static Field barBinding;      // ch0.g (m8)
    private static Field barFixedField;   // ch0.h (boolean)
    private static Field barColorField;   // ch0.i
    private static Field barMaxField;     // ch0.j
    private static Method barFixedColor;  // ch0.f(int color, float max): fixed-colour bar mode

    // dh0 multi-value fields (for cloning)
    private static Field multiBindings; // dh0.f
    private static Field multiSep;      // dh0.h
    private static Field multiColor;    // dh0.i
    private static Field multiApp;      // dh0.j
    private static Field multiGrav;     // dh0.k

    // l8 attribute fields
    private static Class<?> propClass; // m8
    private static Field attrKey;      // l8.a
    private static Field attrFormat;   // l8.b
    private static Field attrIndex;    // l8.c

    // Unsafe for allocateInstance (attribute constructors need arguments)
    private static Object unsafe;
    private static Method unsafeAllocateInstance;

    // Page carrier-count field Y (int) of w00 / ua0
    private static Field ltePageCarrierCountField;
    private static Field nsaPageCarrierCountField;

    // Element type classes for instanceof checks
    private static Class<?> labelClass; // bh0
    private static Class<?> barClass;   // ch0
    private static Class<?> multiClass; // dh0

    /** Binding-class field cache for cloneBinding: flattened, pre-accessible,
     *  non-static instance fields (including superclass fields) per binding class. */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>
            BINDING_FIELDS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean ready = false;

    public CaMatrixDlHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflectionOnce(loader);
    }

    private static void initReflectionOnce(ClassLoader loader) {
        if (ready) return;
        synchronized (INIT_LOCK) {
            if (ready) return;
            initReflection(loader);
        }
    }

    private static void initReflection(ClassLoader loader) {
        try {
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);   // r31
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);   // xg0
            labelClass = Nsg.load(loader, Nsg.GRID_LABEL);                // bh0
            barClass   = Nsg.load(loader, Nsg.GRID_VALUE);                // ch0
            multiClass = Nsg.load(loader, Nsg.GRID_MULTI);                // dh0

            addLabel = builderClass.getMethod("M", float.class, float.class, float.class, float.class);
            addBar   = builderClass.getMethod("N", float.class, float.class, float.class, float.class);
            addMulti = builderClass.getMethod("O", float.class, float.class, float.class, float.class);
            listField = builderClass.getDeclaredField("b");
            listField.setAccessible(true);

            rowField = elementClass.getDeclaredField("b");
            heightField = elementClass.getDeclaredField("c");
            colField = elementClass.getDeclaredField("d");
            widthField = elementClass.getDeclaredField("e");
            rowField.setAccessible(true);
            heightField.setAccessible(true);
            colField.setAccessible(true);
            widthField.setAccessible(true);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelSpan = labelClass.getField("h");

            barBinding = barClass.getDeclaredField("g");
            barFixedField = barClass.getDeclaredField("h");
            barColorField = barClass.getDeclaredField("i");
            barMaxField = barClass.getDeclaredField("j");
            barBinding.setAccessible(true);
            barFixedField.setAccessible(true);
            barColorField.setAccessible(true);
            barMaxField.setAccessible(true);
            barFixedColor = barClass.getDeclaredMethod("f", int.class, float.class);
            barFixedColor.setAccessible(true);

            multiBindings = multiClass.getDeclaredField("f");
            multiSep = multiClass.getDeclaredField("h");
            multiColor = multiClass.getDeclaredField("i");
            multiApp = multiClass.getDeclaredField("j");
            multiGrav = multiClass.getDeclaredField("k");
            multiBindings.setAccessible(true);
            multiSep.setAccessible(true);
            multiColor.setAccessible(true);
            multiApp.setAccessible(true);
            multiGrav.setAccessible(true);

            propClass = Nsg.load(loader, BINDING_CLASS);
            Class<?> attrClass = Nsg.load(loader, ATTR_CLASS);
            attrKey = attrClass.getDeclaredField("a");
            attrFormat = attrClass.getDeclaredField("b");
            attrIndex = attrClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");   // Android/Dalvik
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe"); // OpenJDK fallback
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            ltePageCarrierCountField = resolveCarrierCountField(loader, PAGE_LTE_CA_MATRIX);
            nsaPageCarrierCountField = resolveCarrierCountField(loader, PAGE_NSA_LTE_CA_MATRIX);

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e);
        }
    }

    private static Field resolveCarrierCountField(ClassLoader loader, String pageName) throws Exception {
        Field f = Nsg.load(loader, pageName).getDeclaredField("Y"); // w00/ua0.Y: carrier count
        f.setAccessible(true);
        return f;
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "skipping install — reflection not ready");
            return;
        }
        boolean anyPage = false;
        anyPage |= installN0FlagHook(PAGE_LTE_CA_MATRIX, ltePageCarrierCountField);
        anyPage |= installN0FlagHook(PAGE_NSA_LTE_CA_MATRIX, nsaPageCarrierCountField);
        if (!anyPage) {
            Log.w(TAG, "CaMatrixDlHook: no page hook installed, skipping");
            return;
        }
        installGridCallbackHook();
        Log.i(TAG, "CaMatrixDlHook: installed");
    }

    // -----------------------------------------------------------------------
    // Hook 1: w00.j0() / ua0.j0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private boolean installN0FlagHook(String pageName, Field carrierCountField) {
        try {
            Method j0Method = Nsg.load(loader, pageName).getDeclaredMethod("j0");
            final Field carrierField = carrierCountField;

            xposed.hook(j0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int carriers = -1;
                    try {
                        carriers = (int) carrierField.get(chain.getThisObject());
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
            return true;
        } catch (Exception e) {
            Log.e(TAG, "j0 flag hook failed for " + pageName + ": " + e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: static yg0.g0(r31) — inject rows when called from either page's j0()
    // -----------------------------------------------------------------------

    private void installGridCallbackHook() {
        try {
            Class<?> pageBase = Nsg.load(loader, Nsg.GRID_PAGE);      // yg0
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER); // r31
            Method g0Method = pageBase.getDeclaredMethod("g0", builderClass);

            xposed.hook(g0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object  builderArg = chain.getArg(0);
                    Integer carriers   = carrierCountInN0.get();
                    if (carriers != null && builderArg != null) {
                        injectRsrpRow(builderArg, carriers);
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "yg0.g0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Injection: insert RSRP row below Band/Width, above SINR
    // -----------------------------------------------------------------------

    private void injectRsrpRow(Object builder, int carriers) {
        try {
            boolean isPathA = (carriers == 1 || carriers == 2);
            boolean isPathB = (carriers == 3);
            boolean isPathD = (carriers == 5);
            boolean isPathE = (carriers >= 6);
            boolean isPathDE = isPathD || isPathE;

            if (isPathDE) {
                transformGrid(builder, carriers);
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

            float rankRow = RANK_ROW;
            float rankShift = RANK_SHIFT_AMOUNT;
            if (isPathDE) {
                rankRow = 33.0f;
                rankShift = 6.0f;
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

            java.util.ArrayList<?> list =
                    (java.util.ArrayList<?>) listField.get(builder);
            if (list != null) {
                // Single pass: each threshold is checked against the running
                // (already shifted) row value, preserving the original 3-loop
                // semantics. Rank shifting is skipped entirely on path A.
                for (Object elem : list) {
                    float origRow = (float) rowField.get(elem);
                    float row = origRow;
                    if (row >= shiftFrom) row += shiftAmount;
                    if (!isPathA && row >= rankRow) row += rankShift;
                    if (row >= mcsShiftFrom) row += mcsShiftAmount;
                    if (row != origRow) rowField.set(elem, row);
                }
            }

            if (isPathA) {
                injectRsrpRowPathA(builder, rsrpRow);
            } else if (isPathB) {
                injectRsrpRowPathB(builder, rsrpRow);
            } else if (isPathD) {
                injectRsrpRowPathD(builder, rsrpRow);
            } else if (isPathE) {
                injectRsrpRowPathE(builder, rsrpRow);
            } else {
                injectRsrpRowPathC(builder, rsrpRow);
            }

            if (isPathB) {
                injectRankUsageRowPathB(builder, rankRow);
            } else if (isPathD) {
                injectRankUsageRowPathD(builder, rankRow);
            } else if (isPathE) {
                injectRankUsageRowPathE(builder, rankRow);
            } else if (!isPathA) {
                injectRankUsageRowPathC(builder, rankRow);
            }

            if (isPathA) {
                injectMcsRowPathA(builder, mcsRow);
            } else if (isPathB) {
                injectMcsRowPathB(builder, mcsRow);
            } else if (isPathD) {
                injectMcsRowPathD(builder, mcsRow);
            } else if (isPathE) {
                injectMcsRowPathE(builder, mcsRow);
            } else {
                injectMcsRowPathC(builder, mcsRow);
            }

        } catch (Exception e) {
            Log.w(TAG, "injectRsrpRow failed: " + e);
        }
    }

    /**
     * Path A: Y==1 or Y==2 (1 SCell), single-height rows (h=1.0).
     *
     * RSRP row at rsrpRow:
     *   label col=0 w=27
     *   PCell bar col=30 w=34  key=LTE_RSRP_PCell  index=-1
     *   SCell1 bar col=65 w=34  key=LTE_RSRP_SCell1 index=-1
     */
    private void injectRsrpRowPathA(Object builder, float rsrpRow) throws Exception {
        final float h = 1.0f;

        Object label = addLabel.invoke(builder, rsrpRow, h, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "RSRP");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }

        Object pCellBar = addBar.invoke(builder, rsrpRow, h, 30.0f, 34.0f);
        if (pCellBar != null) {
            barBinding.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = addBar.invoke(builder, rsrpRow, h, 65.0f, 34.0f);
        if (sCell1Bar != null) {
            barBinding.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }
    }

    /**
     * Path B: Y==3 (2 SCells), double-height rows (label h=2.0, PCell barOffset=+0.3 h=1.4).
     * SCell bars stack at rsrpRow and rsrpRow+1 in col=65.
     *
     * RSRP label row=rsrpRow h=2.0 col=0 w=27
     * PCell bar  row=rsrpRow+0.3 h=1.4 col=30 w=34
     * SCell1 bar row=rsrpRow     h=1.0 col=65 w=34
     * SCell2 bar row=rsrpRow+1   h=1.0 col=65 w=34
     */
    private void injectRsrpRowPathB(Object builder, float rsrpRow) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;

        Object label = addLabel.invoke(builder, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "RSRP");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }

        Object pCellBar = addBar.invoke(builder, rsrpRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (pCellBar != null) {
            barBinding.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = addBar.invoke(builder, rsrpRow, scellBarH, 65.0f, 34.0f);
        if (sCell1Bar != null) {
            barBinding.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }

        Object sCell2Bar = addBar.invoke(builder, rsrpRow + 1.0f, scellBarH, 65.0f, 34.0f);
        if (sCell2Bar != null) {
            barBinding.set(sCell2Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));
        }
    }

    /**
     * Path C: Y>=4 (3 SCells), single-height rows (h=1.0).
     * Left panel (col=30): PCell at rsrpRow, SCell1 at rsrpRow+1.
     * Right panel (col=65): SCell2 at rsrpRow, SCell3 at rsrpRow+1.
     *
     * RSRP label row=rsrpRow h=2.0 col=0 w=27
     * PCell  bar row=rsrpRow   h=1.0 col=30 w=34
     * SCell1 bar row=rsrpRow+1 h=1.0 col=30 w=34
     * SCell2 bar row=rsrpRow   h=1.0 col=65 w=34
     * SCell3 bar row=rsrpRow+1 h=1.0 col=65 w=34
     */
    private void injectRsrpRowPathC(Object builder, float rsrpRow) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;

        Object label = addLabel.invoke(builder, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "RSRP");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }

        Object pCellBar = addBar.invoke(builder, rsrpRow, barH, 30.0f, 34.0f);
        if (pCellBar != null) {
            barBinding.set(pCellBar, makeProp(
                    "LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));
        }

        Object sCell1Bar = addBar.invoke(builder, rsrpRow + 1.0f, barH, 30.0f, 34.0f);
        if (sCell1Bar != null) {
            barBinding.set(sCell1Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));
        }

        Object sCell2Bar = addBar.invoke(builder, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell2Bar != null) {
            barBinding.set(sCell2Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));
        }

        Object sCell3Bar = addBar.invoke(builder, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) {
            barBinding.set(sCell3Bar, makeProp(
                    "LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));
        }
    }

    // -----------------------------------------------------------------------
    // Rank3/Rank4 usage row injection (Path B and Path C only)
    // -----------------------------------------------------------------------

    /**
     * Path B: Y==3 (2 SCells), double-height rows (label h=2.0, PCell barOffset=+0.3 h=1.4).
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
     * Bars use ch0.f(DEEP_BLUE, 100.0f) for fixed-color percentage bars.
     */
    private void injectRankUsageRowPathB(Object builder, float startRow) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;

        // Rank3 Usage
        Object rank3Label = addLabel.invoke(builder, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) {
            labelText.set(rank3Label, "Rank3 Usage");
            labelAlign.set(rank3Label, 0);
            labelSpan.set(rank3Label, 1);
        }
        Object rank3PCell = addBar.invoke(builder, startRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (rank3PCell != null) {
            barBinding.set(rank3PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL", -1));
            barFixedColor.invoke(rank3PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell1 = addBar.invoke(builder, startRow, scellBarH, 65.0f, 34.0f);
        if (rank3SCell1 != null) {
            barBinding.set(rank3SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL", -1));
            barFixedColor.invoke(rank3SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell2 = addBar.invoke(builder, startRow + 1.0f, scellBarH, 65.0f, 34.0f);
        if (rank3SCell2 != null) {
            barBinding.set(rank3SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL", -1));
            barFixedColor.invoke(rank3SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }

        // Rank4 Usage at startRow + 2
        float rank4Row = startRow + 2.0f;
        Object rank4Label = addLabel.invoke(builder, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) {
            labelText.set(rank4Label, "Rank4 Usage");
            labelAlign.set(rank4Label, 0);
            labelSpan.set(rank4Label, 1);
        }
        Object rank4PCell = addBar.invoke(builder, rank4Row + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (rank4PCell != null) {
            barBinding.set(rank4PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL", -1));
            barFixedColor.invoke(rank4PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell1 = addBar.invoke(builder, rank4Row, scellBarH, 65.0f, 34.0f);
        if (rank4SCell1 != null) {
            barBinding.set(rank4SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL", -1));
            barFixedColor.invoke(rank4SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell2 = addBar.invoke(builder, rank4Row + 1.0f, scellBarH, 65.0f, 34.0f);
        if (rank4SCell2 != null) {
            barBinding.set(rank4SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL", -1));
            barFixedColor.invoke(rank4SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
    }

    /**
     * Path C: Y>=4 (3 SCells), single-height rows (h=1.0).
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
     * Bars use ch0.f(DEEP_BLUE, 100.0f) for fixed-color percentage bars.
     */
    private void injectRankUsageRowPathC(Object builder, float startRow) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;

        // Rank3 Usage
        Object rank3Label = addLabel.invoke(builder, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) {
            labelText.set(rank3Label, "Rank3 Usage");
            labelAlign.set(rank3Label, 0);
            labelSpan.set(rank3Label, 1);
        }
        Object rank3PCell = addBar.invoke(builder, startRow, barH, 30.0f, 34.0f);
        if (rank3PCell != null) {
            barBinding.set(rank3PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL", -1));
            barFixedColor.invoke(rank3PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell1 = addBar.invoke(builder, startRow + 1.0f, barH, 30.0f, 34.0f);
        if (rank3SCell1 != null) {
            barBinding.set(rank3SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL", -1));
            barFixedColor.invoke(rank3SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell2 = addBar.invoke(builder, startRow, barH, 65.0f, 34.0f);
        if (rank3SCell2 != null) {
            barBinding.set(rank3SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL", -1));
            barFixedColor.invoke(rank3SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank3SCell3 = addBar.invoke(builder, startRow + 1.0f, barH, 65.0f, 34.0f);
        if (rank3SCell3 != null) {
            barBinding.set(rank3SCell3, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL", -1));
            barFixedColor.invoke(rank3SCell3, DEEP_BLUE, RANK_BAR_MAX);
        }

        // Rank4 Usage at startRow + 2
        float rank4Row = startRow + 2.0f;
        Object rank4Label = addLabel.invoke(builder, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) {
            labelText.set(rank4Label, "Rank4 Usage");
            labelAlign.set(rank4Label, 0);
            labelSpan.set(rank4Label, 1);
        }
        Object rank4PCell = addBar.invoke(builder, rank4Row, barH, 30.0f, 34.0f);
        if (rank4PCell != null) {
            barBinding.set(rank4PCell, makeRankProp(
                    "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL", -1));
            barFixedColor.invoke(rank4PCell, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell1 = addBar.invoke(builder, rank4Row + 1.0f, barH, 30.0f, 34.0f);
        if (rank4SCell1 != null) {
            barBinding.set(rank4SCell1, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL", -1));
            barFixedColor.invoke(rank4SCell1, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell2 = addBar.invoke(builder, rank4Row, barH, 65.0f, 34.0f);
        if (rank4SCell2 != null) {
            barBinding.set(rank4SCell2, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL", -1));
            barFixedColor.invoke(rank4SCell2, DEEP_BLUE, RANK_BAR_MAX);
        }
        Object rank4SCell3 = addBar.invoke(builder, rank4Row + 1.0f, barH, 65.0f, 34.0f);
        if (rank4SCell3 != null) {
            barBinding.set(rank4SCell3, makeRankProp(
                    "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL", -1));
            barFixedColor.invoke(rank4SCell3, DEEP_BLUE, RANK_BAR_MAX);
        }
    }

    // -----------------------------------------------------------------------
    // MCS Cwd 0/1 row injection — between CQI and Mod
    // -----------------------------------------------------------------------

    private void injectMcsRowPathA(Object builder, float row) throws Exception {
        final float h = 1.0f;
        Object label = addLabel.invoke(builder, row, h, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "MCS Cwd 0/1");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }
        injectMcsBar(builder, row, h, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(builder, row, h, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(builder, row, h, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(builder, row, h, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
    }

    private void injectMcsRowPathB(Object builder, float row) throws Exception {
        final float labelH    = 2.0f;
        final float pcellBarH = 1.4f;
        final float pcellOff  = 0.3f;
        final float scellBarH = 1.0f;
        Object label = addLabel.invoke(builder, row, labelH, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "MCS Cwd 0/1");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }
        injectMcsBar(builder, row + pcellOff, pcellBarH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(builder, row + pcellOff, pcellBarH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(builder, row, scellBarH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(builder, row, scellBarH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(builder, row + 1.0f, scellBarH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(builder, row + 1.0f, scellBarH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
    }

    private void injectMcsRowPathC(Object builder, float row) throws Exception {
        final float labelH = 2.0f;
        final float barH   = 1.0f;
        Object label = addLabel.invoke(builder, row, labelH, 0.0f, 27.0f);
        if (label != null) {
            labelText.set(label, "MCS Cwd 0/1");
            labelAlign.set(label, 0);
            labelSpan.set(label, 1);
        }
        injectMcsBar(builder, row, barH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(builder, row, barH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(builder, row + 1.0f, barH, 30.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(builder, row + 1.0f, barH, 47.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(builder, row, barH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(builder, row, barH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(builder, row + 1.0f, barH, 65.0f, 16.5f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(builder, row + 1.0f, barH, 82.0f, 17.0f,
                "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
    }

    private void injectMcsBar(Object builder, float row, float h, float col, float w,
                              String key) throws Exception {
        Object bar = addBar.invoke(builder, row, h, col, w);
        if (bar != null) {
            barBinding.set(bar, makeMcsProp(key));
            barFixedColor.invoke(bar, DEEP_BLUE, MCS_BAR_MAX);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: allocate com.qtrun.sys.b via Unsafe and set key/format/index
    // -----------------------------------------------------------------------

    private Object makeProp(String key, int index) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, propClass);
        attrKey.set(prop, key);
        attrFormat.set(prop, "%.1f dBm");
        attrIndex.set(prop, index);
        return prop;
    }

    private Object makeRankProp(String key, int index) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, propClass);
        attrKey.set(prop, key);
        attrFormat.set(prop, "%.1f %%");
        attrIndex.set(prop, index);
        return prop;
    }

    private Object makeMcsProp(String key) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, propClass);
        attrKey.set(prop, key);
        attrFormat.set(prop, "%d");
        attrIndex.set(prop, -1);
        return prop;
    }

    @SuppressWarnings("unchecked")
    private void transformGrid(Object builder, int carriers) throws Exception {
        java.util.ArrayList<?> list = (java.util.ArrayList<?>) listField.get(builder);
        if (list == null) return;

        boolean isPathD = (carriers == 5);
        boolean isPathE = (carriers >= 6);

        java.util.ArrayList<Object> originals = new java.util.ArrayList<>(list);

        // Single pass over the originals: PathE in-place SCell binding rewrite,
        // template collection, and repositioning. Each element's row/col is read
        // once into locals before any write to that element, and elements are
        // independent of each other.
        Object[][] templates = new Object[13][4];
        for (Object elem : originals) {
            float row = (float) rowField.get(elem);
            if (row < 9.0f) continue;
            float col = (float) colField.get(elem);
            int oldRowInt = (int) row;
            int l = (oldRowInt - 9) / 2;
            boolean isFirstSubRow = ((oldRowInt - 9) % 2) == 0;

            if (isPathE && col >= 50.0f) {
                if (isFirstSubRow) {
                    replaceScellInElement(elem, 2, 3);
                } else {
                    replaceScellInElement(elem, 3, 4);
                }
            }

            if (!isFirstSubRow && l >= 0 && l < 13) {
                if (Math.abs(col - 30.0f) < 0.1f) templates[l][0] = elem;
                else if (Math.abs(col - 47.0f) < 0.1f) templates[l][1] = elem;
                else if (Math.abs(col - 65.0f) < 0.1f) templates[l][2] = elem;
                else if (Math.abs(col - 82.0f) < 0.1f) templates[l][3] = elem;
            }

            float newRow;
            if (isPathE) {
                newRow = isFirstSubRow ? 9 + 3*l : 10 + 3*l;
                if (col < 5.0f) {
                    float h = (float) heightField.get(elem);
                    if (h >= 1.9f) heightField.set(elem, 3.0f);
                }
            } else {
                if (col < 5.0f) {
                    newRow = 9 + 3*l;
                    float h = (float) heightField.get(elem);
                    if (h >= 1.9f) heightField.set(elem, 3.0f);
                } else if (col < 50.0f) {
                    if (isFirstSubRow) {
                        newRow = 9.15f + 3*l;
                        heightField.set(elem, 1.2f);
                    } else {
                        newRow = 10.65f + 3*l;
                        heightField.set(elem, 1.2f);
                    }
                } else {
                    newRow = isFirstSubRow ? 9 + 3*l : 10 + 3*l;
                }
            }
            rowField.set(elem, newRow);
        }

        for (int l = 0; l < 13; l++) {
            float newRow = 11.0f + 3*l;
            float newH = 1.0f;

            if (isPathE) {
                if (templates[l][0] != null)
                    cloneElement(builder, templates[l][0], newRow, 30.0f, newH, 1, 2);
                if (templates[l][1] != null)
                    cloneElement(builder, templates[l][1], newRow, 47.0f, newH, 1, 2);
                if (templates[l][2] != null)
                    cloneElement(builder, templates[l][2], newRow, 65.0f, newH, 4, 5);
                if (templates[l][3] != null)
                    cloneElement(builder, templates[l][3], newRow, 82.0f, newH, 4, 5);
            } else {
                if (templates[l][2] != null)
                    cloneElement(builder, templates[l][2], newRow, 65.0f, newH, 3, 4);
                if (templates[l][3] != null)
                    cloneElement(builder, templates[l][3], newRow, 82.0f, newH, 3, 4);
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
        if (barClass.isInstance(elem)) {
            Object binding = barBinding.get(elem);
            if (binding != null) replaceScellInBinding(binding, oldStr, newStr);
        } else if (multiClass.isInstance(elem)) {
            java.util.ArrayList<?> bindings = (java.util.ArrayList<?>) multiBindings.get(elem);
            if (bindings != null) {
                for (Object binding : bindings) {
                    if (binding != null) replaceScellInBinding(binding, oldStr, newStr);
                }
            }
        }
    }

    private void replaceScellInBinding(Object binding, String oldStr, String newStr) throws Exception {
        String key = (String) attrKey.get(binding);
        if (key != null && key.contains(oldStr)) {
            attrKey.set(binding, key.replace(oldStr, newStr));
        }
    }

    private void cloneElement(Object builder, Object template, float newRow, float newCol, float newH,
                              int oldScellIdx, int newScellIdx) throws Exception {
        String oldStr = "SCell" + oldScellIdx;
        String newStr = "SCell" + newScellIdx;
        float w = (float) widthField.get(template);

        if (labelClass.isInstance(template)) {
            Object newElem = addLabel.invoke(builder, newRow, newH, newCol, w);
            if (newElem != null) {
                labelText.set(newElem, labelText.get(template));
                labelAlign.set(newElem, labelAlign.get(template));
                labelSpan.set(newElem, labelSpan.get(template));
            }
        } else if (barClass.isInstance(template)) {
            Object newElem = addBar.invoke(builder, newRow, newH, newCol, w);
            if (newElem != null) {
                Object binding = barBinding.get(template);
                if (binding != null) {
                    Object newBinding = cloneBinding(binding, oldStr, newStr);
                    barBinding.set(newElem, newBinding);
                }
                boolean fixed = barFixedField.getBoolean(template);
                if (fixed) {
                    int color = barColorField.getInt(template);
                    float max = barMaxField.getFloat(template);
                    barFixedColor.invoke(newElem, color, max);
                }
            }
        } else if (multiClass.isInstance(template)) {
            Object newElem = addMulti.invoke(builder, newRow, newH, newCol, w);
            if (newElem != null) {
                java.util.ArrayList<?> bindings = (java.util.ArrayList<?>) multiBindings.get(template);
                if (bindings != null) {
                    java.util.ArrayList<Object> newBindings = new java.util.ArrayList<>();
                    for (Object binding : bindings) {
                        newBindings.add(cloneBinding(binding, oldStr, newStr));
                    }
                    multiBindings.set(newElem, newBindings);
                }
                multiSep.set(newElem, multiSep.get(template));
                multiColor.set(newElem, multiColor.get(template));
                multiApp.set(newElem, multiApp.get(template));
                multiGrav.set(newElem, multiGrav.get(template));
            }
        }
    }

    private Object cloneBinding(Object binding, String oldStr, String newStr) throws Exception {
        Class<?> bindingClass = binding.getClass();
        Object newBinding = unsafeAllocateInstance.invoke(unsafe, bindingClass);
        for (Field f : cachedInstanceFields(bindingClass)) {
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
        return newBinding;
    }

    /** Flattened, pre-accessible, non-static instance fields of the binding class
     *  (including superclass fields), cached per class. */
    private static Field[] cachedInstanceFields(Class<?> bindingClass) {
        Field[] cached = BINDING_FIELDS_CACHE.get(bindingClass);
        if (cached != null) return cached;
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        Class<?> c = bindingClass;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mods)) continue;
                f.setAccessible(true);
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        Field[] arr = fields.toArray(new Field[0]);
        Field[] prev = BINDING_FIELDS_CACHE.putIfAbsent(bindingClass, arr);
        return prev != null ? prev : arr;
    }

    private void updateHeaderText(java.util.ArrayList<?> list, float col, String text) throws Exception {
        for (Object elem : list) {
            float row = (float) rowField.get(elem);
            if (Math.abs(row - 8.0f) > 0.1f) continue;
            float c = (float) colField.get(elem);
            if (Math.abs(c - col) > 0.1f) continue;
            if (labelClass.isInstance(elem)) {
                labelText.set(elem, text);
            }
        }
    }

    private void injectRsrpRowPathD(Object builder, float rsrpRow) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;

        Object label = addLabel.invoke(builder, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) { labelText.set(label, "RSRP"); labelAlign.set(label, 0); labelSpan.set(label, 1); }

        Object pCellBar = addBar.invoke(builder, rsrpRow + 0.15f, leftH, 30.0f, 34.0f);
        if (pCellBar != null) barBinding.set(pCellBar, makeProp("LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));

        Object sCell1Bar = addBar.invoke(builder, rsrpRow + 1.65f, leftH, 30.0f, 34.0f);
        if (sCell1Bar != null) barBinding.set(sCell1Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));

        Object sCell2Bar = addBar.invoke(builder, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell2Bar != null) barBinding.set(sCell2Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));

        Object sCell3Bar = addBar.invoke(builder, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) barBinding.set(sCell3Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));

        Object sCell4Bar = addBar.invoke(builder, rsrpRow + 2.0f, barH, 65.0f, 34.0f);
        if (sCell4Bar != null) barBinding.set(sCell4Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell4", -1));
    }

    private void injectRsrpRowPathE(Object builder, float rsrpRow) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;

        Object label = addLabel.invoke(builder, rsrpRow, labelH, 0.0f, 27.0f);
        if (label != null) { labelText.set(label, "RSRP"); labelAlign.set(label, 0); labelSpan.set(label, 1); }

        Object pCellBar = addBar.invoke(builder, rsrpRow, barH, 30.0f, 34.0f);
        if (pCellBar != null) barBinding.set(pCellBar, makeProp("LTE::Downlink_Measurements::LTE_RSRP_PCell", -1));

        Object sCell1Bar = addBar.invoke(builder, rsrpRow + 1.0f, barH, 30.0f, 34.0f);
        if (sCell1Bar != null) barBinding.set(sCell1Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell1", -1));

        Object sCell2Bar = addBar.invoke(builder, rsrpRow + 2.0f, barH, 30.0f, 34.0f);
        if (sCell2Bar != null) barBinding.set(sCell2Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell2", -1));

        Object sCell3Bar = addBar.invoke(builder, rsrpRow, barH, 65.0f, 34.0f);
        if (sCell3Bar != null) barBinding.set(sCell3Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell3", -1));

        Object sCell4Bar = addBar.invoke(builder, rsrpRow + 1.0f, barH, 65.0f, 34.0f);
        if (sCell4Bar != null) barBinding.set(sCell4Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell4", -1));

        Object sCell5Bar = addBar.invoke(builder, rsrpRow + 2.0f, barH, 65.0f, 34.0f);
        if (sCell5Bar != null) barBinding.set(sCell5Bar, makeProp("LTE::Downlink_Measurements::SCC::LTE_RSRP_SCell5", -1));
    }

    private void injectRankUsageRowPathD(Object builder, float startRow) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;

        Object rank3Label = addLabel.invoke(builder, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) { labelText.set(rank3Label, "Rank3 Usage"); labelAlign.set(rank3Label, 0); labelSpan.set(rank3Label, 1); }
        injectRankBar(builder, startRow + 0.15f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL");
        injectRankBar(builder, startRow + 1.65f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL");
        injectRankBar(builder, startRow, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL");
        injectRankBar(builder, startRow + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL");
        injectRankBar(builder, startRow + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell4_DL");

        float rank4Row = startRow + 3.0f;
        Object rank4Label = addLabel.invoke(builder, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) { labelText.set(rank4Label, "Rank4 Usage"); labelAlign.set(rank4Label, 0); labelSpan.set(rank4Label, 1); }
        injectRankBar(builder, rank4Row + 0.15f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL");
        injectRankBar(builder, rank4Row + 1.65f, leftH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL");
        injectRankBar(builder, rank4Row, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL");
        injectRankBar(builder, rank4Row + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL");
        injectRankBar(builder, rank4Row + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell4_DL");
    }

    private void injectRankUsageRowPathE(Object builder, float startRow) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;

        Object rank3Label = addLabel.invoke(builder, startRow, labelH, 0.0f, 27.0f);
        if (rank3Label != null) { labelText.set(rank3Label, "Rank3 Usage"); labelAlign.set(rank3Label, 0); labelSpan.set(rank3Label, 1); }
        injectRankBar(builder, startRow, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank3_Usage_PCell_DL");
        injectRankBar(builder, startRow + 1.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell1_DL");
        injectRankBar(builder, startRow + 2.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell2_DL");
        injectRankBar(builder, startRow, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell3_DL");
        injectRankBar(builder, startRow + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell4_DL");
        injectRankBar(builder, startRow + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank3_Usage_SCell5_DL");

        float rank4Row = startRow + 3.0f;
        Object rank4Label = addLabel.invoke(builder, rank4Row, labelH, 0.0f, 27.0f);
        if (rank4Label != null) { labelText.set(rank4Label, "Rank4 Usage"); labelAlign.set(rank4Label, 0); labelSpan.set(rank4Label, 1); }
        injectRankBar(builder, rank4Row, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::PCC::LTE_Rank4_Usage_PCell_DL");
        injectRankBar(builder, rank4Row + 1.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell1_DL");
        injectRankBar(builder, rank4Row + 2.0f, barH, 30.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell2_DL");
        injectRankBar(builder, rank4Row, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell3_DL");
        injectRankBar(builder, rank4Row + 1.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell4_DL");
        injectRankBar(builder, rank4Row + 2.0f, barH, 65.0f, 34.0f, "LTE::Downlink_Measurements::SCC::LTE_Rank4_Usage_SCell5_DL");
    }

    private void injectRankBar(Object builder, float row, float h, float col, float w, String key) throws Exception {
        Object bar = addBar.invoke(builder, row, h, col, w);
        if (bar != null) {
            barBinding.set(bar, makeRankProp(key, -1));
            barFixedColor.invoke(bar, DEEP_BLUE, RANK_BAR_MAX);
        }
    }

    private void injectMcsRowPathD(Object builder, float row) throws Exception {
        final float labelH = 3.0f;
        final float leftH = 1.2f;
        final float barH = 1.0f;
        Object label = addLabel.invoke(builder, row, labelH, 0.0f, 27.0f);
        if (label != null) { labelText.set(label, "MCS Cwd 0/1"); labelAlign.set(label, 0); labelSpan.set(label, 1); }
        injectMcsBar(builder, row + 0.15f, leftH, 30.0f, 16.5f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(builder, row + 0.15f, leftH, 47.0f, 17.0f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(builder, row + 1.65f, leftH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(builder, row + 1.65f, leftH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(builder, row, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(builder, row, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(builder, row + 1.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(builder, row + 1.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
        injectMcsBar(builder, row + 2.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell4_DL");
        injectMcsBar(builder, row + 2.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell4_DL");
    }

    private void injectMcsRowPathE(Object builder, float row) throws Exception {
        final float labelH = 3.0f;
        final float barH = 1.0f;
        Object label = addLabel.invoke(builder, row, labelH, 0.0f, 27.0f);
        if (label != null) { labelText.set(label, "MCS Cwd 0/1"); labelAlign.set(label, 0); labelSpan.set(label, 1); }
        injectMcsBar(builder, row, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd0_PCell_DL");
        injectMcsBar(builder, row, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::PCC::LTE_MCS_Cwd1_PCell_DL");
        injectMcsBar(builder, row + 1.0f, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell1_DL");
        injectMcsBar(builder, row + 1.0f, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell1_DL");
        injectMcsBar(builder, row + 2.0f, barH, 30.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell2_DL");
        injectMcsBar(builder, row + 2.0f, barH, 47.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell2_DL");
        injectMcsBar(builder, row, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell3_DL");
        injectMcsBar(builder, row, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell3_DL");
        injectMcsBar(builder, row + 1.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell4_DL");
        injectMcsBar(builder, row + 1.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell4_DL");
        injectMcsBar(builder, row + 2.0f, barH, 65.0f, 16.5f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd0_SCell5_DL");
        injectMcsBar(builder, row + 2.0f, barH, 82.0f, 17.0f, "LTE::Downlink_Measurements::SCC::LTE_MCS_Cwd1_SCell5_DL");
    }
}
