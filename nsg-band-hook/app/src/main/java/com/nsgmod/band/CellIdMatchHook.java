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
 * With this hook enabled (ECellID matching):
 *   earfcn=<EARFCN> and ECellID=<ECellID> and Longitude between <lon±0.2> and Latitude between <lat±0.2>
 *
 * The live LTE serving-cell ECellID is read via NSG's Property/Workspace system:
 *   - A com.qtrun.sys.a wrapper is created for "LTE::Serving_Cell::LTE_Uu_RRC_ECI"
 *   - Workspace.j.h(aVar, moduleIndex) binds it to the live Property (field "d")
 *   - Property.Iterator.reverse() + value() reads the most recent value
 * If no valid ECellID is available (signal absent, value null/-1, or any error), the hook
 * falls back to the original PCI-based WHERE clause transparently.
 *
 * In ma.a.j(): arg3 (d2) = Latitude (fVar.Z = Location_Latitude),
 *              arg4 (d10) = Longitude (fVar.Y = Location_Longitude).
 *
 * Only applies to LTE ("LTE" technology string). All other RATs are unaffected.
 * Controlled by the "NSGMod: Cell-ID matching" toggle in Settings → Experiments.
 * Default: OFF.
 */
public class CellIdMatchHook {

    private static final String TAG = "NSGBandHook";

    // Signal path for the live LTE serving-cell E-UTRAN Cell Identity (28-bit)
    private static final String SIGNAL_LTE_ECI = "LTE::Serving_Cell::LTE_Uu_RRC_ECI";

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
     * Reads the live LTE serving-cell ECellID from NSG's Property/Workspace system.
     *
     * Approach:
     *   1. Create a com.qtrun.sys.a wrapper for the signal path string
     *   2. Call Workspace.j.h(aVar, moduleIndex) for each known module index (0..3)
     *      to bind the wrapper to the live Property handle (stored in field "d")
     *   3. Create a Property.Iterator, call reverse() to seek to the latest sample,
     *      then read value() — returns Integer or Long
     *
     * Returns -1 if the value is unavailable or cannot be read.
     */
    private long readLteEci() {
        try {
            // --- 1. Create com.qtrun.sys.a wrapper for the signal path ---
            Class<?> attrCls = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            Object aVar = attrCls.getDeclaredConstructor(String.class)
                    .newInstance(SIGNAL_LTE_ECI);

            // Field "d" holds the Property handle (set by Workspace.h())
            Field dField = attrCls.getDeclaredField("d");
            dField.setAccessible(true);

            // --- 2. Get Workspace singleton (static field "j") ---
            Class<?> wsCls = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            Field wsField = wsCls.getDeclaredField("j");
            wsField.setAccessible(true);
            Object workspace = wsField.get(null);
            if (workspace == null) {
                Log.w(TAG, "Workspace singleton is null");
                return -1;
            }

            // h(com.qtrun.sys.a, int) — binds the Property if the signal is live
            Method hMethod = wsCls.getDeclaredMethod("h", attrCls, int.class);
            hMethod.setAccessible(true);

            // Try module indices 0..3
            boolean bound = false;
            for (int moduleIdx = 0; moduleIdx <= 3; moduleIdx++) {
                Boolean ok = (Boolean) hMethod.invoke(workspace, aVar, moduleIdx);
                if (Boolean.TRUE.equals(ok) && dField.get(aVar) != null) {
                    bound = true;
                    break;
                }
            }
            if (!bound) {
                Log.w(TAG, "ECI signal not live in Workspace");
                return -1;
            }

            // --- 3. Read the latest value via Property.Iterator ---
            Object property = dField.get(aVar);
            Class<?> propCls = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterCls = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);

            Object iter = iterCls.getDeclaredConstructor(propCls).newInstance(property);
            iterCls.getDeclaredMethod("reverse").invoke(iter);

            boolean end = (Boolean) iterCls.getDeclaredMethod("end").invoke(iter);
            if (end) {
                Log.w(TAG, "ECI Property has no samples yet");
                return -1;
            }

            Object val = iterCls.getDeclaredMethod("value").invoke(iter);
            if (val == null) return -1;
            if (val instanceof Long)    return (Long) val;
            if (val instanceof Integer) return ((Integer) val).longValue();
            // Unexpected type — try toString parse
            String s = val.toString().trim();
            if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseLong(s.substring(2), 16);
            return Long.parseLong(s);

        } catch (Throwable t) {
            Log.w(TAG, "readLteEci failed: " + t);
            return -1;
        }
    }
}
