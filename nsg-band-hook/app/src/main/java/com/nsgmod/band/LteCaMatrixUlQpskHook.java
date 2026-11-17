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
 * (v00, "LTECAMatrixUL"), between Band/Width (row 10) and TxPower (row 11).
 *
 * PUCCH TX is inserted at row 11.0, shifting all original rows >= 11.0 by
 * +1.0. QPSK Util. is then inserted at row 20.0 (the original row 19.0
 * shifted by +1.0), shifting rows >= 20.0 by +1.0.
 *
 * PUCCH TX is PCell-only and uses LegendManager auto-coloring (no
 * manual .f(color, max) call).
 *
 * NSG v4.8.14 targets:
 *   v00 extends yg0; h0(Context) builds directly on yg0.X (r31 grid builder).
 *   Hook is an after-hook on v00.h0(Context).
 *   r31: list b, M/N(row, h, col, w) add a label bh0 / bar ch0 cell.
 *   xg0: row b.  bh0: text f, align g, span h.  ch0: binding g, f(int, float).
 *   l8 (attribute): key a, format b, index c; m8 extends l8.
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

    // r31 grid builder
    private Method addLabel; // M(float row, float h, float col, float w) -> bh0 (label)
    private Method addBar;   // N(float row, float h, float col, float w) -> ch0 (bar)
    private Field listField; // r31.b: ArrayList of grid elements

    // bh0 label fields
    private Field labelText;  // f
    private Field labelAlign; // g
    private Field labelSpan;  // h

    // ch0 bar fields + style method
    private Field barBinding;       // g - data binding (m8)
    private Method barFixedColor;   // void f(int color, float max)

    // l8 attribute (m8 subclass is instantiated)
    private Class<?> propClass;     // m8
    private Field attrKey;          // l8.a key String
    private Field attrFormat;       // l8.b format String
    private Field attrIndex;        // l8.c index int

    // Unsafe (attribute constructors need arguments)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // xg0 row field
    private Field rowField;         // b

    // yg0.X field - r31 builder on the fragment
    private Field pageBuilderField;

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
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);  // r31
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);  // xg0
            Class<?> labelClass   = Nsg.load(loader, Nsg.GRID_LABEL);    // bh0
            Class<?> barClass     = Nsg.load(loader, Nsg.GRID_VALUE);    // ch0
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);     // yg0

            addLabel = builderClass.getMethod("M", float.class, float.class, float.class, float.class);
            addBar   = builderClass.getMethod("N", float.class, float.class, float.class, float.class);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelSpan = labelClass.getField("h");

            barBinding = barClass.getDeclaredField("g");
            barBinding.setAccessible(true);
            barFixedColor = barClass.getMethod("f", int.class, float.class);

            propClass = Nsg.load(loader, "m8");
            Class<?> attrClass = Nsg.load(loader, "l8");
            attrKey = attrClass.getDeclaredField("a");
            attrFormat = attrClass.getDeclaredField("b");
            attrIndex = attrClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            listField = builderClass.getDeclaredField("b");
            listField.setAccessible(true);
            rowField = elementClass.getDeclaredField("b");
            rowField.setAccessible(true);

            pageBuilderField = pageClass.getField("X");

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
            Class<?> pageClass = Nsg.load(loader, Nsg.PAGE_LTE_CA_MATRIX_UL); // v00
            Method h0Method = pageClass.getDeclaredMethod("h0", Context.class);

            xposed.hook(h0Method).intercept(new Hooker() {
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

    private void injectTxBar(Object builder, float row, String key) throws Exception {
        Object bar = addBar.invoke(builder, row, 1.0f, 30.0f, 34.0f);
        if (bar != null) {
            Object prop = unsafeAllocateInstance.invoke(unsafe, propClass);
            attrKey.set(prop, key);
            attrFormat.set(prop, "%.1f dBm");
            attrIndex.set(prop, -1);
            barBinding.set(bar, prop);
        }
    }

    private void injectQpskRow(Object thiz) {
        try {
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "LteCaMatrixUlQpskHook: yg0.X is null after h0(), skipping");
                return;
            }

            final float h = 1.0f;

            java.util.ArrayList<?> list = (java.util.ArrayList<?>) listField.get(builder);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) rowField.get(elem);
                    if (elemRow >= 11.0f) {
                        rowField.set(elem, elemRow + 1.0f);
                    }
                }
            }

            Object pucchLabel = addLabel.invoke(builder, 11.0f, h, 0.0f, 27.0f);
            if (pucchLabel != null) {
                labelText.set(pucchLabel, "PUCCH TX");
                labelAlign.set(pucchLabel, 0);
                labelSpan.set(pucchLabel, 1);
            }
            injectTxBar(builder, 11.0f, PUCCH_TX_KEY);

            final float insertRow = 20.0f;
            final float shiftAmount = 1.0f;

            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) rowField.get(elem);
                    if (elemRow >= insertRow) {
                        rowField.set(elem, elemRow + shiftAmount);
                    }
                }
            }

            Object label = addLabel.invoke(builder, insertRow, h, 0.0f, 27.0f);
            if (label != null) {
                labelText.set(label, "QPSK Util.");
                labelAlign.set(label, 0);
                labelSpan.set(label, 1);
            }

            Object bar = addBar.invoke(builder, insertRow, h, 30.0f, 34.0f);
            if (bar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, propClass);
                attrKey.set(prop, QPSK_KEY);
                attrFormat.set(prop, "%.1f %%");
                attrIndex.set(prop, -1);
                barBinding.set(bar, prop);
                barFixedColor.invoke(bar, deepBlueColor, 100.0f);
            }

            Object sCellBar = addBar.invoke(builder, insertRow, h, 65.0f, 34.0f);
            if (sCellBar != null) {
                Object propSc = unsafeAllocateInstance.invoke(unsafe, propClass);
                attrKey.set(propSc, QPSK_SCELL_KEY);
                attrFormat.set(propSc, "%.1f %%");
                attrIndex.set(propSc, -1);
                barBinding.set(sCellBar, propSc);
                barFixedColor.invoke(sCellBar, deepBlueColor, 100.0f);
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCaMatrixUlQpskHook: injectQpskRow failed: " + e);
        }
    }
}
