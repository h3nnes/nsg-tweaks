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
 * Adds "PUCCH TX" and "QPSK Util." rows on the LTE CA Matrix UL page
 * (e8.a), between Band/Width (row 10) and TxPower (row 11).
 *
 * PUCCH TX is inserted at row 11.0, shifting all original rows >= 11.0 by
 * +1.0. QPSK Util. is then inserted at row 20.0 (the original row 19.0
 * shifted by +1.0), shifting rows >= 20.0 by +1.0.
 *
 * PUCCH TX is PCell-only and uses LegendManager auto-coloring (no
 * manual .f(color, max) call).
 *
 * Architecture:
 *   e8.a extends v6.b; l0(Context) builds directly on this.Y (k2.a).
 *   Hook is an after-hook on e8.a.l0(Context).
 */
public class LteCaMatrixUlQpskHook {

    private static final String TAG = "NSGBandHook";
    private static final String QPSK_KEY =
            "LTE::Uplink_Measurements::LTE_ModUsage_QPSK_UL";
    private static final String QPSK_SCELL_KEY =
            "LTE::Uplink_Measurements::SCC::LTE_ModUsage_QPSK_SCell1_UL";
    private static final String PUCCH_TX_KEY =
            "LTE::Uplink_Measurements::LTE_Power_Tx_PUCCH";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) -> v6.e (label)
    private Method k2aSMethod; // s(float row, float h, float col, float w) -> v6.f (bar)

    // v6.e label fields
    private Field veF; // text
    private Field veG; // align
    private Field veH; // span

    // v6.f bar fields + style method
    private Field vfF8120g; // g - data binding
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

    // v6.b.Y field - k2.a builder on the fragment
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
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> veClass  = ClassMapping.loadClass("v6.e", loader);
            Class<?> vfClass  = ClassMapping.loadClass("v6.f", loader);
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);

            k2aRMethod = ClassMapping.getMethod(k2aClass, "k2.a", "r", loader,
                    float.class, float.class, float.class, float.class);
            k2aSMethod = ClassMapping.getMethod(k2aClass, "k2.a", "s", loader,
                    float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);
            vfFMethod = vfClass.getMethod("f", int.class, float.class);

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
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = ClassMapping.loadClass("v6.a", loader);
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
            Class<?> e8aClass = ClassMapping.loadClass("e8.a", loader);
            if (e8aClass == null) {
                Log.i(TAG, "LteCaMatrixUlQpskHook: e8.a not available, skipping");
                return;
            }
            Class<?> contextClass = Class.forName("android.content.Context");
            Method l0Method = ClassMapping.getMethod(e8aClass, "e8.a", "l0", loader, contextClass);

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

    private void injectTxBar(Object k2aObj, float row, String key) throws Exception {
        Object bar = k2aSMethod.invoke(k2aObj, row, 1.0f, 30.0f, 34.0f);
        if (bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            sysAFieldA.set(prop, key);
            sysAFieldB.set(prop, "%.1f dBm");
            sysAFieldC.set(prop, -1);
            vfF8120g.set(bar, prop);
        }
    }

    private void injectQpskRow(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "LteCaMatrixUlQpskHook: this.Y is null after l0(), skipping");
                return;
            }

            final float h = 1.0f;

            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= 11.0f) {
                        vaRowField.set(elem, elemRow + 1.0f);
                    }
                }
            }

            Object pucchLabel = k2aRMethod.invoke(k2aObj, 11.0f, h, 0.0f, 27.0f);
            if (pucchLabel != null) {
                veF.set(pucchLabel, "PUCCH TX");
                veG.set(pucchLabel, 0);
                veH.set(pucchLabel, 1);
            }
            injectTxBar(k2aObj, 11.0f, PUCCH_TX_KEY);

            final float insertRow = 20.0f;
            final float shiftAmount = 1.0f;

            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= insertRow) {
                        vaRowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            Object label = k2aRMethod.invoke(k2aObj, insertRow, h, 0.0f, 27.0f);
            if (label != null) {
                veF.set(label, "QPSK Util.");
                veG.set(label, 0);
                veH.set(label, 1);
            }

            Object bar = k2aSMethod.invoke(k2aObj, insertRow, h, 30.0f, 34.0f);
            if (bar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, QPSK_KEY);
                sysAFieldB.set(prop, "%.1f %%");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(bar, prop);
                vfFMethod.invoke(bar, deepBlueColor, 100.0f);
            }

            Object sCellBar = k2aSMethod.invoke(k2aObj, insertRow, h, 65.0f, 34.0f);
            if (sCellBar != null) {
                Object propSc = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(propSc, QPSK_SCELL_KEY);
                sysAFieldB.set(propSc, "%.1f %%");
                sysAFieldC.set(propSc, -1);
                vfF8120g.set(sCellBar, propSc);
                vfFMethod.invoke(sCellBar, deepBlueColor, 100.0f);
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCaMatrixUlQpskHook: injectQpskRow failed: " + e);
        }
    }
}
