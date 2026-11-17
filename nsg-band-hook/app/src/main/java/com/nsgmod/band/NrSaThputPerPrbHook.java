package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Inserts a "Thput/PRB" row at position 11.0 on the NR-SA CA Matrix UL page
 * (eb0), between the "RB" row (original row 10) and the "Tx Mode" row
 * (original row 11), shifting the original row 11 and all subsequent rows by +1.0.
 *
 * Row geometry:
 *   label:     row=11.0, h=1.0, col=0.0,  w=27.0, text="TP/PRB/TTI", align=0, span=1
 *   PCell text: row=11.0, h=1.0, col=30.0, w=34.0
 *               key="NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_per_PRB_UL",
 *               index=-1, format="%.1f Bits"
 *   SCell text: row=11.0, h=1.0, col=65.0, w=34.0
 *               key="NR5G::Uplink_Measurements::SCell::NR_SCell_Physical_Throughput_per_PRB_UL",
 *               index=0, format="%.1f Bits", color=holo_purple (via j(0, color))
 *
 * Text-only display, no bar coloring (no .f(color, max) call).
 *
 * Hook order: This hook MUST be installed AFTER NrSaPucchTxRowHook in MainHook
 * so it runs INSIDE PucchTx's chain.proceed(). This hook sees the ORIGINAL layout
 * (RB at row 10, Tx Mode at row 11), shifts rows >= 11.0 by +1.0, and inserts at
 * row 11.0. Then PucchTx shifts rows >= 8.0 by +3.0, moving everything correctly.
 *
 * Hook: AFTER eb0.h0(Context)
 */
public class NrSaThputPerPrbHook {

    private static final String TAG = "NSGBandHook";

    private static final String ATTR_BASE = "l8";   // attribute base: key a, format b, index c
    private static final String ATTR_MAIN = "m8";   // attribute subclass (instantiated via Unsafe)

    private static final String PCELL_KEY =
            "NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_per_PRB_UL";
    private static final String SCELL_KEY =
            "NR5G::Uplink_Measurements::SCell::NR_SCell_Physical_Throughput_per_PRB_UL";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // r31 grid builder methods
    private Method builderLabelMethod; // M(float row, float h, float col, float w) -> bh0 (label)
    private Method builderTextMethod;  // O(float row, float h, float col, float w) -> dh0 (text cell)

    // bh0 label fields
    private Field labelText;  // f
    private Field labelAlign; // g
    private Field labelSpan;  // h

    // dh0 value cell methods
    private Method textBindMethod;  // g(m8, boolean) -> data binding
    private Method textColorMethod; // j(int index, int color) -> SCell text coloring

    // l8 / m8 — property binding
    private Class<?> attrClass;
    private Field attrKey;    // final String a
    private Field attrFormat; // final String b
    private Field attrIndex;  // int c

    // Unsafe for allocateInstance (m8 ctor is not usable here)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // r31 element list + xg0 row field
    private Field builderListField;
    private Field elementRowField;

    // yg0.X field — r31 builder on the fragment
    private Field pageBuilderField;

    private boolean ready = false;

    public NrSaThputPerPrbHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> labelClass   = Nsg.load(loader, Nsg.GRID_LABEL);
            Class<?> textClass    = Nsg.load(loader, Nsg.GRID_MULTI);
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);
            attrClass = Nsg.load(loader, ATTR_MAIN);
            Class<?> attrBaseClass = Nsg.load(loader, ATTR_BASE);

            builderLabelMethod = builderClass.getMethod("M",
                    float.class, float.class, float.class, float.class);
            builderTextMethod = builderClass.getMethod("O",
                    float.class, float.class, float.class, float.class);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelSpan = labelClass.getField("h");

            attrKey = attrBaseClass.getDeclaredField("a");
            attrFormat = attrBaseClass.getDeclaredField("b");
            attrIndex = attrBaseClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

            textBindMethod = textClass.getMethod("g", attrClass, boolean.class);
            textColorMethod = textClass.getMethod("j", int.class, int.class);

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE"); // Android/Dalvik
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe"); // OpenJDK fallback
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            builderListField = builderClass.getDeclaredField("b"); // ArrayList<xg0> (typed Object)
            builderListField.setAccessible(true);
            elementRowField = elementClass.getDeclaredField("b");
            elementRowField.setAccessible(true);

            pageBuilderField = pageClass.getField("X");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaThputPerPrbHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaThputPerPrbHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> pageClass = Nsg.load(loader, Nsg.PAGE_SA_CA_MATRIX_UL);
            Method buildMethod = pageClass.getMethod("h0", Context.class);

            xposed.hook(buildMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    Context ctx = (Context) chain.getArg(0);
                    if (thiz != null) {
                        injectThputPerPrbRow(thiz, ctx);
                    }
                    return result;
                }
            });

            Log.i(TAG, "NrSaThputPerPrbHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaThputPerPrbHook: install failed: " + e);
        }
    }

    private void injectThputPerPrbRow(Object thiz, Context ctx) {
        try {
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "NrSaThputPerPrbHook: this.X is null after h0(), skipping");
                return;
            }

            // Step 1: shift all elements at row >= 11.0 by +1.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) builderListField.get(builder);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) elementRowField.get(elem);
                    if (elemRow >= 11.0f) {
                        elementRowField.set(elem, elemRow + 1.0f);
                    }
                }
            }

            final float row = 11.0f;
            final float h = 1.0f;

            // Step 2: inject "TP/PRB/TTI" label at row=11.0, h=1.0, col=0.0, w=27.0
            Object label = builderLabelMethod.invoke(builder, row, h, 0.0f, 27.0f);
            if (label != null) {
                labelText.set(label, "TP/PRB/TTI");
                labelAlign.set(label, 0);
                labelSpan.set(label, 1);
            }

            // Step 3: inject PCell text at row=11.0, h=1.0, col=30.0, w=34.0
            Object pcellText = builderTextMethod.invoke(builder, row, h, 30.0f, 34.0f);
            if (pcellText != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, PCELL_KEY);
                attrFormat.set(prop, "%.1f Bits");
                attrIndex.set(prop, -1);
                textBindMethod.invoke(pcellText, prop, false);
            }

            // Step 4: inject SCell text at row=11.0, h=1.0, col=65.0, w=34.0
            //         Color-match with holo_purple, same as NSG's own SCell text rows.
            Object scellText = builderTextMethod.invoke(builder, row, h, 65.0f, 34.0f);
            if (scellText != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, SCELL_KEY);
                attrFormat.set(prop, "%.1f Bits");
                attrIndex.set(prop, 0);
                textBindMethod.invoke(scellText, prop, false);
                if (ctx != null) {
                    int color = ctx.getResources().getColor(
                            android.R.color.holo_purple, ctx.getTheme());
                    textColorMethod.invoke(scellText, 0, color);
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaThputPerPrbHook: injectThputPerPrbRow failed: " + e);
        }
    }
}
