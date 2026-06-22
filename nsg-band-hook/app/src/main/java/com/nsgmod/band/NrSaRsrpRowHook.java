package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds "SS-RSRP" and "CSI-RSRP" rows immediately after the "ARFCN/PCI" row on the
 * NR-SA CA Matrix DL page (h8.b).
 *
 * This hook is installed BEFORE NrSaCsiSnrRowHook so it runs outermost (first) in the
 * v6.b.k0 chain. NrSaCsiSnrRowHook and NrSaModUsageRowHook have already been updated
 * to use insertion rows that account for the shift introduced here.
 *
 * Row geometry per carrier path:
 *
 *   Path A  carriers==2 (1 SCell, k0())  — single-height rows (h=1.0, barOffset=0, barH=1.0)
 *     ARFCN/PCI at row 9.  Insert SS-RSRP at 10, CSI-RSRP at 11. Shift ≥10 by +2.0.
 *     Columns: PCell col=30 w=34, SCell[0] col=65 w=34.
 *
 *   Path B  carriers==3 (2 SCells, inline o0())  — double-height rows (h=2.0, barOffset=+0.3, barH=1.4)
 *     ARFCN/PCI at row 10 (h=2). Insert SS-RSRP at 12, CSI-RSRP at 14. Shift ≥12 by +4.0.
 *     SS-RSRP:  PCell bar at row 12.3 h=1.4 col=30; SCell[0] bar at row 12.0 h=1.0 col=65;
 *               SCell[1] bar at row 13.0 h=1.0 col=65.
 *     CSI-RSRP: PCell bar at row 14.3 h=1.4 col=30 only.
 *
 *   Path C  carriers>=4 (3 SCells, l0())  — single-height rows (h=1.0, barOffset=0, barH=1.0)
 *     ARFCN/PCI at row 10 (sub-rows 10+11). Insert SS-RSRP at 12, CSI-RSRP at 14. Shift ≥12 by +3.0.
 *     SS-RSRP:  PCell col=30 row=12; SCell[0] col=30 row=13; SCell[1] col=65 row=12; SCell[2] col=65 row=13.
 *     CSI-RSRP: PCell col=30 row=14 only (same style as CSI SNR / ModUsage in l0()).
 *
 * Property keys:
 *   PCell SS-RSRP  : NR5G::Downlink_Measurements::NR_SS_RSRP       index=-1  format="%.1f dBm"
 *   SCell SS-RSRP  : NR5G::Cell_Measurements::NR_Cells_RSRP         index=N   format="%.1f dBm"
 *   CSI-RSRP PCell : NR5G::Downlink_Measurements::NR_CSI_RSRP       index=-1  format="%.1f dBm"
 */
public class NrSaRsrpRowHook {

    private static final String TAG = "NSGBandHook";

    // Shared ThreadLocal owned by NrSaCsiSnrRowHook — read-only here.
    private final ThreadLocal<Integer> carrierCountInO0;

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

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    private boolean ready = false;

    public NrSaRsrpRowHook(XposedInterface xposed, ClassLoader loader,
                           ThreadLocal<Integer> carrierCountInO0) {
        this.xposed = xposed;
        this.loader = loader;
        this.carrierCountInO0 = carrierCountInO0;
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
                unsafeField = unsafeClass.getDeclaredField("THE_ONE"); // Android/Dalvik
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe"); // OpenJDK fallback
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = loader.loadClass("v6.a");
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            ready = true;
            Log.i(TAG, "NrSaRsrpRowHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaRsrpRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaRsrpRowHook: skipping install — reflection not ready");
            return;
        }
        installV6bK0Hook();
    }

    // -----------------------------------------------------------------------
    // Hook: static v6.b.k0(k2.a) — inject when called from h8.b.o0()
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
                    Integer carriers = carrierCountInO0.get();
                    boolean inO0     = carriers != null;
                    if (inO0 && k2aArg != null) {
                        injectRsrpRows(k2aArg, carriers);
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaRsrpRowHook: v6.b.k0 hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaRsrpRowHook: v6.b.k0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Injection: insert SS-RSRP and CSI-RSRP rows after ARFCN/PCI row
    // -----------------------------------------------------------------------

    private void injectRsrpRows(Object k2aObj, int carriers) {
        try {
            // Determine path geometry
            boolean isPathA   = (carriers == 2);  // 1 SCell, single-height
            boolean isInline3 = (carriers == 3);  // 2 SCells, double-height inline
            // carriers >= 4: 3 SCells, single-height l0() path

            // SS-RSRP and CSI-RSRP both insert right after ARFCN/PCI.
            // Path A: ARFCN/PCI at row 9 → insert SS-RSRP at 10, CSI-RSRP at 11
            //         Each logical row has height 1.0 → total shift = 2.0
            // Path B: ARFCN/PCI at row 10 (h=2) → insert SS-RSRP at 12, CSI-RSRP at 14
            //         Each logical row has height 2.0 → total shift = 4.0
            // Path C: ARFCN/PCI at row 10 (sub-rows 10+11) → insert SS-RSRP at 12, CSI-RSRP at 14
            //         Each logical row spans 2 sub-rows at height 1.0 → total shift = 4.0
            float ssRsrpRow;   // row of SS-RSRP label / first bar
            float csiRsrpRow;  // row of CSI-RSRP label / bar
            float shiftFrom;   // shift all elements at row >= shiftFrom
            float shiftAmount; // how much to shift

            if (isPathA) {
                ssRsrpRow   = 10.0f;
                csiRsrpRow  = 11.0f;
                shiftFrom   = 10.0f;
                shiftAmount = 2.0f;
            } else if (isInline3) {
                // Path B: double-height rows, each logical row h=2.0 → 2 rows × 2.0 = 4.0
                ssRsrpRow   = 12.0f;
                csiRsrpRow  = 14.0f;
                shiftFrom   = 12.0f;
                shiftAmount = 4.0f;
            } else {
                // Path C: SS-RSRP 2 sub-rows + CSI-RSRP 1 sub-row = 3.0
                ssRsrpRow   = 12.0f;
                csiRsrpRow  = 14.0f;
                shiftFrom   = 12.0f;
                shiftAmount = 3.0f;
            }

            // Shift all existing elements that fall at or after the insertion point
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= shiftFrom) {
                        vaRowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            if (isPathA) {
                injectRsrpRowsPathA(k2aObj, ssRsrpRow, csiRsrpRow);
            } else if (isInline3) {
                injectRsrpRowsPathB(k2aObj, ssRsrpRow, csiRsrpRow);
            } else {
                injectRsrpRowsPathC(k2aObj, ssRsrpRow, csiRsrpRow);
            }

            Log.i(TAG, "NrSaRsrpRowHook: SS-RSRP+CSI-RSRP rows injected"
                    + " ssRsrpRow=" + ssRsrpRow
                    + " csiRsrpRow=" + csiRsrpRow
                    + " carriers=" + carriers);
        } catch (Exception e) {
            Log.w(TAG, "NrSaRsrpRowHook: injectRsrpRows failed: " + e);
        }
    }

    /**
     * Path A: carriers==2 (1 SCell), single-height rows (h=1.0, barOffset=0, barH=1.0).
     *
     * SS-RSRP row at ssRsrpRow:
     *   label col=0 w=27
     *   PCell bar col=30 w=34  key=NR_SS_RSRP index=-1
     *   SCell[0] bar col=65 w=34  key=NR_Cells_RSRP index=1
     *
     * CSI-RSRP row at csiRsrpRow:
     *   label col=0 w=27
     *   PCell bar col=30 w=34  key=NR_CSI_RSRP index=-1
     */
    private void injectRsrpRowsPathA(Object k2aObj, float ssRsrpRow, float csiRsrpRow)
            throws Exception {
        final float h = 1.0f;

        // SS-RSRP label
        Object ssLabel = k2aRMethod.invoke(k2aObj, ssRsrpRow, h, 0.0f, 27.0f);
        if (ssLabel != null) {
            veF.set(ssLabel, "SS-RSRP");
            veG.set(ssLabel, 0);
            veH.set(ssLabel, 1);
        }

        // SS-RSRP PCell bar
        Object ssPCellBar = k2aSMethod.invoke(k2aObj, ssRsrpRow, h, 30.0f, 34.0f);
        if (ssPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_SS_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(ssPCellBar, prop);
        }

        // SS-RSRP SCell[0] bar
        Object ssSCell0Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow, h, 65.0f, 34.0f);
        if (ssSCell0Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 1);
            vfF8120g.set(ssSCell0Bar, prop);
        }

        // CSI-RSRP label
        Object csiLabel = k2aRMethod.invoke(k2aObj, csiRsrpRow, h, 0.0f, 27.0f);
        if (csiLabel != null) {
            veF.set(csiLabel, "CSI-RSRP");
            veG.set(csiLabel, 0);
            veH.set(csiLabel, 1);
        }

        // CSI-RSRP PCell bar (PCell only)
        Object csiPCellBar = k2aSMethod.invoke(k2aObj, csiRsrpRow, h, 30.0f, 34.0f);
        if (csiPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_CSI_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(csiPCellBar, prop);
        }
    }

    /**
     * Path B: carriers==3 (2 SCells), double-height rows (h=2.0, PCell barOffset=+0.3, barH=1.4).
     * SCell bars use h=1.0, no offset; they stack at row and row+1 within col=65.
     *
     * SS-RSRP at ssRsrpRow (=12.0):
     *   label row=12.0 h=2.0 col=0 w=27
     *   PCell bar row=12.3 h=1.4 col=30 w=34  key=NR_SS_RSRP index=-1
     *   SCell[0] bar row=12.0 h=1.0 col=65 w=34  key=NR_Cells_RSRP index=1
     *   SCell[1] bar row=13.0 h=1.0 col=65 w=34  key=NR_Cells_RSRP index=2
     *
     * CSI-RSRP at csiRsrpRow (=14.0):
     *   label row=14.0 h=2.0 col=0 w=27
     *   PCell bar row=14.3 h=1.4 col=30 w=34  key=NR_CSI_RSRP index=-1
     */
    private void injectRsrpRowsPathB(Object k2aObj, float ssRsrpRow, float csiRsrpRow)
            throws Exception {
        final float labelH     = 2.0f;
        final float pcellBarH  = 1.4f;
        final float pcellOff   = 0.3f;
        final float scellBarH  = 1.0f;

        // SS-RSRP label
        Object ssLabel = k2aRMethod.invoke(k2aObj, ssRsrpRow, labelH, 0.0f, 27.0f);
        if (ssLabel != null) {
            veF.set(ssLabel, "SS-RSRP");
            veG.set(ssLabel, 0);
            veH.set(ssLabel, 1);
        }

        // SS-RSRP PCell bar
        Object ssPCellBar = k2aSMethod.invoke(k2aObj, ssRsrpRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (ssPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_SS_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(ssPCellBar, prop);
        }

        // SS-RSRP SCell[0] bar — first sub-row in col=65
        Object ssSCell0Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow, scellBarH, 65.0f, 34.0f);
        if (ssSCell0Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 1);
            vfF8120g.set(ssSCell0Bar, prop);
        }

        // SS-RSRP SCell[1] bar — second sub-row in col=65
        Object ssSCell1Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow + 1.0f, scellBarH, 65.0f, 34.0f);
        if (ssSCell1Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 2);
            vfF8120g.set(ssSCell1Bar, prop);
        }

        // CSI-RSRP label
        Object csiLabel = k2aRMethod.invoke(k2aObj, csiRsrpRow, labelH, 0.0f, 27.0f);
        if (csiLabel != null) {
            veF.set(csiLabel, "CSI-RSRP");
            veG.set(csiLabel, 0);
            veH.set(csiLabel, 1);
        }

        // CSI-RSRP PCell bar (PCell only)
        Object csiPCellBar = k2aSMethod.invoke(k2aObj, csiRsrpRow + pcellOff, pcellBarH, 30.0f, 34.0f);
        if (csiPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_CSI_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(csiPCellBar, prop);
        }
    }

    /**
     * Path C: carriers>=4 (3 SCells), single-height rows (h=1.0, barOffset=0, barH=1.0).
     * Each logical row occupies 2 sub-rows: col=30 gets PCell+SCell[0], col=65 gets SCell[1]+SCell[2].
     * CSI-RSRP is PCell-only with same single-height style as CSI SNR / ModUsage in l0().
     *
     * SS-RSRP at ssRsrpRow (=12.0):
     *   label row=12.0 h=1.0 col=0 w=27
     *   PCell bar  row=12.0 h=1.0 col=30 w=34  key=NR_SS_RSRP index=-1
     *   SCell[0] bar row=13.0 h=1.0 col=30 w=34  key=NR_Cells_RSRP index=1
     *   SCell[1] bar row=12.0 h=1.0 col=65 w=34  key=NR_Cells_RSRP index=2
     *   SCell[2] bar row=13.0 h=1.0 col=65 w=34  key=NR_Cells_RSRP index=3
     *
     * CSI-RSRP at csiRsrpRow (=14.0):
     *   label row=14.0 h=1.0 col=0 w=27
     *   PCell bar row=14.0 h=1.0 col=30 w=34  key=NR_CSI_RSRP index=-1
     */
    private void injectRsrpRowsPathC(Object k2aObj, float ssRsrpRow, float csiRsrpRow)
            throws Exception {
        final float h = 1.0f;

        // SS-RSRP label (at first sub-row)
        Object ssLabel = k2aRMethod.invoke(k2aObj, ssRsrpRow, h, 0.0f, 27.0f);
        if (ssLabel != null) {
            veF.set(ssLabel, "SS-RSRP");
            veG.set(ssLabel, 0);
            veH.set(ssLabel, 1);
        }

        // SS-RSRP PCell bar  — col=30 sub-row 0
        Object ssPCellBar = k2aSMethod.invoke(k2aObj, ssRsrpRow, h, 30.0f, 34.0f);
        if (ssPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_SS_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(ssPCellBar, prop);
        }

        // SS-RSRP SCell[0] bar — col=30 sub-row 1
        Object ssSCell0Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow + 1.0f, h, 30.0f, 34.0f);
        if (ssSCell0Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 1);
            vfF8120g.set(ssSCell0Bar, prop);
        }

        // SS-RSRP SCell[1] bar — col=65 sub-row 0
        Object ssSCell1Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow, h, 65.0f, 34.0f);
        if (ssSCell1Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 2);
            vfF8120g.set(ssSCell1Bar, prop);
        }

        // SS-RSRP SCell[2] bar — col=65 sub-row 1
        Object ssSCell2Bar = k2aSMethod.invoke(k2aObj, ssRsrpRow + 1.0f, h, 65.0f, 34.0f);
        if (ssSCell2Bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Cell_Measurements::NR_Cells_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, 3);
            vfF8120g.set(ssSCell2Bar, prop);
        }

        // CSI-RSRP label — single-height, same style as CSI SNR / ModUsage in l0()
        Object csiLabel = k2aRMethod.invoke(k2aObj, csiRsrpRow, h, 0.0f, 27.0f);
        if (csiLabel != null) {
            veF.set(csiLabel, "CSI-RSRP");
            veG.set(csiLabel, 0);
            veH.set(csiLabel, 1);
        }

        // CSI-RSRP PCell bar (PCell only)
        Object csiPCellBar = k2aSMethod.invoke(k2aObj, csiRsrpRow, h, 30.0f, 34.0f);
        if (csiPCellBar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_CSI_RSRP");
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(csiPCellBar, prop);
        }
    }
}
