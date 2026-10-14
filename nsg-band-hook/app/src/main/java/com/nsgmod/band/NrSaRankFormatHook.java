package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Reformats the NR Rank indicator as a decimal value.
 *
 * NSG renders Rank via the d7.i$k binding (collapsed into class v00 from
 * v4.8.9 on, case id 27) as the enum text "Rank 1".."Rank 4". The underlying
 * per-sample scheduled layer counts are already available in NSG's own
 * property store (NR_PCell_Num_Layers_DL / NR_SCell_Num_Layers_DL, fed by
 * NR5G MAC PDSCH Status per-TB records), so the mean number of scheduled
 * layers over a short time window — e.g. "2.7" — can be computed without any
 * additional modem data.
 *
 * This hook intercepts the same value formatter used by NrSaMimoFormatHook
 * (v00.c(Object), logical name d7.i$c.c) for keys containing "Rank_DL" or
 * "Rank_UL" and replaces the enum text with the decimal mean. The element
 * identity (PCell vs SCell, SCell index) is preserved exactly as NSG bound
 * it; only the formatting changes. When no scheduled-layer samples exist in
 * the window, the original NSG text is shown.
 */
public class NrSaRankFormatHook {

    private static final String TAG = "NSGBandHook";

    /** Time window (ms) over which scheduled layers are averaged. Matches the
     *  ~1 s window used by external diag tools for mean-layer statistics. */
    private static final long WINDOW_MS = 1000;

    /** Extended lookback (ms) used when the primary window holds no samples:
     *  sparsely scheduled cells (light background traffic) produce layer
     *  samples seconds apart; without the fallback the display would flip
     *  back to the enum text most of the time. */
    private static final long FALLBACK_MS = 5000;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean ready = false;

    // com.qtrun.sys.a fields (inherited by the formatter binding)
    private Field sysAKeyField;    // "a" (String key)
    private Field sysAIndexField;  // "c" (int SCell index, -1 = scalar/PCell)

    // Workspace / DataSource reflection
    private Field  wsJ;            // Workspace.j singleton
    private Field  wsG;            // Workspace.g — current sample key (cursor)
    private Field  wsModuleIndex;  // Workspace.a (module index)
    private Field  wsDataSource;   // Workspace.c (DataSource)
    private Method dsGetProperty;  // DataSource.getProperty(String, int)
    private Method propBMethod;    // Property.b(long) → Iterator
    private Method iterEndMethod;  // Iterator.end()Z
    private Method iterKeyMethod;  // Iterator.key()J
    private Method iterPrevMethod; // Iterator.prev()V
    private Method iterValueMethod;// Iterator.value()Object

    public NrSaRankFormatHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            sysAKeyField = sysAClass.getField("a");
            sysAIndexField = sysAClass.getDeclaredField("c");
            sysAIndexField.setAccessible(true);

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
            iterKeyMethod = iterClass.getMethod("key");
            iterPrevMethod = iterClass.getMethod("prev");
            iterValueMethod = iterClass.getMethod("value");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaRankFormatHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaRankFormatHook: skipping install");
            return;
        }
        installFormatterHook();
    }

    private void installFormatterHook() {
        try {
            Class<?> formatterClass = ClassMapping.loadClass("d7.i$c", loader);
            if (formatterClass == null) {
                Log.i(TAG, "NrSaRankFormatHook: d7.i$c not available, skipping formatter hook");
                return;
            }
            Method cMethod = formatterClass.getMethod("c", Object.class);

            xposed.hook(cMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object thisObj = chain.getThisObject();
                    String key = (String) sysAKeyField.get(thisObj);

                    if (key == null || !(key.contains("Rank_DL") || key.contains("Rank_UL"))) {
                        return chain.proceed();
                    }
                    if (!SettingsToggleHook.decimalRankEnabled()) {
                        return chain.proceed();
                    }

                    // Preferred: B887 MAC PDSCH grants via the diag helper
                    // (per-TB mean scheduled layers, like external diag tools).
                    Double mean = diagRankMean(thisObj, key);
                    if (mean == null) {
                        // Fallback: NSG's own scheduled-layer property walk.
                        mean = meanScheduledLayers(thisObj, key);
                    }
                    if (mean == null) {
                        return chain.proceed();
                    }
                    return String.format(java.util.Locale.US, "%.1f", mean);
                }
            });
            Log.i(TAG, "NrSaRankFormatHook: formatter hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaRankFormatHook: install d7.i$c failed: " + e);
        }
    }

    /**
     * Mean scheduled layers from the diag helper's B887 stream (qbm-style
     * per-TB mean). Resolves the cell identity the same way the MIMO hook
     * does: PCell from the Serving_Cell scalars, SCell from the Serving_Cell
     * SCell arrays at the binding's SCell index. Null when the helper is not
     * connected or has no fresh report for the cell.
     */
    private Double diagRankMean(Object binding, String rankKey) {
        if (!DiagBridge.isAvailable()) {
            return null;
        }

        NrSaMimoFormatHook.FormatContext ctx = NrSaMimoFormatHook.currentFormatContext();
        long sampleKey;
        int moduleIndex;
        Object ds;

        if (ctx != null) {
            sampleKey = ctx.sampleKey;
            moduleIndex = ctx.moduleIndex;
            ds = ctx.dataSource;
        } else {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) return null;
                sampleKey = wsG.getLong(ws);
                moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
                ds = wsDataSource.get(ws);
            } catch (Exception e) {
                return null;
            }
        }
        if (ds == null) return null;

        try {
            int cellIndex = sysAIndexField.getInt(binding);

            // SCell rank bindings always carry c==0; the color hook's grid
            // geometry mapping (col/row sort) provides the true SCell index.
            Integer geoIdx = NrSaCellColorHook.rankScellIndexForBinding(binding);
            boolean pcell = cellIndex < 0 || rankKey.contains("PCell_Rank");
            if (geoIdx != null) {
                cellIndex = geoIdx;
                pcell = false;
            }

            int pci = -1;
            long arfcn = -1;
            if (pcell) {
                int[] pcis = readIntArray(ds, moduleIndex,
                        "NR5G::Serving_Cell::NR_PCI", sampleKey);
                long[] arfcns = readLongArray(ds, moduleIndex,
                        "NR5G::Serving_Cell::NR_ARFCN_SSB", sampleKey);
                pci = pcis != null ? pcis[0] : -1;
                arfcn = arfcns != null ? arfcns[0] : -1;
            } else {
                int[] pcis = readIntArray(ds, moduleIndex,
                        "NR5G::Serving_Cell::SCell::NR_SCell_PCI", sampleKey);
                long[] arfcns = readLongArray(ds, moduleIndex,
                        "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB", sampleKey);
                if (pcis != null && cellIndex < pcis.length) pci = pcis[cellIndex];
                if (arfcns != null && cellIndex < arfcns.length) arfcn = arfcns[cellIndex];
            }
            if (pci < 0) {
                return null;
            }
            return DiagBridge.rankMean(pci, arfcn);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object readProperty(Object ds, int moduleIndex, String key, long sampleKey) {
        try {
            Object property = dsGetProperty.invoke(ds, key, moduleIndex);
            if (property == null) return null;
            Object iterator = propBMethod.invoke(property, sampleKey);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;
            return iterValueMethod.invoke(iterator);
        } catch (Throwable t) {
            return null;
        }
    }

    private int[] readIntArray(Object ds, int moduleIndex, String key, long sampleKey) {
        Object v = readProperty(ds, moduleIndex, key, sampleKey);
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

    private long[] readLongArray(Object ds, int moduleIndex, String key, long sampleKey) {
        int[] ints = readIntArray(ds, moduleIndex, key, sampleKey);
        if (ints == null) return null;
        long[] r = new long[ints.length];
        for (int i = 0; i < ints.length; i++) r[i] = ints[i];
        return r;
    }

    /**
     * Computes the mean of the scheduled-layer counts that accompany the given
     * Rank property (NR_*_Rank_* → NR_*_Num_Layers_*) over the last
     * {@link #WINDOW_MS} milliseconds, using the exact sample key currently
     * being rendered (v6.g.a context) or the Workspace cursor as fallback.
     *
     * Samples with no scheduled data (value ≤ 0) are excluded, mirroring the
     * per-TB mean used by external diag tooling.
     */
    private Double meanScheduledLayers(Object binding, String rankKey) {
        String layersKey = rankKey.replace("Rank", "Num_Layers");

        long sampleKey;
        int moduleIndex;
        Object ds;

        NrSaMimoFormatHook.FormatContext ctx = NrSaMimoFormatHook.currentFormatContext();
        if (ctx != null) {
            sampleKey = ctx.sampleKey;
            moduleIndex = ctx.moduleIndex;
            ds = ctx.dataSource;
        } else {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) {
                    return null;
                }
                sampleKey = wsG.getLong(ws);
                moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
                ds = wsDataSource.get(ws);
            } catch (Exception e) {
                return null;
            }
        }

        if (ds == null) {
            return null;
        }

        int cellIndex;
        try {
            cellIndex = sysAIndexField.getInt(binding);
        } catch (Exception e) {
            cellIndex = -1;
        }

        try {
            Object property = dsGetProperty.invoke(ds, layersKey, moduleIndex);
            if (property == null) {
                return null;
            }

            Object iterator = propBMethod.invoke(property, sampleKey);
            if (iterator == null) {
                return null;
            }

            long windowStart = sampleKey - WINDOW_MS;
            long fallbackStart = sampleKey - FALLBACK_MS;
            double sumWin = 0;
            int nWin = 0;
            double sumAll = 0;
            int nAll = 0;
            int guard = 0;
            while (!(boolean) iterEndMethod.invoke(iterator) && guard++ < 4096) {
                long k = ((Number) iterKeyMethod.invoke(iterator)).longValue();
                if (k < fallbackStart) {
                    break;
                }
                Number v = extractNumber(iterValueMethod.invoke(iterator), cellIndex);
                if (v != null && v.doubleValue() > 0) {
                    sumAll += v.doubleValue();
                    nAll++;
                    if (k >= windowStart) {
                        sumWin += v.doubleValue();
                        nWin++;
                    }
                }
                iterPrevMethod.invoke(iterator);
            }
            if (nWin > 0) return sumWin / nWin;
            if (nAll > 0) return sumAll / nAll;
            return null;
        } catch (Exception e) {
            Log.w(TAG, "NrSaRankFormatHook: meanScheduledLayers failed for "
                    + layersKey + " idx=" + cellIndex + ": " + e);
            return null;
        }
    }

    /** Extracts the numeric layer count for the given cell index from a raw
     *  property value (scalar, int[], Integer[], or Object[]). A scalar
     *  binding (index -1) reading an array property takes element 0. */
    private static Number extractNumber(Object value, int cellIndex) {
        if (value instanceof Number) {
            return (Number) value;
        }
        int idx = cellIndex >= 0 ? cellIndex : 0;
        if (value instanceof int[] && idx < ((int[]) value).length) {
            return ((int[]) value)[idx];
        }
        if (value instanceof Integer[] && idx < ((Integer[]) value).length) {
            return ((Integer[]) value)[idx];
        }
        if (value instanceof Object[] && idx < ((Object[]) value).length) {
            Object v = ((Object[]) value)[idx];
            return v instanceof Number ? (Number) v : null;
        }
        return null;
    }
}
