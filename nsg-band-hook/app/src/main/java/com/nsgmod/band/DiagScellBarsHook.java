package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Feeds the SCell CSI SNR bars injected by NrSaCsiSnrRowHook with live values
 * from the DiagBridge DCI helper.
 *
 * The bars are bound to NSG's real per-cell NR5G::Cell_Measurements::
 * NR_Cells_SINR array (index = SCell number + 1), so NSG renders fill, color
 * and text through its own pipeline. The bar update method (v6.f.a) stores
 * the raw property value in field f; this hook runs after it and replaces
 * that stored value with the CSI SNR measured by the helper for the same
 * cell — matched by the NSG sample key being rendered. NSG's own renderer
 * (v6.f.e) then shows the helper value for text, fill and color, without any
 * flickering between data sources.
 */
public class DiagScellBarsHook {

    private static final String TAG = "NSGBandHook";

    /** NSG's per-cell SINR array the SCell bars are bound to. */
    public static final String SINR_CELLS_KEY =
            "NR5G::Cell_Measurements::NR_Cells_SINR";

    private static final String SCELL_PCI_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_PCI";
    private static final String SCELL_ARFCN_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Field vfValueField;   // v6.f "f": raw property value (Object[])
    private Field vfBindingField; // v6.f "g": binding
    private Field sysAFieldC;     // com.qtrun.sys.a "c": index
    private Field wsJ, wsG, wsModuleIndex, wsDataSource;
    private Method dsGetProperty, propBMethod, iterEndMethod, iterValueMethod;

    /** v6.f bar element → SCell index (0-based). */
    private static final Map<Object, Integer> barCells =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private boolean ready = false;

    public DiagScellBarsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> v6fClass = ClassMapping.loadClass("v6.f", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);

            vfValueField = findField(v6fClass, "f");
            vfBindingField = v6fClass.getDeclaredField("g");
            vfBindingField.setAccessible(true);
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldC.setAccessible(true);

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

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "DiagScellBarsHook: skipping install");
            return;
        }
        installBarUpdateHook();
        Log.i(TAG, "DiagScellBarsHook: installed");
    }

    /** Called by NrSaCsiSnrRowHook when an SCell bar is created. */
    static void registerBar(Object barElement, int scellIdx) {
        barCells.put(barElement, scellIdx);
    }

    private void installBarUpdateHook() {
        try {
            Class<?> v6fClass = ClassMapping.loadClass("v6.f", loader);
            Class<?> dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Method aMethod = v6fClass.getMethod("a", long.class, dsClass, short.class);

            xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object bar = chain.getThisObject();
                        Integer scellIdx = barCells.get(bar);
                        if (scellIdx != null) {
                            long sampleKey = ((Number) chain.getArg(0)).longValue();
                            patchValue(bar, scellIdx, sampleKey);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "DiagScellBarsHook: patch failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "DiagScellBarsHook: bar update hook failed: " + e);
        }
    }

    /**
     * Replaces the raw SINR array value stored by v6.f.a with a copy whose
     * element for this bar's SCell index carries the helper's CSI SNR, so
     * NSG's renderer displays it like any native measurement.
     */
    private void patchValue(Object bar, int scellIdx, long sampleKey) {
        try {
            int pci = -1;
            long arfcn = -1;
            int[] pcis = readIntArray(SCELL_PCI_KEY, sampleKey);
            long[] arfcns = readLongArray(SCELL_ARFCN_KEY, sampleKey);
            if (pcis != null && scellIdx < pcis.length) pci = pcis[scellIdx];
            if (arfcns != null && scellIdx < arfcns.length) arfcn = arfcns[scellIdx];
            if (pci < 0) return;

            Double snr = DiagBridge.csiSnr(pci, arfcn, sampleKey);
            if (snr == null) return;

            Object raw = vfValueField.get(bar);
            Object[] arr;
            if (raw instanceof Object[]) {
                Object[] old = (Object[]) raw;
                int idx = bindingIndex(bar);
                if (idx < 0) return;
                arr = new Object[Math.max(old.length, idx + 1)];
                System.arraycopy(old, 0, arr, 0, Math.min(old.length, arr.length));
                arr[idx] = snr;
            } else {
                int idx = bindingIndex(bar);
                if (idx < 0) return;
                arr = new Object[idx + 1];
                arr[idx] = snr;
            }
            vfValueField.set(bar, arr);
        } catch (Throwable t) {
            Log.w(TAG, "DiagScellBarsHook: patchValue failed: " + t);
        }
    }

    private int bindingIndex(Object bar) {
        try {
            Object binding = vfBindingField.get(bar);
            return binding != null ? sysAFieldC.getInt(binding) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Array property readers
    // ------------------------------------------------------------------

    private int[] readIntArray(String key, long sampleKey) {
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

    private long[] readLongArray(String key, long sampleKey) {
        int[] ints = readIntArray(key, sampleKey);
        if (ints == null) return null;
        long[] r = new long[ints.length];
        for (int i = 0; i < ints.length; i++) r[i] = ints[i];
        return r;
    }

    private Object readProperty(String key, long sampleKey) {
        try {
            Object ws = wsJ.get(null);
            if (ws == null) return null;
            int module = ((Number) wsModuleIndex.get(ws)).intValue();
            Object ds = wsDataSource.get(ws);
            if (ds == null) return null;
            Object property = dsGetProperty.invoke(ds, key, module);
            if (property == null) return null;
            Object iterator = propBMethod.invoke(property, sampleKey);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;
            return iterValueMethod.invoke(iterator);
        } catch (Throwable t) {
            return null;
        }
    }
}
