package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds the NR UL BWP bandwidth to the BW column of the BWP table on the
 * NR-SA Dedicated Mode page (h8.h).
 *
 * <p>Renames the column header from "BW" to "PRBs/DL/UL MHz". The existing
 * BW value cell (v6.g at row 11/12/13, col 63) shows "PRBs / DL MHz" via
 * two property bindings. This hook changes the DL binding format from
 * "%d MHz" to "%d" and appends a third binding (NR_PCell_BWP_Bandwidth_UL,
 * format "%d") to each of the three BWP rows, so the cell renders as
 * "PRBs / DL / UL".
 *
 * <p>Hook: AFTER h8.h.l0(Context) / q6.h.k0(Context).
 */
public class BwpUlBwDedicatedModeHook {

    private static final String TAG = "NSGBandHook";

    private static final String KEY_UL_BW =
            "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Bandwidth_UL";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Class<?> sysBClass;
    private Field sysAFieldA;
    private Field sysAFieldB;

    private Object unsafe;
    private Method unsafeAllocateInstance;

    private Field v6bYField;
    private Field k2aListField;
    private Field vaRowField;
    private Field vaColField;

    private Class<?> vgClass;
    private Method vgFMethod;
    private Field vgFListField;

    private Class<?> veClass;
    private Field veTextField;

    private boolean ready = false;

    public BwpUlBwDedicatedModeHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> vaClass  = ClassMapping.loadClass("v6.a", loader);
            vgClass = ClassMapping.loadClass("v6.g", loader);

            sysBClass = ClassMapping.loadClass("com.qtrun.sys.b", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);

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

            v6bYField = v6bClass.getField("Y");

            vaRowField = vaClass.getDeclaredField("b");
            vaColField = vaClass.getDeclaredField("d");
            vaRowField.setAccessible(true);
            vaColField.setAccessible(true);

            vgFMethod = vgClass.getMethod("f", sysBClass, int.class, boolean.class);
            vgFListField = vgClass.getDeclaredField("f");
            vgFListField.setAccessible(true);

            veClass = ClassMapping.loadClass("v6.e", loader);
            veTextField = veClass.getField("f");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "BwpUlBwDedicatedModeHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "BwpUlBwDedicatedModeHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> h8hClass = ClassMapping.loadClass("h8.h", loader);
            if (h8hClass == null) {
                Log.i(TAG, "BwpUlBwDedicatedModeHook: h8.h not available on this flavor, skipping");
                return;
            }
            Method l0Method = ClassMapping.getMethod(h8hClass, "h8.h", "l0", loader, Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        injectUlBw(thiz);
                    }
                    return result;
                }
            });
            Log.i(TAG, "BwpUlBwDedicatedModeHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "BwpUlBwDedicatedModeHook: install failed: " + e);
        }
    }

    private void injectUlBw(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "BwpUlBwDedicatedModeHook: this.Y is null after l0(), skipping");
                return;
            }

            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list == null) {
                Log.w(TAG, "BwpUlBwDedicatedModeHook: element list is null, skipping");
                return;
            }

            for (Object elem : list) {
                try {
                    float row = (float) vaRowField.get(elem);
                    float col = (float) vaColField.get(elem);
                    if (col != 63.0f) {
                        continue;
                    }
                    if (row == 10.0f && veClass.isInstance(elem)) {
                        veTextField.set(elem, "PRBs/DL/UL MHz");
                    } else if (row == 11.0f || row == 12.0f || row == 13.0f) {
                        if (!vgClass.isInstance(elem)) {
                            continue;
                        }
                        java.util.ArrayList<?> bindings =
                                (java.util.ArrayList<?>) vgFListField.get(elem);
                        if (bindings != null) {
                            for (Object b : bindings) {
                                String key = (String) sysAFieldA.get(b);
                                if (key != null && key.contains("Bandwidth_DL")) {
                                    sysAFieldB.set(b, "%d");
                                    break;
                                }
                            }
                        }
                        int bwpIndex = (int) (row - 11.0f);
                        Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                        sysAFieldA.set(prop, KEY_UL_BW);
                        sysAFieldB.set(prop, "%d");
                        vgFMethod.invoke(elem, prop, bwpIndex, false);
                    }
                } catch (Exception ce) {
                    Log.w(TAG, "BwpUlBwDedicatedModeHook: cell inject failed: " + ce);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "BwpUlBwDedicatedModeHook: injectUlBw failed: " + e);
        }
    }
}
