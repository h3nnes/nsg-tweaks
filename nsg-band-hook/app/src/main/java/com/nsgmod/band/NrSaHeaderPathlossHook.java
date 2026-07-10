package com.nsgmod.band;

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

    // v6.g value cell fields and method
    private Method vgGMethod; // g(com.qtrun.sys.b, boolean)
    private Field vgJ;        // appearance (j)
    private Field vgK;        // gravity (k)

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
            vgJ = vgClass.getDeclaredField("j");
            vgK = vgClass.getDeclaredField("k");
            vgJ.setAccessible(true);
            vgK.setAccessible(true);

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
    }

    private void injectPathlossColumn(Object thiz, View rootView) {
        fragmentRootViews.put(thiz, rootView);
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

            // Step 1: reshape existing row 0 / row 1 elements.
            for (Object elem : list) {
                float row = (float) vaRowField.get(elem);
                if (row != 0.0f && row != 1.0f) {
                    continue;
                }
                float col = (float) vaColField.get(elem);
                float width = (float) vaWidthField.get(elem);

                // SSB-ARFCN: col 0, width 25 -> col 0, width 22
                if (col < 1.0f && Math.abs(width - 25.0f) < 1.0f) {
                    vaColField.set(elem, 0.0f);
                    vaWidthField.set(elem, 22.0f);
                }
                // PCI: col 25, width 15 -> col 22, width 13
                else if (col >= 24.0f && col < 26.0f && Math.abs(width - 15.0f) < 1.0f) {
                    vaColField.set(elem, 22.0f);
                    vaWidthField.set(elem, 13.0f);
                }
                // SS-RSRP: col 41, width 30 -> col 45, width 27
                else if (col >= 40.0f && col < 42.0f && Math.abs(width - 30.0f) < 1.0f) {
                    vaColField.set(elem, 45.0f);
                    vaWidthField.set(elem, 27.0f);
                }
                // SS-SINR: col 72, width 28 -> col 72, width 26
                else if (col >= 71.0f && col < 73.0f && Math.abs(width - 28.0f) < 1.0f) {
                    vaColField.set(elem, 72.0f);
                    vaWidthField.set(elem, 26.0f);
                }
            }

            final float h = 1.0f;

            // Step 2: create Pathloss label at row=0, col=35, width=10.
            Object label = k2aRMethod.invoke(k2aObj, 0.0f, h, 35.0f, 10.0f);
            if (label != null) {
                veF.set(label, "Pathloss");
                veG.set(label, 0); // appearance
                veH.set(label, 2); // gravity
            }

            // Step 3: create Pathloss value cell at row=1, col=35, width=10.
            Object valueCell = k2aTMethod.invoke(k2aObj, 1.0f, h, 35.0f, 10.0f);
            if (valueCell != null) {
                vgJ.set(valueCell, 0); // appearance
                vgK.set(valueCell, 2); // gravity

                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, "NR5G::Downlink_Measurements::NR_Pathloss_DL");
                sysAFieldB.set(prop, "%.1f dB");
                sysAFieldC.set(prop, -1);
                vgGMethod.invoke(valueCell, prop, true);
            }

            // Step 4: add new elements to the layout.
            // HeaderRFFragment.I() has already built the v6.d layout, so we must
            // both append to k2a.d and create/attach the corresponding Views in
            // v6.d's internal child list.
            if (label != null && valueCell != null) {
                // The v6.d layout is the first (and only) child of the ScrollView.
                if (rootView instanceof android.widget.ScrollView
                        && ((android.widget.ScrollView) rootView).getChildCount() > 0) {
                    ViewGroup v6d = (ViewGroup) ((android.widget.ScrollView) rootView).getChildAt(0);
                    if (v6d != null) {
                        addElementToLayout(k2aObj, v6d, list, label);
                        addElementToLayout(k2aObj, v6d, list, valueCell);
                    }
                }
            }

            // Step 5: store references to the underlying Views and mark injected.
            if (label != null && valueCell != null) {
                View labelView = (View) vaViewField.get(label);
                View valueView = (View) vaViewField.get(valueCell);
                if (labelView != null && valueView != null) {
                    // Start hidden; b() hook will toggle based on RAT.
                    labelView.setVisibility(View.GONE);
                    valueView.setVisibility(View.GONE);
                    rootView.setTag(R.id.nsg_header_pathloss_label, labelView);
                    rootView.setTag(R.id.nsg_header_pathloss_value, valueView);
                    rootView.setTag(R.id.nsg_header_pathloss_injected, Boolean.TRUE);
                }
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
                // Fallback: try Fragment.getView() if the I() hook hasn't cached it yet.
                try {
                    Method getViewMethod = headerRFFragmentClass.getMethod("getView");
                    Object rootViewObj = getViewMethod.invoke(thiz);
                    if (rootViewObj instanceof View) {
                        rootView = (View) rootViewObj;
                    }
                } catch (Exception fallbackEx) {
                    // ignore — the map is the primary source
                }
                if (rootView == null) {
                    return;
                }
            }
            View labelView = (View) rootView.getTag(R.id.nsg_header_pathloss_label);
            View valueView = (View) rootView.getTag(R.id.nsg_header_pathloss_value);
            if (labelView == null || valueView == null) {
                return;
            }

            boolean isNrSa = isNrSaMode();
            int visibility = isNrSa ? View.VISIBLE : View.GONE;
            if (labelView.getVisibility() != visibility) {
                labelView.setVisibility(visibility);
            }
            if (valueView.getVisibility() != visibility) {
                valueView.setVisibility(visibility);
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaHeaderPathlossHook: updateVisibility failed: " + e);
        }
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
