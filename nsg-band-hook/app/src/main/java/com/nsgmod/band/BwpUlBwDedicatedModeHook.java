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
 * NR-SA Dedicated Mode page (kb0).
 *
 * <p>Renames the column header from "BW" to "PRBs/DL/UL MHz". The existing
 * BW value cell (dh0 at row 11/12/13, col 63) shows "PRBs / DL MHz" via
 * two property bindings. This hook changes the DL binding format from
 * "%d MHz" to "%d" and appends a third binding (NR_PCell_BWP_Bandwidth_UL,
 * format "%d") to each of the three BWP rows, so the cell renders as
 * "PRBs / DL / UL".
 *
 * <p>Hook: AFTER kb0.h0(Context).
 */
public class BwpUlBwDedicatedModeHook {

    private static final String TAG = "NSGBandHook";

    private static final String ATTR_BASE = "l8";   // attribute base: key a, format b
    private static final String ATTR_MAIN = "m8";   // attribute subclass (instantiated via Unsafe)

    private static final String KEY_UL_BW =
            "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Bandwidth_UL";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Class<?> attrClass;
    private Field attrKey;    // final String a
    private Field attrFormat; // final String b

    private Object unsafe;
    private Method unsafeAllocateInstance;

    private Field pageBuilderField;   // yg0.X (r31)
    private Field builderListField;   // r31.b (ArrayList<xg0>, typed Object)
    private Field elementRowField;    // xg0.b
    private Field elementColField;    // xg0.d

    private Class<?> valueClass;      // dh0 (value cell with attribute list)
    private Method valueAddMethod;    // f(m8, int index, boolean clear)
    private Field valueListField;     // f (ArrayList<m8>)

    private Class<?> labelClass;      // bh0
    private Field labelTextField;     // f

    private boolean ready = false;

    public BwpUlBwDedicatedModeHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);
            valueClass = Nsg.load(loader, Nsg.GRID_MULTI);
            labelClass = Nsg.load(loader, Nsg.GRID_LABEL);

            attrClass = Nsg.load(loader, ATTR_MAIN);
            Class<?> attrBaseClass = Nsg.load(loader, ATTR_BASE);
            attrKey = attrBaseClass.getDeclaredField("a");
            attrFormat = attrBaseClass.getDeclaredField("b");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);

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

            builderListField = builderClass.getDeclaredField("b");
            builderListField.setAccessible(true);
            pageBuilderField = pageClass.getField("X");

            elementRowField = elementClass.getDeclaredField("b");
            elementColField = elementClass.getDeclaredField("d");
            elementRowField.setAccessible(true);
            elementColField.setAccessible(true);

            valueAddMethod = valueClass.getMethod("f", attrClass, int.class, boolean.class);
            valueListField = valueClass.getDeclaredField("f");
            valueListField.setAccessible(true);

            labelTextField = labelClass.getField("f");

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
            Class<?> pageClass = Nsg.load(loader, Nsg.PAGE_SA_DEDICATED_MODE);
            Method buildMethod = pageClass.getMethod("h0", Context.class);

            xposed.hook(buildMethod).intercept(new Hooker() {
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
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "BwpUlBwDedicatedModeHook: this.X is null after h0(), skipping");
                return;
            }

            java.util.ArrayList<?> list = (java.util.ArrayList<?>) builderListField.get(builder);
            if (list == null) {
                Log.w(TAG, "BwpUlBwDedicatedModeHook: element list is null, skipping");
                return;
            }

            for (Object elem : list) {
                try {
                    float row = (float) elementRowField.get(elem);
                    float col = (float) elementColField.get(elem);
                    if (col != 63.0f) {
                        continue;
                    }
                    if (row == 10.0f && labelClass.isInstance(elem)) {
                        labelTextField.set(elem, "PRBs/DL/UL MHz");
                    } else if (row == 11.0f || row == 12.0f || row == 13.0f) {
                        if (!valueClass.isInstance(elem)) {
                            continue;
                        }
                        java.util.ArrayList<?> bindings =
                                (java.util.ArrayList<?>) valueListField.get(elem);
                        if (bindings != null) {
                            for (Object b : bindings) {
                                String key = (String) attrKey.get(b);
                                if (key != null && key.contains("Bandwidth_DL")) {
                                    attrFormat.set(b, "%d");
                                    break;
                                }
                            }
                        }
                        int bwpIndex = (int) (row - 11.0f);
                        Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                        attrKey.set(prop, KEY_UL_BW);
                        attrFormat.set(prop, "%d");
                        valueAddMethod.invoke(elem, prop, bwpIndex, false);
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
