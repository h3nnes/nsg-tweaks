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
 * (h8.c / q6.c), between the "RB" row (original row 10) and the "Tx Mode" row
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
 * Hook: AFTER h8.c.l0(Context) / q6.c.k0(Context)
 */
public class NrSaThputPerPrbHook {

    private static final String TAG = "NSGBandHook";

    private static final String PCELL_KEY =
            "NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_per_PRB_UL";
    private static final String SCELL_KEY =
            "NR5G::Uplink_Measurements::SCell::NR_SCell_Physical_Throughput_per_PRB_UL";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) -> v6.e  (label)
    private Method k2aTMethod; // t(float row, float h, float col, float w) -> v6.g  (text cell)

    // v6.e label fields (actual bytecode names)
    private Field veF; // text   (f)
    private Field veG; // appearance (g)
    private Field veH; // gravity (h)

    // v6.g value cell method
    private Method v6gGMethod; // g(com.qtrun.sys.b, boolean) -> data binding
    private Method v6gJMethod; // j(int index, int color) -> SCell text coloring

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

    public NrSaThputPerPrbHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> veClass  = ClassMapping.loadClass("v6.e", loader);
            Class<?> vgClass  = ClassMapping.loadClass("v6.g", loader);
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> vaClass  = ClassMapping.loadClass("v6.a", loader);

            k2aRMethod = ClassMapping.getMethod(k2aClass, "k2.a", "r", loader,
                    float.class, float.class, float.class, float.class);
            k2aTMethod = ClassMapping.getMethod(k2aClass, "k2.a", "t", loader,
                    float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            sysBClass = ClassMapping.loadClass("com.qtrun.sys.b", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

            v6gGMethod = vgClass.getMethod("g", sysBClass, boolean.class);
            v6gJMethod = vgClass.getMethod("j", int.class, int.class);

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

            k2aListField = k2aClass.getDeclaredField(ClassMapping.runtimeFieldName("k2.a", "d", loader));
            k2aListField.setAccessible(true);
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            v6bYField = v6bClass.getField(ClassMapping.runtimeFieldName("v6.b", "Y", loader));

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
            Class<?> h8cClass = ClassMapping.loadClass("h8.c", loader);
            if (h8cClass == null) {
                Log.i(TAG, "NrSaThputPerPrbHook: h8.c not available on this flavor, skipping");
                return;
            }
            Method l0Method = ClassMapping.getMethod(h8cClass, "h8.c", "l0", loader, Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
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
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "NrSaThputPerPrbHook: this.Y is null after l0(), skipping");
                return;
            }

            // Step 1: shift all elements at row >= 11.0 by +1.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= 11.0f) {
                        vaRowField.set(elem, elemRow + 1.0f);
                    }
                }
            }

            final float row = 11.0f;
            final float h = 1.0f;

            // Step 2: inject "TP/PRB/TTI" label at row=11.0, h=1.0, col=0.0, w=27.0
            Object label = k2aRMethod.invoke(k2aObj, row, h, 0.0f, 27.0f);
            if (label != null) {
                veF.set(label, "TP/PRB/TTI");
                veG.set(label, 0);
                veH.set(label, 1);
            }

            // Step 3: inject PCell text at row=11.0, h=1.0, col=30.0, w=34.0
            Object pcellText = k2aTMethod.invoke(k2aObj, row, h, 30.0f, 34.0f);
            if (pcellText != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, PCELL_KEY);
                sysAFieldB.set(prop, "%.1f Bits");
                sysAFieldC.set(prop, -1);
                v6gGMethod.invoke(pcellText, prop, false);
            }

            // Step 4: inject SCell text at row=11.0, h=1.0, col=65.0, w=34.0
            //         Color-match with holo_purple, same as NSG's own SCell text rows.
            Object scellText = k2aTMethod.invoke(k2aObj, row, h, 65.0f, 34.0f);
            if (scellText != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, SCELL_KEY);
                sysAFieldB.set(prop, "%.1f Bits");
                sysAFieldC.set(prop, 0);
                v6gGMethod.invoke(scellText, prop, false);
                if (v6gJMethod != null && ctx != null) {
                    int color = ctx.getResources().getColor(
                            android.R.color.holo_purple, ctx.getTheme());
                    v6gJMethod.invoke(scellText, 0, color);
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaThputPerPrbHook: injectThputPerPrbRow failed: " + e);
        }
    }
}
