package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "CSI SNR" row (PCell only) to the NR-SA CA Matrix DL page (h8.b).
 *
 * Architecture:
 *   h8.b.o0() dispatches to k0(d1.g), l0(d1.g), or inline code based on SCell count.
 *   All paths end with:  v6.b bVarK0 = v6.b.k0(aVarL0);  [line 1104]
 *
 * Strategy:
 *   Hook h8.b.o0() with a before+after wrapper that sets a ThreadLocal flag.
 *   Hook v6.b.k0(k2.a) — inject the CSI SNR row into the k2.a when flag is set.
 *
 * Fallback:
 *   Also directly hook h8.b.k0(d1.g) and h8.b.l0(d1.g) in case the static
 *   v6.b.k0 hook doesn't fire (e.g. due to static dispatch issues).
 *
 * Row layout (float f8101b = row position, used via Math.floor for layout):
 *   20/21 = SS-SNR (existing)
 *   21.5  = CSI SNR injected — floors to row 21, placed at end of ArrayList
 *           but rows are layout-positioned by f8101b not insertion order
 *   22/23 = RBs (existing)
 */
public class NrSaCsiSnrRowHook {

    private static final String TAG = "NSGBandHook";

    static final ThreadLocal<Integer> carrierCountInO0 = new ThreadLocal<>();

    /**
     * Maps k2.a builder instances created inside h8.b.o0() to their carrier count.
     * Populated by the v6.b.k0 hook (which fires inside o0()).
     * Used by NrSaCellColorHook to identify SA CA Matrix builders when v6.b.I() fires later.
     * WeakHashMap so k2.a objects are not prevented from being GC'd.
     */
    static final Map<Object, Integer> saK2aCarriers =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Method k2aRMethod;
    private Method k2aSMethod;

    private Field veF8116f;
    private Field veF8117g;
    private Field veH;

    private Field vfF8120g;

    private Class<?> sysBClass;
    // Fields on com.qtrun.sys.a (superclass of b) — actual bytecode names after JADX rename
    private Field sysAFieldA; // "a" = key string   (JADX: f3868a), declared final
    private Field sysAFieldB; // "b" = format string (JADX: f3869b), declared final
    private Field sysAFieldC; // "c" = index int     (JADX: f3870c)
    private Object unsafe;    // sun.misc.Unsafe instance, held as Object to avoid compile dep
    private java.lang.reflect.Method unsafeAllocateInstance;

    // h8.b.f5066a0 = carrier count (2 = PCell only/1SCell, 3 = 2SCells, ...)
    // Actual bytecode field name after JADX rename: "a0"
    private Field h8bCarrierCountField;

    // k2.a ArrayList of v6.a elements — actual bytecode name "d" (JADX: f5437d)
    private Field k2aListField;
    // v6.a row position float — actual bytecode name "b" (JADX: f8101b)
    private Field vaRowField;

    private boolean ready = false;

    public NrSaCsiSnrRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass  = loader.loadClass("k2.a");
            Class<?> veClass   = loader.loadClass("v6.e");
            Class<?> vfClass   = loader.loadClass("v6.f");

            k2aRMethod = k2aClass.getMethod("r", float.class, float.class, float.class, float.class);
            k2aSMethod = k2aClass.getMethod("s", float.class, float.class, float.class, float.class);

            // JADX renames fields to avoid collision with package names, e.g. "f" in package v6.f
            // becomes "f8116f". The actual bytecode field names are the originals: "f", "g", "h".
            veF8116f = veClass.getField("f"); // label text (renamed from "f" → "f8116f" by JADX)
            veF8117g = veClass.getField("g"); // alignment  (renamed from "g" → "f8117g" by JADX)
            veH      = veClass.getField("h"); // span       (not renamed)

            // v6.f.g field type is com.qtrun.sys.b; ProGuard stripped b's constructor.
            // Use Unsafe.allocateInstance(b.class) to create a real b instance without a ctor,
            // then set the inherited final fields from a via Unsafe putObject/putInt.
            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);

            sysBClass = loader.loadClass("com.qtrun.sys.b");
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");
            sysAFieldA = sysAClass.getDeclaredField("a"); // final String key
            sysAFieldB = sysAClass.getDeclaredField("b"); // final String format
            sysAFieldC = sysAClass.getDeclaredField("c"); // int index
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

            // Obtain sun.misc.Unsafe via reflection (not importable in Android SDK compile)
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

            // h8.b.f5066a0 = carrier count field; JADX renames it "f5066a0", actual name "a0"
            Class<?> h8bClass = loader.loadClass("h8.b");
            h8bCarrierCountField = h8bClass.getDeclaredField("a0");
            h8bCarrierCountField.setAccessible(true);

            // k2.a ArrayList field "d" (JADX: f5437d); v6.a row float field "b" (JADX: f8101b)
            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = loader.loadClass("v6.a");
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            ready = true;
            Log.i(TAG, "NrSaCsiSnrRowHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCsiSnrRowHook: skipping install — reflection not ready");
            return;
        }
        installO0FlagHook();
        installV6bK0Hook();
    }

    // -----------------------------------------------------------------------
    // Hook 1: h8.b.o0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private void installO0FlagHook() {
        try {
            Class<?> h8bClass = loader.loadClass("h8.b");
            Method   o0Method = h8bClass.getMethod("o0");

            xposed.hook(o0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int carriers = -1;
                    try {
                        carriers = (int) h8bCarrierCountField.get(chain.getThisObject());
                    } catch (Exception e) {
                        Log.w(TAG, "NrSaCsiSnrRowHook: could not read carrier count: " + e);
                    }
                    carrierCountInO0.set(carriers);
                    try {
                        return chain.proceed();
                    } finally {
                        carrierCountInO0.remove();
                    }
                }
            });
            Log.i(TAG, "NrSaCsiSnrRowHook: o0 flag hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: o0 flag hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: static v6.b.k0(k2.a) — inject when called from h8.b.o0()
    // -----------------------------------------------------------------------

    private void installV6bK0Hook() {
        try {
            Class<?> v6bClass = loader.loadClass("v6.b");
            Class<?> k2aClass = loader.loadClass("k2.a");
            Method   k0Method = v6bClass.getMethod("k0", k2aClass);

            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object k2aArg = chain.getArg(0);
                    Integer carriers = carrierCountInO0.get();
                    boolean inO0 = carriers != null;
                    if (inO0 && k2aArg != null) {
                        // Record this k2.a as belonging to the SA CA Matrix screen
                        saK2aCarriers.put(k2aArg, carriers);
                        // carriers == 2: 1 SCell — SNR at row 14, inject before RBs at 15
                        // carriers >= 3: 2+ SCells — SNR at row 20, inject before RBs at 22
                        // row param is used to compute insertRow = ceil(row)
                        // +2.0 (carriers==2) or +4.0 (carriers>=3) to account for SS-RSRP and CSI-RSRP rows injected before us
                        float row = (carriers != null && carriers >= 3) ? 25.5f : 16.5f;
                        injectCsiSnrRow(k2aArg, row, carriers != null ? carriers : 2);
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaCsiSnrRowHook: v6.b.k0 hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: v6.b.k0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Injection: append CSI SNR label + PCell bar to k2.a builder
    // -----------------------------------------------------------------------

    private void injectCsiSnrRow(Object k2aObj, float row, int carriers) {
        try {
            float insertRow = (float) Math.ceil(row); // 14.5 → 15, 21.5 → 22

            // In the 3-SCell inline path (carriers == 3), labels use height 2.0f and
            // PCell bars use a +0.3f row offset with height 1.4f — match that exactly.
            boolean isInline3 = (carriers == 3);
            float labelHeight = isInline3 ? 2.0f : 1.0f;
            float barRowOffset = isInline3 ? 0.3f : 0.0f;
            float barHeight    = isInline3 ? 1.4f : 1.0f;
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= insertRow) {
                        vaRowField.set(elem, elemRow + labelHeight);
                    }
                }
            }

            // Inject label at insertRow, col 0..27
            Object labelElem = k2aRMethod.invoke(k2aObj, insertRow, labelHeight, 0.0f, 27.0f);
            if (labelElem != null) {
                veF8116f.set(labelElem, "CSI SNR");
                veF8117g.set(labelElem, 0);
                veH.set(labelElem, 1);
            }

            // Inject PCell bar at same row (+offset), col 30..34
            Object barElem = k2aSMethod.invoke(k2aObj, insertRow + barRowOffset, barHeight, 30.0f, 34.0f);
            if (barElem != null) {
                Object propBinding = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(propBinding, "NR5G::Downlink_Measurements::NR_CSI_SINR");
                sysAFieldB.set(propBinding, "%.1f dB");
                sysAFieldC.set(propBinding, -1);
                vfF8120g.set(barElem, propBinding);
            }

            Log.i(TAG, "NrSaCsiSnrRowHook: CSI SNR row injected at row=" + insertRow + " into " + k2aObj.getClass().getName());
        } catch (Exception e) {
            Log.w(TAG, "NrSaCsiSnrRowHook: injectCsiSnrRow failed: " + e);
        }
    }
}
