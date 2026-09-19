package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks ma.a.j(String, Integer, Integer, Double, Double) — NSG's cell-DB WHERE-clause builder —
 * to substitute ECellID-based matching for LTE instead of the default PCI-based matching.
 *
 * Default NSG behaviour (PCI matching):
 *   earfcn=<EARFCN> and pci=<PCI> and Longitude between <lon±0.2> and Latitude between <lat±0.2>
 *
 * With this hook enabled, ECellID matching is applied ONLY to the row that is the live serving
 * LTE cell (same EARFCN and PCI as the serving cell):
 *   earfcn=<EARFCN> and ECellID=<ECellID> and Longitude between <lon±0.2> and Latitude between <lat±0.2>
 * Neighbor/SCell rows keep NSG's native PCI-based matching unchanged, so their cell-DB info
 * is no longer blanked by a single serving-cell ECellID.
 *
 * The live LTE serving-cell signals are read via NSG's Property/Workspace system:
 *   - A com.qtrun.sys.a wrapper is created for the signal path
 *   - Workspace.j.h(aVar, moduleIndex) binds it to the live Property (field "d")
 *   - Property.Iterator.reverse() + value() reads the most recent value
 * Serving PCI (LTE::Serving_Cell::LTE_PCI_PCell), DL EARFCN
 * (LTE::Serving_Cell::LTE_EARFCN_PCell_DL) and ECellID (LTE::Serving_Cell::LTE_Uu_RRC_ECI)
 * are read fresh per query. If any serving signal is unavailable, or the row is not the
 * serving cell, the hook falls back to the original PCI-based WHERE clause transparently.
 *
 * In ma.a.j(): arg3 (d2) = Latitude (fVar.Z = Location_Latitude),
 *              arg4 (d10) = Longitude (fVar.Y = Location_Longitude).
 *
 * Only applies to LTE ("LTE" technology string). All other RATs are unaffected.
 * Controlled by the "NSGMod: Cell-ID matching" toggle in Settings → Experiments.
 * Default: ON.
 */
public class CellIdMatchHook {

    private static final String TAG = "NSGBandHook";

    // Signal paths for the live LTE serving cell (all 28/validity-checked at read time)
    private static final String SIGNAL_LTE_ECI    = "LTE::Serving_Cell::LTE_Uu_RRC_ECI";
    private static final String SIGNAL_LTE_PCI    = "LTE::Serving_Cell::LTE_PCI_PCell";
    private static final String SIGNAL_LTE_EARFCN = "LTE::Serving_Cell::LTE_EARFCN_PCell_DL";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    public CellIdMatchHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        try {
            Class<?> cls = ClassMapping.loadClass("ma.a", loader);
            if (cls == null) {
                Log.i(TAG, "CellIdMatchHook: ma.a not available on this flavor, skipping");
                return;
            }
            Method method = ClassMapping.getDeclaredMethod(cls, "ma.a", "j", loader,
                    String.class, Integer.class, Integer.class, Double.class, Double.class);
            method.setAccessible(true);

            xposed.hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Read args BEFORE chain.proceed() — Chain state is guaranteed valid here.
                    List<Object> args = chain.getArgs();
                    String  tech  = (String)  args.get(0);
                    Integer arfcn = (Integer)  args.get(1);
                    Integer pci   = (Integer)  args.get(2);
                    // arg3 = Latitude (fVar.Z), arg4 = Longitude (fVar.Y)
                    Double  lat   = (Double)   args.get(3);
                    Double  lon   = (Double)   args.get(4);

                    // Run the original to obtain a valid PCI-based fallback.
                    String originalWhere = (String) chain.proceed();

                    if (!SettingsToggleHook.cellIdMatchEnabled()) {
                        return originalWhere;
                    }

                    // Only intercept LTE.
                    if (!"LTE".equals(tech)) {
                        return originalWhere;
                    }

                    if (arfcn == null || lon == null || lat == null) {
                        Log.w(TAG, "null EARFCN/lon/lat — falling back");
                        return originalWhere;
                    }

                    long servingPci = readSignalLong(SIGNAL_LTE_PCI);
                    if (servingPci < 0) {
                        Log.w(TAG, "serving PCI unavailable (pci=" + servingPci + ") — falling back to PCI matching");
                        return originalWhere;
                    }
                    if (pci == null || pci.intValue() != servingPci) {
                        return originalWhere;
                    }

                    long servingArfcn = readSignalLong(SIGNAL_LTE_EARFCN);
                    if (servingArfcn < 0) {
                        Log.w(TAG, "serving EARFCN unavailable (arfcn=" + servingArfcn + ") — falling back to PCI matching");
                        return originalWhere;
                    }
                    if (arfcn == null || arfcn.intValue() != servingArfcn) {
                        return originalWhere;
                    }

                    // Read the live serving-cell ECellID from NSG's signal store.
                    long eci = readLteEci();

                    if (eci <= 0 || eci == Long.MAX_VALUE || eci == Long.MIN_VALUE) {
                        Log.w(TAG, "ECellID unavailable (eci=" + eci + ") — falling back to PCI matching");
                        return originalWhere;
                    }

                    // Build EARFCN + ECellID WHERE clause.
                    // Use Locale.US so decimal separator is always '.' regardless of device locale.
                    // Column name ECellID matches the DB schema in h7/d.java.
                    String where = "earfcn=" + arfcn
                            + " and ECellID=" + eci
                            + String.format(Locale.US,
                                " and Longitude between %.2f and %.2f"
                              + " and Latitude between %.2f and %.2f",
                                lon - 0.2, lon + 0.2, lat - 0.2, lat + 0.2);

                    return where;
                }
            });

            Log.i(TAG, "CellIdMatchHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "CellIdMatchHook install failed: " + t);
        }
    }

    /**
     * Reads the latest live value for an arbitrary NSG signal path from the
     * Property/Workspace system.
     *
     * Approach:
     *   1. Create a com.qtrun.sys.a wrapper for the signal path string
     *   2. Call Workspace.j.h(aVar, moduleIndex) for each known module index (0..3)
     *      to bind the wrapper to the live Property handle (stored in field "d")
     *   3. Create a Property.Iterator, call reverse() to seek to the latest sample,
     *      then read value() — returns Integer or Long
     *
     * Returns -1 if the value is unavailable or cannot be read. Every call performs
     * a fresh bind/read; only reflection handles are cached, never signal values.
     */
    // Cached reflection handles — resolved once (lazy, retried until success).
    // Only handles are cached; every Workspace/Property VALUE read below stays
    // fresh per call (new wrapper, live bind, new Iterator over the live data).
    private volatile boolean eciHandlesReady = false;
    private Class<?>                attrCls;
    private java.lang.reflect.Constructor<?> attrCtor;
    private Field                   attrDField;
    private Field                   wsSingletonField;
    private Method                  wsHMethod;
    private java.lang.reflect.Constructor<?> iterCtor;
    private Method                  iterReverseMethod;
    private Method                  iterEndMethod;
    private Method                  iterValueMethod;

    private synchronized void resolveEciHandles() {
        if (eciHandlesReady) return;
        try {
            Class<?> a = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            Class<?> ws = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            Class<?> prop = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iter = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);
            if (a == null || ws == null || prop == null || iter == null) return; // retry next call

            java.lang.reflect.Constructor<?> aCtor = a.getDeclaredConstructor(String.class);
            Field d = a.getDeclaredField("d");
            d.setAccessible(true);
            Field wsField = ws.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader));
            wsField.setAccessible(true);
            Method h = ClassMapping.getDeclaredMethod(ws, "com.qtrun.sys.Workspace", "h",
                    loader, a, int.class);
            h.setAccessible(true);
            java.lang.reflect.Constructor<?> itCtor = iter.getDeclaredConstructor(prop);

            attrCls           = a;
            attrCtor          = aCtor;
            attrDField        = d;
            wsSingletonField  = wsField;
            wsHMethod         = h;
            iterCtor          = itCtor;
            iterReverseMethod = iter.getDeclaredMethod("reverse");
            iterEndMethod     = iter.getDeclaredMethod("end");
            iterValueMethod   = iter.getDeclaredMethod("value");
            eciHandlesReady   = true;
        } catch (Throwable ignored) {
            // retry next call — legacy behavior resolved everything per call
        }
    }

    private long readLteEci() {
        return readSignalLong(SIGNAL_LTE_ECI);
    }

    private long readSignalLong(String path) {
        try {
            resolveEciHandles();
            if (!eciHandlesReady) {
                Log.w(TAG, "readSignalLong handles unavailable");
                return -1;
            }

            // --- 1. Create com.qtrun.sys.a wrapper for the signal path (fresh) ---
            Object aVar = attrCtor.newInstance(path);

            // --- 2. Get Workspace singleton (fresh read) ---
            Object workspace = wsSingletonField.get(null);
            if (workspace == null) {
                Log.w(TAG, "Workspace singleton is null");
                return -1;
            }

            // Try module indices 0..3 — h(com.qtrun.sys.a, int) binds the
            // Property (field "d") if the signal is live. Fresh bind per call.
            boolean bound = false;
            for (int moduleIdx = 0; moduleIdx <= 3; moduleIdx++) {
                Boolean ok = (Boolean) wsHMethod.invoke(workspace, aVar, moduleIdx);
                if (Boolean.TRUE.equals(ok) && attrDField.get(aVar) != null) {
                    bound = true;
                    break;
                }
            }
            if (!bound) {
                Log.w(TAG, "signal not live in Workspace: " + path);
                return -1;
            }

            // --- 3. Read the latest value via a fresh Property.Iterator ---
            Object property = attrDField.get(aVar);

            Object iter = iterCtor.newInstance(property);
            iterReverseMethod.invoke(iter);

            boolean end = (Boolean) iterEndMethod.invoke(iter);
            if (end) {
                Log.w(TAG, "signal Property has no samples yet: " + path);
                return -1;
            }

            Object val = iterValueMethod.invoke(iter);
            if (val == null) return -1;
            if (val instanceof Long)    return (Long) val;
            if (val instanceof Integer) return ((Integer) val).longValue();
            // Unexpected type — try toString parse
            String s = val.toString().trim();
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            return Long.parseLong(s);

        } catch (Throwable t) {
            Log.w(TAG, "readSignalLong failed: " + path + " — " + t);
            return -1;
        }
    }
}
