package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Inserts a "Pwr Headr." row at position 8.0, an "SRS TX" row at position 9.0,
 * and a "PUCCH TX" row at position 10.0 on the NR-SA CA Matrix UL page (h8.c),
 * shifting the original row 8 (TxPower) and all subsequent rows up by +3.0.
 *
 * Row geometry:
 *   headroom label: row=8.0, h=1.0, col=0.0, w=27.0, text="Pwr Headr.", align=0, span=1
 *   headroom bar:   row=8.0, h=1.0, col=30.0, w=34.0
 *                   key="NR5G::Uplink_Measurements::NR_Power_Headroom",
 *                   index=-1, format="%.0f dB", color based on live value
 *   SRS TX label:   row=9.0, h=1.0, col=0.0, w=27.0, text="SRS TX", align=0, span=1
 *   SRS TX bar:     row=9.0, h=1.0, col=30.0, w=34.0
 *                   key="NR5G::Uplink_Measurements::NR_Power_Tx_SRS",
 *                   index=-1, format="%.1f dBm", LegendManager auto-coloring
 *                   (same approach as NSG's own PUSCH TX row in h8.c — no .f() call)
 *   PUCCH TX label: row=10.0, h=1.0, col=0.0, w=27.0, text="PUCCH TX", align=0, span=1
 *   PUCCH TX bar:   row=10.0, h=1.0, col=30.0, w=34.0
 *                   key="NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH",
 *                   index=-1, format="%.1f dBm"
 *   SCell column (col=65) intentionally omitted — no SCell data for these in NSG.
 *
 * Hook: AFTER h8.c.l0(Context) — called once per fragment lifecycle (guarded by Y==null in v6.b.I()).
 * Hook: AFTER v6.f.a(long, DataSource, short) — dynamic color for the headroom bar.
 */
public class NrSaPucchTxRowHook {

    private static final String TAG = "NSGBandHook";

    private static final String HEADROOM_KEY = "NR5G::Uplink_Measurements::NR_Power_Headroom";
    private static final String SRS_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_SRS";
    private static final String PUCCH_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH";
    private static final String PUSCH_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_PUSCH";

    private static boolean headroomBarHookInstalled = false;
    private static boolean srsColorRedirectInstalled = false;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // k2.a builder methods
    private Method k2aRMethod; // r(float row, float h, float col, float w) → v6.e  (label)
    private Method k2aSMethod; // s(float row, float h, float col, float w) → v6.f  (bar)

    // v6.e label fields (actual bytecode names)
    private Field veF; // text   (JADX: f8116f)
    private Field veG; // align  (JADX: f8117g)
    private Field veH; // span

    // v6.f bar fields / methods
    private Field vfF8120g; // g (JADX: f8120g) — data binding
    private Field vfF8119f; // f (JADX: f8119f) — latest value object
    private Field vfF8122j; // j (JADX: f8122j) — bar color int
    private Field vfF8123k; // k (JADX: f8123k) — max value (float)
    private Method vfFMethod; // f(int color, float max) → manual bar style
    private Method vfAMethod; // a(long sampleKey, DataSource, short moduleIndex)

    // com.qtrun.sys.b / a — property binding
    private Class<?> sysBClass;
    private Field sysAFieldA; // final String key
    private Field sysAFieldB; // final String format
    private Field sysAFieldC; // int index

    // com.qtrun.sys.DataSource (used for v6.f.a signature)
    private Class<?> dataSourceClass;

    // Unsafe for allocateInstance (com.qtrun.sys.b ctor stripped by ProGuard)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // k2.a list + v6.a row field
    private Field k2aListField;
    private Field vaRowField;

    // v6.b.Y field — k2.a instance on the fragment
    private Field v6bYField;

    // LegendManager — for SRS color redirect (SRS key not in attribute2legend_map.xml)
    private Field  legendSingleton;
    private Method legendMethodC; // c(b, double) -> float  (progress)
    private Method legendMethodA; // a(b, double) -> Integer (color)
    private Object puschLegendBVar; // com.qtrun.sys.b with PUSCH key for legend lookup

    private boolean ready = false;

    public NrSaPucchTxRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            Class<?> veClass  = ClassMapping.loadClass("v6.e", loader);
            Class<?> vfClass  = ClassMapping.loadClass("v6.f", loader);
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);

            k2aRMethod = ClassMapping.getMethod(k2aClass, "k2.a", "r", loader,
                    float.class, float.class, float.class, float.class);
            k2aSMethod = ClassMapping.getMethod(k2aClass, "k2.a", "s", loader,
                    float.class, float.class, float.class, float.class);

            veF = veClass.getField("f");
            veG = veClass.getField("g");
            veH = veClass.getField("h");

            vfF8120g = vfClass.getDeclaredField("g");
            vfF8120g.setAccessible(true);
            vfF8119f = vfClass.getDeclaredField("f");
            vfF8119f.setAccessible(true);
            vfF8122j = vfClass.getDeclaredField("j");
            vfF8122j.setAccessible(true);
            vfF8123k = vfClass.getDeclaredField("k");
            vfF8123k.setAccessible(true);

            vfFMethod = vfClass.getMethod("f", int.class, float.class);
            dataSourceClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            vfAMethod = vfClass.getMethod("a", long.class, dataSourceClass, short.class);

            sysBClass = ClassMapping.loadClass("com.qtrun.sys.b", loader);
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

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

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);
            Class<?> vaClass = ClassMapping.loadClass("v6.a", loader);
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            v6bYField = v6bClass.getField("Y");

            // LegendManager for SRS color redirect.
            // NR_Power_Tx_SRS is not in attribute2legend_map.xml, so LegendManager
            // returns null for color and -1 for progress. We redirect the lookup to
            // the PUSCH key (which IS mapped to the NR_TxPower legend) so SRS uses
            // the exact same dynamic color thresholds as PUSCH/PUCCH TX.
            Class<?> legendClass = ClassMapping.loadClass("com.qtrun.legend.LegendManager", loader);
            if (legendClass != null) {
                String singletonName = ClassMapping.runtimeFieldName(
                        "com.qtrun.legend.LegendManager", "e", loader);
                try {
                    legendSingleton = legendClass.getDeclaredField(singletonName);
                } catch (NoSuchFieldException nsfe) {
                    legendSingleton = legendClass.getDeclaredField("f3783e");
                }
                legendSingleton.setAccessible(true);

                legendMethodC = ClassMapping.getDeclaredMethod(
                        legendClass, "com.qtrun.legend.LegendManager", "c", loader,
                        sysBClass, double.class);
                legendMethodC.setAccessible(true);
                legendMethodA = ClassMapping.getDeclaredMethod(
                        legendClass, "com.qtrun.legend.LegendManager", "a", loader,
                        sysBClass, double.class);
                legendMethodA.setAccessible(true);

                puschLegendBVar = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(puschLegendBVar, PUSCH_TX_KEY);
                sysAFieldB.set(puschLegendBVar, "%.1f dBm");
                sysAFieldC.set(puschLegendBVar, -1);
            }

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaPucchTxRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaPucchTxRowHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> h8cClass = ClassMapping.loadClass("h8.c", loader);
            if (h8cClass == null) {
                Log.i(TAG, "NrSaPucchTxRowHook: h8.c not available on this flavor, skipping");
                return;
            }
            Method l0Method = ClassMapping.getMethod(h8cClass, "h8.c", "l0", loader, Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        injectPucchTxRow(thiz);
                    }
                    return result;
                }
            });

            installHeadroomBarHook();
            installSrsColorRedirect();

            Log.i(TAG, "NrSaPucchTxRowHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaPucchTxRowHook: install failed: " + e);
        }
    }

    private void installHeadroomBarHook() {
        synchronized (NrSaPucchTxRowHook.class) {
            if (headroomBarHookInstalled) {
                return;
            }
            headroomBarHookInstalled = true;
            try {
                xposed.hook(vfAMethod).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        updateHeadroomBarColor(chain.getThisObject());
                        return result;
                    }
                });
                Log.i(TAG, "NrSaPucchTxRowHook: headroom bar color hook installed");
            } catch (Exception e) {
                Log.e(TAG, "NrSaPucchTxRowHook: headroom bar color hook failed: " + e);
            }
        }
    }

    /**
     * Install LegendManager.a() and .c() hooks that redirect SRS key lookups
     * to the PUSCH key. Since NR_Power_Tx_SRS is not in attribute2legend_map.xml,
     * LegendManager cannot resolve its color/progress. By redirecting to the
     * PUSCH key (which IS mapped to the NR_TxPower legend), SRS gets the exact
     * same dynamic color thresholds as PUSCH/PUCCH TX — no hardcoded values.
     */
    private void installSrsColorRedirect() {
        synchronized (NrSaPucchTxRowHook.class) {
            if (srsColorRedirectInstalled) {
                return;
            }
            if (legendMethodA == null || legendMethodC == null || puschLegendBVar == null) {
                Log.w(TAG, "NrSaPucchTxRowHook: SRS color redirect not available, skipping");
                return;
            }
            srsColorRedirectInstalled = true;
            try {
                xposed.hook(legendMethodA).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object bVar = chain.getArg(0);
                        if (bVar != null) {
                            Object keyObj = sysAFieldA.get(bVar);
                            if (keyObj != null && SRS_TX_KEY.equals(keyObj.toString())) {
                                Object legend = legendSingleton.get(null);
                                if (legend != null) {
                                    return legendMethodA.invoke(legend, puschLegendBVar, chain.getArg(1));
                                }
                            }
                        }
                        return chain.proceed();
                    }
                });

                xposed.hook(legendMethodC).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object bVar = chain.getArg(0);
                        if (bVar != null) {
                            Object keyObj = sysAFieldA.get(bVar);
                            if (keyObj != null && SRS_TX_KEY.equals(keyObj.toString())) {
                                Object legend = legendSingleton.get(null);
                                if (legend != null) {
                                    return legendMethodC.invoke(legend, puschLegendBVar, chain.getArg(1));
                                }
                            }
                        }
                        return chain.proceed();
                    }
                });

                Log.i(TAG, "NrSaPucchTxRowHook: SRS color redirect installed");
            } catch (Exception e) {
                Log.e(TAG, "NrSaPucchTxRowHook: SRS color redirect failed: " + e);
            }
        }
    }

    private void updateHeadroomBarColor(Object bar) {
        if (bar == null || vfF8120g == null || vfF8119f == null || vfF8122j == null) {
            return;
        }
        try {
            Object binding = vfF8120g.get(bar);
            if (binding == null) {
                return;
            }
            Object keyObj = sysAFieldA.get(binding);
            if (keyObj == null || !HEADROOM_KEY.equals(keyObj.toString())) {
                return;
            }

            Object valueObj = vfF8119f.get(bar);
            if (!(valueObj instanceof Number)) {
                return;
            }
            double value = ((Number) valueObj).doubleValue();

            int color;
            if (value > 30.0) {
                color = 0xFF2E7D32; // dark green
            } else if (value > 20.0) {
                color = 0xFF43A047; // green
            } else if (value > 10.0) {
                color = 0xFFFBC02D; // yellow
            } else if (value > 5.0) {
                color = 0xFFFF9800; // orange
            } else {
                color = 0xFFF44336; // red
            }

            vfF8122j.set(bar, color);
        } catch (Exception e) {
            Log.w(TAG, "NrSaPucchTxRowHook: updateHeadroomBarColor failed: " + e);
        }
    }

    private void injectPucchTxRow(Object thiz) {
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "NrSaPucchTxRowHook: this.Y is null after l0(), skipping");
                return;
            }

            // Step 1: shift all elements at row >= 8.0 by +3.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= 8.0f) {
                        vaRowField.set(elem, elemRow + 3.0f);
                    }
                }
            }

            final float headroomRow = 8.0f;
            final float srsRow      = 9.0f;
            final float pucchRow    = 10.0f;
            final float h = 1.0f;

            // Step 2: inject "Pwr Headr." label at row=8.0, h=1.0, col=0.0, w=27.0
            Object headroomLabel = k2aRMethod.invoke(k2aObj, headroomRow, h, 0.0f, 27.0f);
            if (headroomLabel != null) {
                veF.set(headroomLabel, "Pwr Headr.");
                veG.set(headroomLabel, 0);
                veH.set(headroomLabel, 1);
            }

            // Step 3: inject headroom bar at row=8.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object headroomBar = k2aSMethod.invoke(k2aObj, headroomRow, h, 30.0f, 34.0f);
            if (headroomBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, HEADROOM_KEY);
                sysAFieldB.set(prop, "%.0f dB");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(headroomBar, prop);
                // manual bar style: green default, max 30 dB
                vfFMethod.invoke(headroomBar, 0xFF43A047, 30.0f);
            }

            // Step 4: inject "SRS TX" label at row=9.0, h=1.0, col=0.0, w=27.0
            Object srsLabel = k2aRMethod.invoke(k2aObj, srsRow, h, 0.0f, 27.0f);
            if (srsLabel != null) {
                veF.set(srsLabel, "SRS TX");
                veG.set(srsLabel, 0);
                veH.set(srsLabel, 1);
            }

            // Step 5: inject SRS TX bar at row=9.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object srsBar = k2aSMethod.invoke(k2aObj, srsRow, h, 30.0f, 34.0f);
            if (srsBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, SRS_TX_KEY);
                sysAFieldB.set(prop, "%.1f dBm");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(srsBar, prop);
                // No .f(color, max) call — LegendManager auto-coloring, same as NSG's PUSCH TX row
            }

            // Step 6: inject "PUCCH TX" label at row=10.0, h=1.0, col=0.0, w=27.0
            Object pucchLabel = k2aRMethod.invoke(k2aObj, pucchRow, h, 0.0f, 27.0f);
            if (pucchLabel != null) {
                veF.set(pucchLabel, "PUCCH TX");
                veG.set(pucchLabel, 0);
                veH.set(pucchLabel, 1);
            }

            // Step 7: inject PUCCH TX bar at row=10.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object pucchBar = k2aSMethod.invoke(k2aObj, pucchRow, h, 30.0f, 34.0f);
            if (pucchBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(prop, PUCCH_TX_KEY);
                sysAFieldB.set(prop, "%.1f dBm");
                sysAFieldC.set(prop, -1);
                vfF8120g.set(pucchBar, prop);
                // No .f(color, max) call — LegendManager auto-coloring via f8120g
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaPucchTxRowHook: injectPucchTxRow failed: " + e);
        }
    }
}
