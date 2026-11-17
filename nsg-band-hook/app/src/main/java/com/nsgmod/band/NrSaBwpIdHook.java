package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "BWP-ID" column to the NR-SA status panel (lb0),
 * resizing the existing 4 columns and adding a 5th column
 * showing the active DL BWP ID.
 *
 * Hook: AFTER lb0.h0(Context)
 */
public class NrSaBwpIdHook {

    private static final String TAG = "NSGBandHook";

    private static final String STATUS_PAGE = "lb0"; // NR-SA status panel page (yg0 subclass)
    private static final String ATTR_BASE = "l8";   // attribute base: key a, format b, index c
    private static final String ATTR_MAIN = "m8";   // attribute subclass (instantiated via Unsafe)

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // r31 grid builder methods
    private Method builderLabelMethod; // M(float row, float h, float col, float w) → bh0 (label)
    private Method builderValueMethod; // O(float row, float h, float col, float w) → dh0 (value cell)

    // bh0 label fields
    private Field labelText;  // f
    private Field labelAlign; // g
    private Field labelSpan;  // h

    // xg0 element fields
    private Field elementRowField;   // b (row float)
    private Field elementColField;   // d (col float)
    private Field elementWidthField; // e (width float)

    // dh0 value cell fields and method
    private Method valueBindMethod; // g(m8, boolean)
    private Field valueAppearance;  // j
    private Field valueGravity;     // k

    // l8 / m8 — property binding
    private Class<?> attrClass;
    private Field attrKey;    // final String a
    private Field attrFormat; // final String b
    private Field attrIndex;  // int c

    // Unsafe for allocateInstance (m8 ctor is not usable here)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // r31 element list + yg0.X field
    private Field builderListField;
    private Field pageBuilderField;

    private boolean ready = false;

    public NrSaBwpIdHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> labelClass   = Nsg.load(loader, Nsg.GRID_LABEL);
            Class<?> valueClass   = Nsg.load(loader, Nsg.GRID_MULTI);
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);

            builderLabelMethod = builderClass.getMethod("M",
                    float.class, float.class, float.class, float.class);
            builderValueMethod = builderClass.getMethod("O",
                    float.class, float.class, float.class, float.class);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelSpan = labelClass.getField("h");

            elementRowField   = elementClass.getDeclaredField("b");
            elementColField   = elementClass.getDeclaredField("d");
            elementWidthField = elementClass.getDeclaredField("e");
            elementRowField.setAccessible(true);
            elementColField.setAccessible(true);
            elementWidthField.setAccessible(true);

            attrClass = Nsg.load(loader, ATTR_MAIN);
            Class<?> attrBaseClass = Nsg.load(loader, ATTR_BASE);
            attrKey = attrBaseClass.getDeclaredField("a");
            attrFormat = attrBaseClass.getDeclaredField("b");
            attrIndex = attrBaseClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

            valueBindMethod = valueClass.getMethod("g", attrClass, boolean.class);
            valueAppearance = valueClass.getDeclaredField("j");
            valueGravity = valueClass.getDeclaredField("k");
            valueAppearance.setAccessible(true);
            valueGravity.setAccessible(true);

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
            pageBuilderField = pageClass.getField("X");

            ready = true;
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
            Class<?> pageClass = Nsg.load(loader, STATUS_PAGE);
            Method buildMethod = pageClass.getMethod("h0", Context.class);

            xposed.hook(buildMethod).intercept(new Hooker() {
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
            Log.i(TAG, "NrSaBwpIdHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaBwpIdHook: install failed: " + e);
        }
    }

    private void injectBwpIdColumn(Object thiz) {
        try {
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "NrSaBwpIdHook: this.X is null after h0(), skipping");
                return;
            }

            // Step 1: resize existing columns in row 0.0f and row 1.0f
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) builderListField.get(builder);
            if (list != null) {
                for (Object elem : list) {
                    float row = (float) elementRowField.get(elem);
                    if (row == 0.0f || row == 1.0f) {
                        float col = (float) elementColField.get(elem);
                        if (col < 1.0f) {
                            // First column (was col 0, width 25)
                            elementColField.set(elem, 0.0f);
                            elementWidthField.set(elem, 20.0f);
                        } else if (col >= 25.0f && col < 27.0f) {
                            // Second column (was col 26, width 25)
                            elementColField.set(elem, 20.0f);
                            elementWidthField.set(elem, 20.0f);
                        } else if (col >= 51.0f && col < 53.0f) {
                            // Third column (was col 52, width 25)
                            elementColField.set(elem, 40.0f);
                            elementWidthField.set(elem, 20.0f);
                        } else if (col >= 77.0f && col < 79.0f) {
                            // Fourth column (was col 78, width 21)
                            elementColField.set(elem, 60.0f);
                            elementWidthField.set(elem, 20.0f);
                        }
                    }
                }
            }

            final float h = 1.0f;

            // Step 2: create new label at row=0.0f, col=80.0f, width=20.0f
            Object label = builderLabelMethod.invoke(builder, 0.0f, h, 80.0f, 20.0f);
            if (label != null) {
                labelText.set(label, "BWP-ID");
                labelAlign.set(label, 0);  // appearance
                labelSpan.set(label, 2);  // gravity
            }

            // Step 3: create new value cell at row=1.0f, col=80.0f, width=20.0f
            Object valueCell = builderValueMethod.invoke(builder, 1.0f, h, 80.0f, 20.0f);
            if (valueCell != null) {
                valueAppearance.set(valueCell, 0);  // appearance
                valueGravity.set(valueCell, 2);  // gravity

                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_ID_DL");
                attrFormat.set(prop, null); // no format
                attrIndex.set(prop, 0);    // index
                valueBindMethod.invoke(valueCell, prop, true);
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaBwpIdHook: injectBwpIdColumn failed: " + e);
        }
    }
}
