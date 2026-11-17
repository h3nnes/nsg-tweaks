package com.nsgmod.band;

import com.nsgmod.band.SettingsToggleHook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "Pathloss" column to the top RF header bar (HeaderRFFragment)
 * between PCI and SS-RSRP, showing NR5G::Downlink_Measurements::NR_Pathloss_DL.
 * Only visible in NR-SA mode.
 *
 * Hook: AFTER HeaderRFFragment.E(LayoutInflater, ViewGroup, Bundle)
 * Hook: AFTER HeaderRFFragment.b(DataSource, long, short, Object)
 */
public class NrSaHeaderPathlossHook {

    private static final String TAG = "NSGBandHook";

    private static final String HEADER_RF_FRAGMENT = "com.qtrun.udv.header.HeaderRFFragment";
    private static final String ATTR_BASE = "l8";   // attribute base: key a, format b, index c
    private static final String ATTR_MAIN = "m8";   // attribute subclass (instantiated via Unsafe)

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Cache fragment -> root view so updateVisibility doesn't need Fragment.getView()
    private final java.util.WeakHashMap<Object, View> fragmentRootViews = new java.util.WeakHashMap<>();

    // Existing four header column elements and the injected pathloss elements, stored
    // per fragment so we can toggle between standard (4-column) and NR-SA (5-column)
    // geometry in updateVisibility().
    private final java.util.WeakHashMap<Object, HeaderGeometry> fragmentGeometry = new java.util.WeakHashMap<>();

    // HeaderRFFragment
    private Class<?> headerRFFragmentClass;
    private Field headerRFFragmentBuilderField; // Z (r31, final)

    // r31 grid builder methods
    private Method builderLabelMethod; // M(float row, float h, float col, float w) -> bh0 (label)
    private Method builderValueMethod; // O(float row, float h, float col, float w) -> dh0 (value)
    private Field builderListField;    // b -> ArrayList<xg0> (typed Object)

    // bh0 label fields
    private Field labelText;  // f
    private Field labelAlign; // g
    private Field labelGravity; // h

    // dh0 value cell methods
    private Method valueBindMethod;  // g(m8, boolean)
    private Method valueStyleMethod; // j(int appearance, int color)

    // xg0 element fields / methods
    private Field elementViewField;  // a -> View
    private Field elementRowField;   // b (row float)
    private Field elementColField;   // d (col float)
    private Field elementWidthField; // e (width float)
    private Method elementCreateViewMethod; // c(ViewGroup) -> View

    // ah0 layout internal list
    private Field layoutListField; // a -> ArrayList<xg0>

    // l8 / m8 — property binding
    private Class<?> attrClass;
    private Field attrKey;    // final String a
    private Field attrFormat; // final String b
    private Field attrIndex;  // int c

    // Workspace reflection for NR-SA detection
    private Field wsSingletonField; // j
    private Field wsRatField;       // d -> com.qtrun.sys.d (toString "NR-SA" / "NR-NSA" / ...)

    // Unsafe for allocateInstance (m8 ctor is not usable here)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    private boolean ready = false;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    /** Holds the four existing header column elements and the injected pathloss pair,
     *  plus their standard and NR-SA geometries, for one fragment instance. */
    private static final class HeaderGeometry {
        final Object ssbArfcnLabel;
        final Object pciLabel;
        final Object ssRsrpLabel;
        final Object ssSinrLabel;
        final Object ssbArfcnValue;
        final Object pciValue;
        final Object ssRsrpValue;
        final Object ssSinrValue;
        final Object pathlossLabel;
        final Object pathlossValue;

        HeaderGeometry(Object ssbArfcnLabel, Object pciLabel, Object ssRsrpLabel, Object ssSinrLabel,
                       Object ssbArfcnValue, Object pciValue, Object ssRsrpValue, Object ssSinrValue,
                       Object pathlossLabel, Object pathlossValue) {
            this.ssbArfcnLabel = ssbArfcnLabel;
            this.pciLabel = pciLabel;
            this.ssRsrpLabel = ssRsrpLabel;
            this.ssSinrLabel = ssSinrLabel;
            this.ssbArfcnValue = ssbArfcnValue;
            this.pciValue = pciValue;
            this.ssRsrpValue = ssRsrpValue;
            this.ssSinrValue = ssSinrValue;
            this.pathlossLabel = pathlossLabel;
            this.pathlossValue = pathlossValue;
        }
    }

    public NrSaHeaderPathlossHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            headerRFFragmentClass = Nsg.load(loader, HEADER_RF_FRAGMENT);

            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> labelClass = Nsg.load(loader, Nsg.GRID_LABEL);
            Class<?> valueClass = Nsg.load(loader, Nsg.GRID_MULTI);
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);
            Class<?> layoutClass = Nsg.load(loader, Nsg.GRID_D);

            headerRFFragmentBuilderField = headerRFFragmentClass.getDeclaredField("Z");
            headerRFFragmentBuilderField.setAccessible(true);

            builderLabelMethod = builderClass.getMethod("M",
                    float.class, float.class, float.class, float.class);
            builderValueMethod = builderClass.getMethod("O",
                    float.class, float.class, float.class, float.class);
            builderListField = builderClass.getDeclaredField("b");
            builderListField.setAccessible(true);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelGravity = labelClass.getField("h");

            elementViewField = elementClass.getDeclaredField("a");
            elementRowField = elementClass.getDeclaredField("b");
            elementColField = elementClass.getDeclaredField("d");
            elementWidthField = elementClass.getDeclaredField("e");
            elementViewField.setAccessible(true);
            elementRowField.setAccessible(true);
            elementColField.setAccessible(true);
            elementWidthField.setAccessible(true);
            elementCreateViewMethod = elementClass.getMethod("c", ViewGroup.class);

            attrClass = Nsg.load(loader, ATTR_MAIN);
            Class<?> attrBaseClass = Nsg.load(loader, ATTR_BASE);
            attrKey = attrBaseClass.getDeclaredField("a");
            attrFormat = attrBaseClass.getDeclaredField("b");
            attrIndex = attrBaseClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

            valueBindMethod = valueClass.getMethod("g", attrClass, boolean.class);
            valueStyleMethod = valueClass.getMethod("j", int.class, int.class);

            layoutListField = layoutClass.getDeclaredField("a");
            layoutListField.setAccessible(true);

            Class<?> workspaceClass = Nsg.load(loader, Nsg.WORKSPACE);
            wsSingletonField = workspaceClass.getField("j");
            wsRatField = workspaceClass.getDeclaredField("d");
            wsRatField.setAccessible(true);

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

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaHeaderPathlossHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaHeaderPathlossHook: skipping install — reflection not ready");
            return;
        }
        try {
            Method createViewMethod = headerRFFragmentClass.getMethod("E",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(createViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null && result instanceof android.view.View) {
                        injectPathlossColumn(thiz, (android.view.View) result);
                    }
                    return result;
                }
            });

            Class<?> dataSourceClass = Nsg.load(loader, Nsg.DATA_SOURCE);
            Method bMethod = headerRFFragmentClass.getMethod("b",
                    dataSourceClass, long.class, short.class, Object.class);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        updateVisibility(thiz);
                    }
                    return result;
                }
            });

            Log.i(TAG, "NrSaHeaderPathlossHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaHeaderPathlossHook: install failed: " + e);
        }
        registerPrefListener();
    }

    /** Register a SharedPreferences listener so ON/OFF toggle changes take effect immediately. */
    private void registerPrefListener() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Application app =
                    (Application) atCls.getMethod("currentApplication").invoke(null);
            if (app == null) return;

            prefs = app.getSharedPreferences(
                    "com.qtrun.QuickTest_preferences", Context.MODE_PRIVATE);

            prefListener = (sharedPreferences, key) -> {
                if (!SettingsToggleHook.PREF_KEY_PATHLOSS_COLUMN.equals(key)) return;
                for (Object fragment : new java.util.ArrayList<>(fragmentRootViews.keySet())) {
                    updateVisibility(fragment);
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable t) {
            Log.w(TAG, "NrSaHeaderPathlossHook: failed to register preference listener: " + t);
        }
    }

    private void injectPathlossColumn(Object thiz, View rootView) {
        fragmentRootViews.put(thiz, rootView);
        if (!SettingsToggleHook.pathlossColumnEnabled()) {
            Log.i(TAG, "NrSaHeaderPathlossHook: disabled by settings, skipping injection");
            return;
        }
        if (rootView.getTag(R.id.nsg_header_pathloss_injected) != null) {
            return; // already injected for this fragment instance
        }

        try {
            Object builder = headerRFFragmentBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "NrSaHeaderPathlossHook: r31 builder is null, skipping");
                return;
            }

            @SuppressWarnings("unchecked")
            ArrayList<Object> list = (ArrayList<Object>) builderListField.get(builder);
            if (list == null) {
                Log.w(TAG, "NrSaHeaderPathlossHook: r31 list is null, skipping");
                return;
            }

            // Step 1: identify the four existing header columns (label + value for each).
            Object ssbArfcnLabel = null, ssbArfcnValue = null;
            Object pciLabel = null, pciValue = null;
            Object ssRsrpLabel = null, ssRsrpValue = null;
            Object ssSinrLabel = null, ssSinrValue = null;

            for (Object elem : list) {
                float row = (float) elementRowField.get(elem);
                if (row != 0.0f && row != 1.0f) {
                    continue;
                }
                float col = (float) elementColField.get(elem);
                float width = (float) elementWidthField.get(elem);

                // SSB-ARFCN: col 0, width 25
                if (col < 1.0f && Math.abs(width - 25.0f) < 1.0f) {
                    if (row == 0.0f) ssbArfcnLabel = elem; else ssbArfcnValue = elem;
                }
                // PCI: col 25, width 15
                else if (col >= 24.0f && col < 26.0f && Math.abs(width - 15.0f) < 1.0f) {
                    if (row == 0.0f) pciLabel = elem; else pciValue = elem;
                }
                // SS-RSRP: col 41, width 30
                else if (col >= 40.0f && col < 42.0f && Math.abs(width - 30.0f) < 1.0f) {
                    if (row == 0.0f) ssRsrpLabel = elem; else ssRsrpValue = elem;
                }
                // SS-SINR: col 72, width 28
                else if (col >= 71.0f && col < 73.0f && Math.abs(width - 28.0f) < 1.0f) {
                    if (row == 0.0f) ssSinrLabel = elem; else ssSinrValue = elem;
                }
            }

            if (ssbArfcnLabel == null || pciLabel == null || ssRsrpLabel == null || ssSinrLabel == null) {
                Log.w(TAG, "NrSaHeaderPathlossHook: could not identify all existing header columns");
                return;
            }

            final float h = 1.0f;

            // Step 2: create Pathloss label at row=0, col=34, width=16.
            Object pathlossLabel = builderLabelMethod.invoke(builder, 0.0f, h, 34.0f, 16.0f);
            if (pathlossLabel != null) {
                labelText.set(pathlossLabel, "Pathloss");
                labelAlign.set(pathlossLabel, 0); // appearance
                labelGravity.set(pathlossLabel, 2); // gravity
            }

            // Step 3: create Pathloss value cell at row=1, col=34, width=16.
            Object pathlossValue = builderValueMethod.invoke(builder, 1.0f, h, 34.0f, 16.0f);
            if (pathlossValue != null) {
                // Match the surrounding value cells: appearance 0, color -1 (white), centered.
                valueStyleMethod.invoke(pathlossValue, 0, -1);

                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, "NR5G::Downlink_Measurements::NR_Pathloss_DL");
                attrFormat.set(prop, "%.1f dB");
                attrIndex.set(prop, -1);
                valueBindMethod.invoke(pathlossValue, prop, true);
            }

            // Step 4: add new elements to the layout.
            ViewGroup layout = null;
            if (pathlossLabel != null && pathlossValue != null) {
                if (rootView instanceof android.widget.ScrollView
                        && ((android.widget.ScrollView) rootView).getChildCount() > 0) {
                    layout = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                    if (layout != null) {
                        addElementToLayout(layout, list, pathlossLabel);
                        addElementToLayout(layout, list, pathlossValue);
                    }
                }
            }

            // Step 5: store geometry and mark injected.
            if (pathlossLabel != null && pathlossValue != null) {
                View labelView = (View) elementViewField.get(pathlossLabel);
                View valueView = (View) elementViewField.get(pathlossValue);
                if (labelView != null && valueView != null) {
                    rootView.setTag(R.id.nsg_header_pathloss_label, labelView);
                    rootView.setTag(R.id.nsg_header_pathloss_value, valueView);
                    rootView.setTag(R.id.nsg_header_pathloss_injected, Boolean.TRUE);

                    fragmentGeometry.put(thiz, new HeaderGeometry(
                            ssbArfcnLabel, pciLabel, ssRsrpLabel, ssSinrLabel,
                            ssbArfcnValue, pciValue, ssRsrpValue, ssSinrValue,
                            pathlossLabel, pathlossValue));

                    Log.i(TAG, "NrSaHeaderPathlossHook: injected pathloss column");
                    // Apply the geometry for the current RAT right away.
                    updateVisibility(thiz);
                } else {
                    Log.w(TAG, "NrSaHeaderPathlossHook: label or value underlying view is null");
                }
            } else {
                Log.w(TAG, "NrSaHeaderPathlossHook: pathloss label or value cell is null");
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaHeaderPathlossHook: injectPathlossColumn failed: " + e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addElementToLayout(ViewGroup layout, ArrayList builderList, Object elem)
            throws Exception {
        // Append to r31's internal list so it is updated with the other elements.
        builderList.add(elem);

        // Create the actual View for this element and attach it to the ah0 layout.
        View view = (View) elementCreateViewMethod.invoke(elem, layout);
        if (view != null) {
            layout.addView(view);
            @SuppressWarnings("unchecked")
            ArrayList<Object> layoutList = (ArrayList<Object>) layoutListField.get(layout);
            if (layoutList != null) {
                layoutList.add(elem);
            }
        }
    }

    private void updateVisibility(Object thiz) {
        try {
            View rootView = fragmentRootViews.get(thiz);
            if (rootView == null) {
                // Fallback to Fragment.getView() if I() ran while disabled and didn't cache it.
                try {
                    Method getViewMethod = headerRFFragmentClass.getMethod("getView");
                    Object rootViewObj = getViewMethod.invoke(thiz);
                    if (rootViewObj instanceof View) {
                        rootView = (View) rootViewObj;
                        fragmentRootViews.put(thiz, rootView);
                    }
                } catch (Exception fallbackEx) {
                    // ignore
                }
                if (rootView == null) return;
            }

            boolean enabled = SettingsToggleHook.pathlossColumnEnabled();
            boolean injected = rootView.getTag(R.id.nsg_header_pathloss_injected) != null;

            if (!enabled) {
                // Toggle OFF: if the column was previously injected, restore the stock
                // 4-column layout and hide the pathloss views.
                if (injected) {
                    HeaderGeometry geo = fragmentGeometry.get(thiz);
                    if (geo != null) {
                        setGeometry(geo.ssbArfcnLabel, 0.0f, 25.0f);
                        setGeometry(geo.ssbArfcnValue, 0.0f, 25.0f);
                        setGeometry(geo.pciLabel,      25.0f, 15.0f);
                        setGeometry(geo.pciValue,      25.0f, 15.0f);
                        setGeometry(geo.ssRsrpLabel,   41.0f, 30.0f);
                        setGeometry(geo.ssRsrpValue,   41.0f, 30.0f);
                        setGeometry(geo.ssSinrLabel,   72.0f, 28.0f);
                        setGeometry(geo.ssSinrValue,   72.0f, 28.0f);
                        setGeometry(geo.pathlossLabel, 0.0f, 0.0f);
                        setGeometry(geo.pathlossValue, 0.0f, 0.0f);

                        View labelView = (View) rootView.getTag(R.id.nsg_header_pathloss_label);
                        View valueView = (View) rootView.getTag(R.id.nsg_header_pathloss_value);
                        if (labelView != null && labelView.getVisibility() != View.INVISIBLE) {
                            labelView.setVisibility(View.INVISIBLE);
                        }
                        if (valueView != null && valueView.getVisibility() != View.INVISIBLE) {
                            valueView.setVisibility(View.INVISIBLE);
                        }

                        if (rootView instanceof android.widget.ScrollView
                                && ((android.widget.ScrollView) rootView).getChildCount() > 0) {
                            ViewGroup layout = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                            forceLayout(layout);
                        }
                    }
                }
                return;
            }

            // Toggle ON: inject now if not already injected for this fragment.
            if (!injected) {
                injectPathlossColumn(thiz, rootView);
                return;
            }

            // Already injected: apply NR-SA vs. standard geometry as before.
            View labelView = (View) rootView.getTag(R.id.nsg_header_pathloss_label);
            View valueView = (View) rootView.getTag(R.id.nsg_header_pathloss_value);
            if (labelView == null || valueView == null) return;

            boolean isNrSa = isNrSaMode();
            HeaderGeometry geo = fragmentGeometry.get(thiz);
            if (geo == null) return;

            if (isNrSa) {
                setGeometry(geo.ssbArfcnLabel, 0.0f, 21.0f);
                setGeometry(geo.ssbArfcnValue, 0.0f, 21.0f);
                setGeometry(geo.pciLabel,      21.0f, 13.0f);
                setGeometry(geo.pciValue,      21.0f, 13.0f);
                setGeometry(geo.ssRsrpLabel,   50.0f, 24.0f);
                setGeometry(geo.ssRsrpValue,   50.0f, 24.0f);
                setGeometry(geo.ssSinrLabel,   74.0f, 24.0f);
                setGeometry(geo.ssSinrValue,   74.0f, 24.0f);
                setGeometry(geo.pathlossLabel, 34.0f, 16.0f);
                setGeometry(geo.pathlossValue, 34.0f, 16.0f);
            } else {
                setGeometry(geo.ssbArfcnLabel, 0.0f, 25.0f);
                setGeometry(geo.ssbArfcnValue, 0.0f, 25.0f);
                setGeometry(geo.pciLabel,      25.0f, 15.0f);
                setGeometry(geo.pciValue,      25.0f, 15.0f);
                setGeometry(geo.ssRsrpLabel,   41.0f, 30.0f);
                setGeometry(geo.ssRsrpValue,   41.0f, 30.0f);
                setGeometry(geo.ssSinrLabel,   72.0f, 28.0f);
                setGeometry(geo.ssSinrValue,   72.0f, 28.0f);
                setGeometry(geo.pathlossLabel, 0.0f, 0.0f);
                setGeometry(geo.pathlossValue, 0.0f, 0.0f);
            }

            int visibility = isNrSa ? View.VISIBLE : View.INVISIBLE;
            if (labelView.getVisibility() != visibility) labelView.setVisibility(visibility);
            if (valueView.getVisibility() != visibility) valueView.setVisibility(visibility);

            if (rootView instanceof android.widget.ScrollView
                    && ((android.widget.ScrollView) rootView).getChildCount() > 0) {
                ViewGroup layout = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                forceLayout(layout);
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaHeaderPathlossHook: updateVisibility failed: " + e);
        }
    }

    private void setGeometry(Object elem, float col, float width) throws Exception {
        if (elem == null) return;
        elementColField.set(elem, col);
        elementWidthField.set(elem, width);
    }

    private void forceLayout(final ViewGroup layout) {
        if (layout == null) return;
        layout.requestLayout();
        layout.post(() -> {
            try {
                if (layout.getWidth() > 0 && layout.getHeight() > 0) {
                    Method onLayoutMethod = ViewGroup.class.getDeclaredMethod(
                            "onLayout", boolean.class, int.class, int.class, int.class, int.class);
                    onLayoutMethod.setAccessible(true);
                    onLayoutMethod.invoke(layout, true,
                            layout.getLeft(), layout.getTop(), layout.getRight(), layout.getBottom());
                }
            } catch (Exception e) {
                Log.w(TAG, "NrSaHeaderPathlossHook: forceLayout failed: " + e);
            }
        });
    }

    private boolean isNrSaMode() {
        try {
            Object workspace = wsSingletonField.get(null);
            if (workspace == null) {
                return false;
            }
            Object rat = wsRatField.get(workspace);
            if (rat == null) {
                return false;
            }
            return "NR-SA".equals(rat.toString());
        } catch (Exception e) {
            Log.w(TAG, "NrSaHeaderPathlossHook: isNrSaMode failed: " + e);
            return false;
        }
    }
}
