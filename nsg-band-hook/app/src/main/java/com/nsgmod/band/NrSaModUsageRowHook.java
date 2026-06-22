package com.nsgmod.band;

import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds "16QAM" and "QPSK" percentage rows (PCell only) directly below "64Q Util."
 * on the NR-SA CA Matrix DL page (h8.b), above "Phy. Thput".
 *
 * Uses the same hook architecture as NrSaCsiSnrRowHook:
 *   - h8.b.o0() intercept sets a ThreadLocal with the carrier count
 *   - v6.b.k0(k2.a) intercept injects the new rows into the builder
 *
 * Row positions (per path, after NrSaRsrpRowHook +2/+4 and NrSaCsiSnrRowHook +1/+2 shifts):
 *   Path A k0()  carriers==2 : 64Q at 23, Phy.Thput at 27 → insert at 27, shift by 2.0f
 *   Path B l0()  carriers>=4 : 64Q at 38, Phy.Thput at 45 → insert at 45, shift by 2.0f
 *   Path C inline carriers==3 : 64Q label at 38 h=2.0, bar at 38.3 h=1.4
 *                               → insert at 46 (=38+2+4+2), shift by 4.0f (2 rows × 2.0f)
 *
 * Property keys (flat namespace — PCell variants for 16QAM/QPSK don't exist in NSG):
 *   16QAM DL : NR5G::Downlink_Measurements::NR_ModUsage_16QAM_DL
 *   QPSK DL  : NR5G::Downlink_Measurements::NR_ModUsage_QPSK_DL
 *
 * Bar style: v6.f (same as 64Q Util. bars), deep blue via v6.f.f(colorInt, 100.0f).
 */
public class NrSaModUsageRowHook {

    private static final String TAG = "NSGBandHook";

    // Shared ThreadLocal with NrSaCsiSnrRowHook — we read it, not own it.
    // Passed in via constructor from MainHook.
    private final ThreadLocal<Integer> carrierCountInO0;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) → v6.e  (label)
    private Method k2aSMethod; // s(float row, float h, float col, float w) → v6.f  (bar)

    // v6.e label fields (actual bytecode names)
    private Field veF;  // text   (JADX: f8116f)
    private Field veG;  // align  (JADX: f8117g)
    private Field veH;  // span

    // v6.f bar field + style method
    private Field   vfG;        // data binding (JADX: f8120g)
    private Method  vfFMethod;  // void f(int color, float max)

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field    sysAFieldA; // key string
    private Field    sysAFieldB; // format string
    private Field    sysAFieldC; // index int

    // Unsafe for allocateInstance (com.qtrun.sys.b ctor stripped by ProGuard)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    // Deep blue color int (resolved from host app resources)
    private int deepBlueColor = 0;

    private boolean ready = false;

    public NrSaModUsageRowHook(XposedInterface xposed, ClassLoader loader,
                               ThreadLocal<Integer> carrierCountInO0) {
        this.xposed           = xposed;
        this.loader           = loader;
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

            vfG = vfClass.getDeclaredField("g");
            vfG.setAccessible(true);
            vfFMethod = vfClass.getMethod("f", int.class, float.class);

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
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = loader.loadClass("v6.a");
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            // Resolve deep blue color from host app resources via ActivityThread
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Method   curApp  = atClass.getMethod("currentApplication");
                android.content.Context ctx = (android.content.Context) curApp.invoke(null);
                if (ctx != null) {
                    Resources res = ctx.getResources();
                    int colorId = res.getIdentifier("color_deep_blue", "color", ctx.getPackageName());
                    if (colorId != 0) {
                        deepBlueColor = ctx.getColor(colorId);
                        Log.i(TAG, "NrSaModUsageRowHook: color_deep_blue=0x"
                                + Integer.toHexString(deepBlueColor));
                    } else {
                        deepBlueColor = 0xFF1565C0;
                        Log.w(TAG, "NrSaModUsageRowHook: color_deep_blue not found, fallback");
                    }
                } else {
                    deepBlueColor = 0xFF1565C0;
                }
            } catch (Exception ce) {
                deepBlueColor = 0xFF1565C0;
                Log.w(TAG, "NrSaModUsageRowHook: color lookup failed: " + ce);
            }

            ready = true;
            Log.i(TAG, "NrSaModUsageRowHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaModUsageRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaModUsageRowHook: skipping install — reflection not ready");
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
                        injectModUsageRows(k2aArg, carriers);
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaModUsageRowHook: v6.b.k0 hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaModUsageRowHook: v6.b.k0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Injection: insert 16QAM and QPSK rows after 64Q Util., before Phy. Thput
    // -----------------------------------------------------------------------

    private void injectModUsageRows(Object k2aObj, int carriers) {
        try {
            // Per-path geometry — matches h8.b exactly
            //   Path A  carriers==2 : 64Q at row 23, label h=1.0, bar h=1.0, no offset
            //   Path B  carriers>=4 : 64Q at row 38, label h=1.0, bar h=1.0, no offset
            //   Path C  carriers==3 : 64Q at row 38, label h=2.0, bar h=1.4, offset +0.3f
            boolean isInline3 = (carriers == 3);
            boolean isPathA   = (carriers == 2);

            // insertRow = Phy.Thput row AFTER NrSaRsrpRowHook and NrSaCsiSnrRowHook have already shifted things.
            //
            // Original (pre-hook) Phy.Thput positions:
            //   Path A carriers==2 : 24  — RSRP +2.0, CSI SNR +1.0  → 27
            //   Path B carriers>=4 : 40  — RSRP +3.0, CSI SNR +1.0  → 44
            //   Path C carriers==3 : 40  — RSRP +4.0, CSI SNR +2.0  → 46
            float insertRow;
            if (isPathA) {
                insertRow = 27.0f;  // 24 + 1 (CSI SNR) + 2 (SS-RSRP+CSI-RSRP rows)
            } else if (isInline3) {
                insertRow = 46.0f;  // 40 + 2 (CSI SNR) + 4 (SS-RSRP+CSI-RSRP rows)
            } else {
                insertRow = 44.0f;  // 40 + 1 (CSI SNR) + 3 (SS-RSRP+CSI-RSRP rows)
            }
            float labelHeight = isInline3 ? 2.0f : 1.0f;
            float barHeight   = isInline3 ? 1.4f : 1.0f;
            float barOffset   = isInline3 ? 0.3f : 0.0f;

            // Two new rows inserted at insertRow (16QAM) and insertRow+labelHeight (QPSK)
            float row1 = insertRow;                // 16QAM
            float row2 = insertRow + labelHeight;  // QPSK
            float shiftAmount = 2 * labelHeight;   // make room for both rows

            // Shift all existing elements at row >= row1 down by shiftAmount
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= row1) {
                        vaRowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            // --- 16QAM label ---
            Object label16 = k2aRMethod.invoke(k2aObj, row1, labelHeight, 0.0f, 27.0f);
            if (label16 != null) {
                veF.set(label16, "16Q Util.");
                veG.set(label16, 0);
                veH.set(label16, 1);
            }

            // --- 16QAM PCell bar ---
            Object bar16 = k2aSMethod.invoke(k2aObj, row1 + barOffset, barHeight, 30.0f, 34.0f);
            if (bar16 != null) {
                Object prop16 = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop16, "NR5G::Downlink_Measurements::NR_ModUsage_16QAM_DL");
                sysAFieldB.set(prop16, "%.1f %%");
                sysAFieldC.set(prop16, -1);
                vfG.set(bar16, prop16);
                vfFMethod.invoke(bar16, deepBlueColor, 100.0f);
            }

            // --- QPSK label ---
            Object labelQpsk = k2aRMethod.invoke(k2aObj, row2, labelHeight, 0.0f, 27.0f);
            if (labelQpsk != null) {
                veF.set(labelQpsk, "QPSK Util.");
                veG.set(labelQpsk, 0);
                veH.set(labelQpsk, 1);
            }

            // --- QPSK PCell bar ---
            Object barQpsk = k2aSMethod.invoke(k2aObj, row2 + barOffset, barHeight, 30.0f, 34.0f);
            if (barQpsk != null) {
                Object propQpsk = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(propQpsk, "NR5G::Downlink_Measurements::NR_ModUsage_QPSK_DL");
                sysAFieldB.set(propQpsk, "%.1f %%");
                sysAFieldC.set(propQpsk, -1);
                vfG.set(barQpsk, propQpsk);
                vfFMethod.invoke(barQpsk, deepBlueColor, 100.0f);
            }

            Log.i(TAG, "NrSaModUsageRowHook: 16QAM+QPSK rows injected at rows "
                    + row1 + "/" + row2 + " (carriers=" + carriers + ")");
        } catch (Exception e) {
            Log.w(TAG, "NrSaModUsageRowHook: injectModUsageRows failed: " + e);
        }
    }
}
