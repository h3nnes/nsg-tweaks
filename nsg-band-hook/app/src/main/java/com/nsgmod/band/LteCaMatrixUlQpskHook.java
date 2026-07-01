package com.nsgmod.band;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "QPSK Util." row on the LTE CA Matrix UL page (e8.a),
 * between CQI (row 18) and 16Q Usage (row 19), for UL PCell only.
 *
 * The existing 16Q/64Q/256Q usage rows show the full set of modulation
 * percentages. QPSK is missing from the CA Matrix but is available on
 * the LTE Dedicated Mode page (e8.i) via:
 *   LTE::Uplink_Measurements::LTE_ModUsage_QPSK_UL
 *
 * Architecture:
 *   e8.a extends v6.b; l0(Context) builds directly on this.Y (k2.a).
 *   Hook is an after-hook on e8.a.l0(Context).
 *
 * Row layout (single-height, h=1.0):
 *   label: row=19.0 h=1.0 col=0.0 w=27.0  text="QPSK Util."
 *   bar:   row=19.0 h=1.0 col=30.0 w=34.0  key=QPSK_UL index=-1 format="%.1f %%"
 *   bar color: color_deep_blue via v6.f.f(color, 100.0f)
 *   No SCell bar — the property key has no SCell-specific variant.
 */
public class LteCaMatrixUlQpskHook {

    private static final String TAG = "NSGBandHook";
    private static final String QPSK_KEY =
            "LTE::Uplink_Measurements::LTE_ModUsage_QPSK_UL";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) → v6.e (label)
    private Method k2aSMethod; // s(float row, float h, float col, float w) → v6.f (bar)

    // v6.e label fields
    private Field veF; // text
    private Field veG; // align
    private Field veH; // span

    // v6.f bar fields + style method
    private Field vfF8120g; // g — data binding
    private Method vfFMethod; // void f(int color, float max)

    // com.qtrun.sys.b / a
    private Class<?> sysBClass;
    private Field sysAFieldA; // key String
    private Field sysAFieldB; // format String
    private Field sysAFieldC; // index int

    // Unsafe (com.qtrun.sys.b ctor stripped by ProGuard)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    // v6.b.Y field — k2.a builder on the fragment
    private Field v6bYField;

    // Deep blue color (cached from host app resources)
    private int deepBlueColor = 0;

    private boolean ready = false;

    public LteCaMatrixUlQpskHook(XposedInterface xposed, ClassLoader loader) {
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

            v6bYField = v6bClass.getField("Y");

            // Resolve deep blue color from host app resources
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Method curApp = atClass.getMethod("currentApplication");
                Context ctx = (Context) curApp.invoke(null);
                if (ctx != null) {
                    Resources res = ctx.getResources();
                    int colorId = res.getIdentifier("color_deep_blue", "color", ctx.getPackageName());
                    if (colorId != 0) {
                        deepBlueColor = ctx.getColor(colorId);
                    } else {
                        deepBlueColor = 0xFF1565C0;
                        Log.w(TAG, "LteCaMatrixUlQpskHook: color_deep_blue not found, fallback");
                    }
                } else {
                    deepBlueColor = 0xFF1565C0;
                }
            } catch (Exception ce) {
                deepBlueColor = 0xFF1565C0;
                Log.w(TAG, "LteCaMatrixUlQpskHook: color lookup failed: " + ce);
            }

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "LteCaMatrixUlQpskHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "LteCaMatrixUlQpskHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> e8aClass = loader.loadClass("e8.a");
            Class<?> contextClass = Class.forName("android.content.Context");
            Method l0Method = e8aClass.getMethod("l0", contextClass);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        injectQpskRow(thiz);
                    }
                    return result;
                }
            });
            Log.i(TAG, "LteCaMatrixUlQpskHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "LteCaMatrixUlQpskHook: install failed: " + e);
        }
    }

    private void injectQpskRow(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "LteCaMatrixUlQpskHook: this.Y is null after l0(), skipping");
                return;
            }

            final float insertRow = 19.0f;
            final float shiftAmount = 1.0f;
            final float h = 1.0f;

            // Step 1: shift all elements at row >= 19.0 by +1.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= insertRow) {
                        vaRowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            // Step 2: inject QPSK Util. label at row=19.0, col=0, w=27
            Object label = k2aRMethod.invoke(k2aObj, insertRow, h, 0.0f, 27.0f);
            if (label != null) {
                veF.set(label, "QPSK Util.");
                veG.set(label, 0);
                veH.set(label, 1);
            }

            // Step 3: inject PCell bar at row=19.0, col=30, w=34
            Object bar = k2aSMethod.invoke(k2aObj, insertRow, h, 30.0f, 34.0f);
            if (bar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, QPSK_KEY);
                sysAFieldB.set(prop, "%.1f %%");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(bar, prop);
                vfFMethod.invoke(bar, deepBlueColor, 100.0f);
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCaMatrixUlQpskHook: injectQpskRow failed: " + e);
        }
    }
}
