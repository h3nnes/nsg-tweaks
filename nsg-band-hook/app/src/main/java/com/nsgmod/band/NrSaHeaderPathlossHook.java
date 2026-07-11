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
 * Hook: AFTER HeaderRFFragment.I(LayoutInflater, ViewGroup, Bundle)
 * Hook: AFTER HeaderRFFragment.b(DataSource, long, short, Object)
 */
public class NrSaHeaderPathlossHook {

    private static final String TAG = "NSGBandHook";

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
    private Field headerRFFragmentK2aField; // found by type k2.a

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) -> v6.e (label)
    private Method k2aTMethod; // t(float row, float h, float col, float w) -> v6.g (value)
    private Method k2aJMethod; // j(Context, int) -> ScrollView
    private Field k2aListField;  // d -> ArrayList<v6.a>

    // v6.e label fields
    private Field veF; // text   (f)
    private Field veG; // appearance (g)
    private Field veH; // gravity (h)

    // v6.g value cell methods
    private Method vgGMethod; // g(com.qtrun.sys.b, boolean)
    private Method vgJMethod; // j(int appearance, int color)

    // v6.a element fields
    private Field vaViewField; // a -> View
    private Field vaRowField;   // b (row float)
    private Field vaColField;   // d (col float)
    private Field vaWidthField; // e (width float)

    // v6.d layout internal list
    private Class<?> v6dClass;
    private Field v6dListField; // a -> ArrayList<v6.a>

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field sysAFieldA; // final String key
    private Field sysAFieldB; // final String format
    private Field sysAFieldC; // int index

    // Workspace reflection for NR-SA detection
    private Class<?> workspaceClass;
    private Field wsSingletonField; // j
    private Field wsRatField;     // d -> com.qtrun.sys.j

    // Unsafe for allocateInstance (com.qtrun.sys.b ctor stripped by ProGuard)
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
            headerRFFragmentClass = loader.loadClass("com.qtrun.udv.header.HeaderRFFragment");

            Class<?> k2aClass = loader.loadClass("k2.a");
            Class<?> veClass = loader.loadClass("v6.e");
            Class<?> vgClass = loader.loadClass("v6.g");
            Class<?> vaClass = loader.loadClass("v6.a");
            v6dClass = loader.loadClass("v6.d");

            // Find the k2.a field inside HeaderRFFragment by type.
            for (Field field : headerRFFragmentClass.getDeclaredFields()) {
                if ("k2.a".equals(field.getType().getName())) {
                    field.setAccessible(true);
                    headerRFFragmentK2aField = field;
                    break;
                }
            }
            if (headerRFFragmentK2aField == null) {
                throw new NoSuchFieldException("k2.a field not found in HeaderRFFragment");
            }

            k2aRMethod = k2aClass.getMethod("r", float.class, float.class, float.class, float.class);
            k2aTMethod = k2aClass.getMethod("t", float.class, float.class, float.class, float.class);
            k2aJMethod = k2aClass.getMethod("j", android.content.Context.class, int.class);
            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vaViewField = vaClass.getDeclaredField("a");
            vaRowField = vaClass.getDeclaredField("b");
            vaColField = vaClass.getDeclaredField("d");
            vaWidthField = vaClass.getDeclaredField("e");
            vaViewField.setAccessible(true);
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
            vgJMethod = vgClass.getMethod("j", int.class, int.class);

            v6dListField = v6dClass.getDeclaredField("a");
            v6dListField.setAccessible(true);

            workspaceClass = loader.loadClass("com.qtrun.sys.Workspace");
            wsSingletonField = workspaceClass.getField("j");
            wsRatField = workspaceClass.getDeclaredField("d");
            wsRatField.setAccessible(true);

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
            Class<?> layoutInflaterClass = loader.loadClass("android.view.LayoutInflater");
            Class<?> viewGroupClass = loader.loadClass("android.view.ViewGroup");
            Class<?> bundleClass = loader.loadClass("android.os.Bundle");
            Method iMethod = headerRFFragmentClass.getMethod("I",
                    layoutInflaterClass, viewGroupClass, bundleClass);

            xposed.hook(iMethod).intercept(new Hooker() {
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

            Class<?> dataSourceClass = loader.loadClass("com.qtrun.sys.DataSource");
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
            Object k2aObj = headerRFFragmentK2aField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "NrSaHeaderPathlossHook: k2.a builder is null, skipping");
                return;
            }

            @SuppressWarnings("unchecked")
            ArrayList<Object> list = (ArrayList<Object>) k2aListField.get(k2aObj);
            if (list == null) {
                Log.w(TAG, "NrSaHeaderPathlossHook: k2.a list is null, skipping");
                return;
            }

            // Step 1: identify the four existing header columns (label + value for each).
            Object ssbArfcnLabel = null, ssbArfcnValue = null;
            Object pciLabel = null, pciValue = null;
            Object ssRsrpLabel = null, ssRsrpValue = null;
            Object ssSinrLabel = null, ssSinrValue = null;

            for (Object elem : list) {
                float row = (float) vaRowField.get(elem);
                if (row != 0.0f && row != 1.0f) {
                    continue;
                }
                float col = (float) vaColField.get(elem);
                float width = (float) vaWidthField.get(elem);

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
            Object pathlossLabel = k2aRMethod.invoke(k2aObj, 0.0f, h, 34.0f, 16.0f);
            if (pathlossLabel != null) {
                veF.set(pathlossLabel, "Pathloss");
                veG.set(pathlossLabel, 0); // appearance
                veH.set(pathlossLabel, 2); // gravity
            }

            // Step 3: create Pathloss value cell at row=1, col=34, width=16.
            Object pathlossValue = k2aTMethod.invoke(k2aObj, 1.0f, h, 34.0f, 16.0f);
            if (pathlossValue != null) {
                // Match the surrounding value cells: appearance 0, color -1 (white), centered.
                vgJMethod.invoke(pathlossValue, 0, -1);

                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_Pathloss_DL");
                sysAFieldB.set(prop, "%.1f dB");
                sysAFieldC.set(prop, -1);
                vgGMethod.invoke(pathlossValue, prop, true);
            }

            // Step 4: add new elements to the layout.
            ViewGroup v6d = null;
            if (pathlossLabel != null && pathlossValue != null) {
                if (rootView instanceof android.widget.ScrollView
                        && ((android.widget.ScrollView) rootView).getChildCount() > 0) {
                    v6d = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                    if (v6d != null) {
                        addElementToLayout(k2aObj, v6d, list, pathlossLabel);
                        addElementToLayout(k2aObj, v6d, list, pathlossValue);
                    }
                }
            }

            // Step 5: store geometry and mark injected.
            if (pathlossLabel != null && pathlossValue != null) {
                View labelView = (View) vaViewField.get(pathlossLabel);
                View valueView = (View) vaViewField.get(pathlossValue);
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
    private void addElementToLayout(Object k2aObj, ViewGroup v6d, ArrayList k2aList, Object elem)
            throws Exception {
        // Append to k2.a's internal list so it is updated by k2a.e().
        k2aList.add(elem);

        // Create the actual View for this element and attach it to v6.d.
        View view = (View) vaClassCMethod().invoke(elem, v6d);
        if (view != null) {
            v6d.addView(view);
            @SuppressWarnings("unchecked")
            ArrayList<Object> v6dList = (ArrayList<Object>) v6dListField.get(v6d);
            if (v6dList != null) {
                v6dList.add(elem);
            }
        }
    }

    private Method vaClassCMethod;
    private Method vaClassCMethod() throws Exception {
        if (vaClassCMethod == null) {
            Class<?> vaClass = loader.loadClass("v6.a");
            vaClassCMethod = vaClass.getMethod("c", ViewGroup.class);
        }
        return vaClassCMethod;
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
                            ViewGroup v6d = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                            forceV6dLayout(v6d);
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
                ViewGroup v6d = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                forceV6dLayout(v6d);
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaHeaderPathlossHook: updateVisibility failed: " + e);
        }
    }

    private void setGeometry(Object elem, float col, float width) throws Exception {
        if (elem == null) return;
        vaColField.set(elem, col);
        vaWidthField.set(elem, width);
    }

    private void forceV6dLayout(final ViewGroup v6d) {
        if (v6d == null) return;
        v6d.requestLayout();
        v6d.post(() -> {
            try {
                if (v6d.getWidth() > 0 && v6d.getHeight() > 0) {
                    Method onLayoutMethod = ViewGroup.class.getDeclaredMethod(
                            "onLayout", boolean.class, int.class, int.class, int.class, int.class);
                    onLayoutMethod.setAccessible(true);
                    onLayoutMethod.invoke(v6d, true,
                            v6d.getLeft(), v6d.getTop(), v6d.getRight(), v6d.getBottom());
                }
            } catch (Exception e) {
                Log.w(TAG, "NrSaHeaderPathlossHook: forceV6dLayout failed: " + e);
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
