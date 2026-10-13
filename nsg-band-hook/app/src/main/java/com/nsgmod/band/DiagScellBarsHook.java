package com.nsgmod.band;

import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Feeds the SCell CSI SNR bars injected by NrSaCsiSnrRowHook with live values
 * from the DiagBridge DCI helper.
 *
 * The bars are bound to a marker key (no such NSG property exists), tracked
 * here together with their SCell index. Once the page's views exist
 * (v6.b.I), the TextView inside each bar view is located and refreshed on a
 * short schedule from DiagBridge, matched by SCell PCI / SSB ARFCN arrays and
 * the NSG sample key currently being rendered.
 */
public class DiagScellBarsHook {

    private static final String TAG = "NSGBandHook";

    /** Binding key marking an SCell CSI SNR bar (index = SCell number). */
    public static final String MARKER_KEY = "NSGMod::SCell_CSI_SINR";

    /** Marker key prefix shared with other diag-fed bindings. */
    private static final String SCELL_PCI_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_PCI";
    private static final String SCELL_ARFCN_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a / v6.a / com.qtrun.sys reflection
    private Field k2aListField;   // k2.a "d": ArrayList<v6.a>
    private Field vaRowField;     // v6.a "b": float row
    private Field vaColField;     // v6.a "d": float column
    private Field vaViewField;    // v6.a "a": View
    private Field vfBindingField; // v6.f "g": binding
    private Field sysAFieldA;     // com.qtrun.sys.a "a": key
    private Field sysAFieldC;     // com.qtrun.sys.a "c": index
    private Field v6bYField;      // v6.b "Y": k2.a stored on the fragment
    private Field wsJ, wsG, wsModuleIndex, wsDataSource;
    private Method dsGetProperty, propBMethod, iterEndMethod, iterValueMethod;

    /** k2.a builder → tracked (element, scellIdx) pairs for that page. */
    private static final Map<Object, List<BarRef>> barsByBuilder =
            new WeakHashMap<>();

    private static final class BarRef {
        Object element;
        final int scellIdx;
        TextView view; // resolved lazily after v6.b.I
        BarRef(Object element, int scellIdx) {
            this.element = element;
            this.scellIdx = scellIdx;
        }
    }

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NSGMod-ScellBars");
                t.setDaemon(true);
                return t;
            });

    private static volatile DiagScellBarsHook instance;

    private boolean ready = false;

    public DiagScellBarsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        instance = this;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> v6aClass = ClassMapping.loadClass("v6.a", loader);
            Class<?> v6fClass = ClassMapping.loadClass("v6.f", loader);
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);

            k2aListField = k2aClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("k2.a", "d", loader));
            k2aListField.setAccessible(true);
            vaRowField = v6aClass.getDeclaredField("b");
            vaRowField.setAccessible(true);
            vaColField = v6aClass.getDeclaredField("d");
            vaColField.setAccessible(true);
            vaViewField = v6aClass.getDeclaredField("a");
            vaViewField.setAccessible(true);
            vfBindingField = v6fClass.getDeclaredField("g");
            vfBindingField.setAccessible(true);
            sysAFieldA = sysAClass.getField("a");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldC.setAccessible(true);
            v6bYField = v6bClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("v6.b", "Y", loader));
            v6bYField.setAccessible(true);

            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            Class<?> dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Class<?> propClass = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterClass = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);
            wsJ = wsClass.getField(ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader));
            wsG = wsClass.getDeclaredField(ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "g", loader));
            wsG.setAccessible(true);
            wsModuleIndex = wsClass.getField(ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "a", loader));
            wsDataSource = wsClass.getField(ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "c", loader));
            dsGetProperty = dsClass.getMethod("getProperty", String.class, int.class);
            propBMethod = propClass.getMethod("b", long.class);
            iterEndMethod = iterClass.getMethod("end");
            iterValueMethod = iterClass.getMethod("value");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "DiagScellBarsHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "DiagScellBarsHook: skipping install");
            return;
        }
        installViewHook();
        EXECUTOR.scheduleWithFixedDelay(this::refreshBars, 2, 500, TimeUnit.MILLISECONDS);
        Log.i(TAG, "DiagScellBarsHook: installed");
    }

    /** Called by NrSaCsiSnrRowHook when an SCell bar is created. */
    static void registerBar(Object k2aBuilder, Object barElement, int scellIdx) {
        synchronized (barsByBuilder) {
            List<BarRef> refs = barsByBuilder.get(k2aBuilder);
            if (refs == null) {
                refs = new ArrayList<>();
                barsByBuilder.put(k2aBuilder, refs);
            }
            for (BarRef r : refs) {
                if (r.scellIdx == scellIdx) {
                    r.element = barElement == null ? r.element : barElement;
                    return;
                }
            }
            refs.add(new BarRef(barElement, scellIdx));
        }
    }

    // ------------------------------------------------------------------
    // View resolution: hook v6.b.I(LayoutInflater, ViewGroup, Bundle) and
    // bind TextViews to tracked bars once views exist.
    // ------------------------------------------------------------------

    private void installViewHook() {
        try {
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Method iMethod = ClassMapping.getMethod(v6bClass, "v6.b", "I", loader,
                    android.view.LayoutInflater.class, android.view.ViewGroup.class, android.os.Bundle.class);
            if (iMethod == null) {
                Log.w(TAG, "DiagScellBarsHook: v6.b.I not found");
                return;
            }
            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        resolveViews(chain.getThisObject());
                    } catch (Throwable t) {
                        Log.w(TAG, "DiagScellBarsHook: resolveViews failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "DiagScellBarsHook: view hook failed: " + e);
        }
    }

    private void resolveViews(Object fragment) {
        try {
            Object k2a = v6bYField.get(fragment);
            if (k2a == null) return;
            List<BarRef> refs;
            synchronized (barsByBuilder) {
                refs = barsByBuilder.get(k2a);
            }
            if (refs == null || refs.isEmpty()) return;

            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2a);
            if (list == null) return;

            // Map element identity → view by scanning the builder list.
            for (BarRef ref : refs) {
                if (ref.view != null) continue;
                for (Object elem : list) {
                    if (elem != ref.element) continue;
                    Object view = vaViewField.get(elem);
                    if (view instanceof android.view.View) {
                        ref.view = findTextView((android.view.View) view);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "DiagScellBarsHook: resolveViews failed: " + t);
        }
    }

    /** Depth-first search for the first TextView inside a bar view. */
    private static TextView findTextView(android.view.View v) {
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Periodic refresh
    // ------------------------------------------------------------------

    private void refreshBars() {
        if (!SettingsToggleHook.scellDiagEnabled() || !DiagBridge.isAvailable()) return;

        List<BarRef> active = new ArrayList<>();
        synchronized (barsByBuilder) {
            Iterator<Map.Entry<Object, List<BarRef>>> it = barsByBuilder.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, List<BarRef>> e = it.next();
                List<BarRef> refs = e.getValue();
                refs.removeIf(r -> r.view == null && !elementAlive(r));
                if (refs.isEmpty()) {
                    it.remove();
                    continue;
                }
                active.addAll(refs);
            }
        }
        if (active.isEmpty()) return;

        Long sampleKey = DiagBridge.currentSampleKey();
        int[] pcis = readIntArray(SCELL_PCI_KEY, sampleKey);
        long[] arfcns = readLongArray(SCELL_ARFCN_KEY, sampleKey);

        for (BarRef ref : active) {
            if (ref.view == null) continue;
            try {
                int idx = ref.scellIdx;
                int pci = pcis != null && idx < pcis.length ? pcis[idx] : -1;
                long arfcn = arfcns != null && idx < arfcns.length ? arfcns[idx] : -1;
                Double snr = pci >= 0 ? DiagBridge.csiSnr(pci, arfcn, sampleKey) : null;
                ref.view.setText(snr != null
                        ? String.format(java.util.Locale.US, "%.1f dB", snr)
                        : "—");
            } catch (Throwable t) {
                Log.w(TAG, "DiagScellBarsHook: refresh failed: " + t);
            }
        }
    }

    private static boolean elementAlive(BarRef r) {
        try {
            return vaViewField_get(r.element) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object vaViewField_get(Object element) throws Exception {
        return instance.vaViewField.get(element);
    }

    // ------------------------------------------------------------------
    // Array property readers (Workspace cursor values)
    // ------------------------------------------------------------------

    private int[] readIntArray(String key, Long sampleKey) {
        Object v = readProperty(key, sampleKey);
        if (v instanceof int[]) return (int[]) v;
        if (v instanceof Integer[]) {
            Integer[] a = (Integer[]) v;
            int[] r = new int[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i] != null ? a[i] : -1;
            return r;
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            int[] r = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                r[i] = a[i] instanceof Number ? ((Number) a[i]).intValue() : -1;
            }
            return r;
        }
        if (v instanceof Number) return new int[]{((Number) v).intValue()};
        return null;
    }

    private long[] readLongArray(String key, Long sampleKey) {
        int[] ints = readIntArray(key, sampleKey);
        if (ints == null) return null;
        long[] r = new long[ints.length];
        for (int i = 0; i < ints.length; i++) r[i] = ints[i];
        return r;
    }

    private Object readProperty(String key, Long sampleKey) {
        try {
            Object ws = wsJ.get(null);
            if (ws == null) return null;
            long sk = sampleKey != null ? sampleKey : wsG.getLong(ws);
            int module = ((Number) wsModuleIndex.get(ws)).intValue();
            Object ds = wsDataSource.get(ws);
            if (ds == null) return null;
            Object property = dsGetProperty.invoke(ds, key, module);
            if (property == null) return null;
            Object iterator = propBMethod.invoke(property, sk);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;
            return iterValueMethod.invoke(iterator);
        } catch (Throwable t) {
            return null;
        }
    }
}
