package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Inserts a "PUCCH TX" row at position 8.0 on the NR-SA CA Matrix UL page (h8.c),
 * shifting the original row 8 (TxPower) and all subsequent rows up by +1.0.
 *
 * Row geometry:
 *   label: row=8.0, h=1.0, col=0.0, w=27.0, text="PUCCH TX", align=0, span=1
 *   bar:   row=8.0, h=1.0, col=30.0, w=34.0
 *          key="NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH", index=-1, format="%.1f dBm"
 *   SCell column (col=65) intentionally omitted — no SCell PUCCH TX data in NSG.
 *
 * Hook: AFTER h8.c.l0(Context) — called once per fragment lifecycle (guarded by Y==null in v6.b.I()).
 */
public class NrSaPucchTxRowHook {

    private static final String TAG = "NSGBandHook";

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

    // v6.b.Y field — k2.a instance on the fragment
    private Field v6bYField;

    private boolean ready = false;

    public NrSaPucchTxRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = loader.loadClass("k2.a");
            Class<?> veClass  = loader.loadClass("v6.e");
            Class<?> vfClass  = loader.loadClass("v6.f");
            Class<?> v6bClass = loader.loadClass("v6.b");

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

            v6bYField = v6bClass.getField("Y");

            ready = true;
            Log.i(TAG, "NrSaPucchTxRowHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaPucchTxRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaPucchTxRowHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> h8cClass = loader.loadClass("h8.c");
            Method l0Method = h8cClass.getMethod("l0", Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        injectPucchTxRow(thiz);
                    }
                    return result;
                }
            });
            Log.i(TAG, "NrSaPucchTxRowHook: h8.c.l0 hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaPucchTxRowHook: install failed: " + e);
        }
    }

    private void injectPucchTxRow(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "NrSaPucchTxRowHook: this.Y is null after l0(), skipping");
                return;
            }

            // Step 1: shift all elements at row >= 8.0 by +1.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= 8.0f) {
                        vaRowField.set(elem, elemRow + 1.0f);
                    }
                }
            }

            final float row = 8.0f;
            final float h   = 1.0f;

            // Step 2: inject label at row=8.0, h=1.0, col=0.0, w=27.0
            Object label = k2aRMethod.invoke(k2aObj, row, h, 0.0f, 27.0f);
            if (label != null) {
                veF.set(label, "PUCCH TX");
                veG.set(label, 0);
                veH.set(label, 1);
            }

            // Step 3: inject bar at row=8.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object bar = k2aSMethod.invoke(k2aObj, row, h, 30.0f, 34.0f);
            if (bar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, "NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH");
                sysAFieldB.set(prop, "%.1f dBm");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(bar, prop);
                // No .f(color, max) call — LegendManager auto-coloring via f8120g
            }

            Log.i(TAG, "NrSaPucchTxRowHook: PUCCH TX row injected at row 8.0");
        } catch (Exception e) {
            Log.w(TAG, "NrSaPucchTxRowHook: injectPucchTxRow failed: " + e);
        }
    }
}
