package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "BWP-ID" column to the NR-SA status panel (d8.i),
 * resizing the existing 4 columns and adding a 5th column
 * showing the active DL BWP ID.
 *
 * Hook: AFTER d8.i.l0(Context)
 */
public class NrSaBwpIdHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) → v6.e (label)
    private Method k2aTMethod; // t(float row, float h, float col, float w) → v6.g (value cell)

    // v6.e label fields
    private Field veF; // text   (f)
    private Field veG; // appearance (g)
    private Field veH; // gravity (h)

    // v6.a element fields
    private Field vaRowField;   // b (row float)
    private Field vaColField;   // d (col float)
    private Field vaWidthField; // e (width float)

    // v6.g value cell fields and method
    private Method vgGMethod; // g(com.qtrun.sys.b, boolean)
    private Field vgJ;        // appearance (j)
    private Field vgK;        // gravity (k)

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field sysAFieldA; // final String key
    private Field sysAFieldB; // final String format
    private Field sysAFieldC; // int index

    // Unsafe for allocateInstance (com.qtrun.sys.b ctor stripped by ProGuard)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // k2.a list + v6.b.Y field
    private Field k2aListField;
    private Field v6bYField;

    private boolean ready = false;

    public NrSaBwpIdHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = loader.loadClass("k2.a");
            Class<?> veClass  = loader.loadClass("v6.e");
            Class<?> vgClass  = loader.loadClass("v6.g");
            Class<?> v6bClass = loader.loadClass("v6.b");
            Class<?> vaClass  = loader.loadClass("v6.a");

            k2aRMethod = k2aClass.getMethod("r", float.class, float.class, float.class, float.class);
            k2aTMethod = k2aClass.getMethod("t", float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vaRowField   = vaClass.getDeclaredField("b");
            vaColField   = vaClass.getDeclaredField("d");
            vaWidthField = vaClass.getDeclaredField("e");
            vaRowField.setAccessible(true);
            vaColField.setAccessible(true);
            vaWidthField.setAccessible(true);

            sysBClass = loader.loadClass("com.qtrun.sys.b");
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

            vgGMethod = vgClass.getMethod("g", sysBClass, boolean.class);
            vgJ = vgClass.getDeclaredField("j");
            vgK = vgClass.getDeclaredField("k");
            vgJ.setAccessible(true);
            vgK.setAccessible(true);

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

            v6bYField = v6bClass.getField("Y");

            ready = true;
            Log.i(TAG, "NrSaBwpIdHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaBwpIdHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaBwpIdHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> d8iClass = loader.loadClass("d8.i");
            Method l0Method = d8iClass.getMethod("l0", Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        injectBwpIdColumn(thiz);
                    }
                    return result;
                }
            });
            Log.i(TAG, "NrSaBwpIdHook: d8.i.l0 hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaBwpIdHook: install failed: " + e);
        }
    }

    private void injectBwpIdColumn(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "NrSaBwpIdHook: this.Y is null after l0(), skipping");
                return;
            }

            // Step 1: resize existing columns in row 0.0f and row 1.0f
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float row = (float) vaRowField.get(elem);
                    if (row == 0.0f || row == 1.0f) {
                        float col = (float) vaColField.get(elem);
                        if (col < 1.0f) {
                            // First column (was col 0, width 25)
                            vaColField.set(elem, 0.0f);
                            vaWidthField.set(elem, 20.0f);
                        } else if (col >= 25.0f && col < 27.0f) {
                            // Second column (was col 26, width 25)
                            vaColField.set(elem, 20.0f);
                            vaWidthField.set(elem, 20.0f);
                        } else if (col >= 51.0f && col < 53.0f) {
                            // Third column (was col 52, width 25)
                            vaColField.set(elem, 40.0f);
                            vaWidthField.set(elem, 20.0f);
                        } else if (col >= 77.0f && col < 79.0f) {
                            // Fourth column (was col 78, width 21)
                            vaColField.set(elem, 60.0f);
                            vaWidthField.set(elem, 20.0f);
                        }
                    }
                }
            }

            final float h = 1.0f;

            // Step 2: create new label at row=0.0f, col=80.0f, width=20.0f
            Object label = k2aRMethod.invoke(k2aObj, 0.0f, h, 80.0f, 20.0f);
            if (label != null) {
                veF.set(label, "BWP-ID");
                veG.set(label, 0);  // appearance
                veH.set(label, 2);  // gravity
            }

            // Step 3: create new value cell at row=1.0f, col=80.0f, width=20.0f
            Object valueCell = k2aTMethod.invoke(k2aObj, 1.0f, h, 80.0f, 20.0f);
            if (valueCell != null) {
                vgJ.set(valueCell, 0);  // appearance
                vgK.set(valueCell, 2);  // gravity

                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_ID_DL");
                sysAFieldB.set(prop, null); // no format
                sysAFieldC.set(prop, 0);    // index
                vgGMethod.invoke(valueCell, prop, true);
            }

            Log.i(TAG, "NrSaBwpIdHook: BWP-ID column injected");
        } catch (Exception e) {
            Log.w(TAG, "NrSaBwpIdHook: injectBwpIdColumn failed: " + e);
        }
    }
}
