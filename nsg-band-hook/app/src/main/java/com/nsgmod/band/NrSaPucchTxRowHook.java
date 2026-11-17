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
 * and a "PUCCH TX" row at position 10.0 on the NR-SA CA Matrix UL page (eb0),
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
 *                   (same approach as NSG's own PUSCH TX row in eb0 — no .f() call)
 *   PUCCH TX label: row=10.0, h=1.0, col=0.0, w=27.0, text="PUCCH TX", align=0, span=1
 *   PUCCH TX bar:   row=10.0, h=1.0, col=30.0, w=34.0
 *                   key="NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH",
 *                   index=-1, format="%.1f dBm"
 *   SCell column (col=65) intentionally omitted — no SCell data for these in NSG.
 *
 * Hook: AFTER eb0.h0(Context) — called once per fragment lifecycle (guarded by X==null in yg0.E()).
 * Hook: AFTER ch0.a(long, DataSource, short) — dynamic color for the headroom bar.
 */
public class NrSaPucchTxRowHook {

    private static final String TAG = "NSGBandHook";

    private static final String ATTR_BASE = "l8";   // attribute base: key a, format b, index c
    private static final String ATTR_MAIN = "m8";   // attribute subclass (instantiated via Unsafe)
    private static final String LEGEND_MANAGER = "com.qtrun.legend.LegendManager";

    private static final String HEADROOM_KEY = "NR5G::Uplink_Measurements::NR_Power_Headroom";
    private static final String SRS_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_SRS";
    private static final String PUCCH_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH";
    private static final String PUSCH_TX_KEY = "NR5G::Uplink_Measurements::NR_Power_Tx_PUSCH";

    private static boolean headroomBarHookInstalled = false;
    private static boolean srsColorRedirectInstalled = false;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // r31 grid builder methods
    private Method builderLabelMethod; // M(float row, float h, float col, float w) → bh0 (label)
    private Method builderBarMethod;   // N(float row, float h, float col, float w) → ch0 (bar)

    // bh0 label fields
    private Field labelText;  // f
    private Field labelAlign; // g
    private Field labelSpan;  // h

    // ch0 bar fields / methods
    private Field barBinding;   // g (m8) — data binding
    private Field barValue;     // f — latest value object
    private Field barColor;     // i — bar color int
    private Method barStyleMethod; // f(int color, float max) → manual bar style
    private Method barUpdateMethod; // a(long sampleKey, DataSource, short moduleIndex)

    // l8 / m8 — property binding
    private Class<?> attrClass;
    private Field attrKey;    // final String a
    private Field attrFormat; // final String b
    private Field attrIndex;  // int c

    // Unsafe for allocateInstance (m8 ctor is not usable here)
    private Object unsafe;
    private Method unsafeAllocateInstance;

    // r31 element list + xg0 row field
    private Field builderListField;
    private Field elementRowField;

    // yg0.X field — r31 builder on the fragment
    private Field pageBuilderField;

    // LegendManager — for SRS color redirect (SRS key not in attribute2legend_map.xml)
    private Method legendMethodC; // b(m8, double) -> float  (progress)
    private Method legendMethodA; // a(m8, double) -> Integer (color)
    private Object puschLegendBVar; // m8 with PUSCH key for legend lookup

    private boolean ready = false;

    public NrSaPucchTxRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> labelClass   = Nsg.load(loader, Nsg.GRID_LABEL);
            Class<?> barClass     = Nsg.load(loader, Nsg.GRID_VALUE);
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);
            Class<?> dataSourceClass = Nsg.load(loader, Nsg.DATA_SOURCE);
            attrClass = Nsg.load(loader, ATTR_MAIN);
            Class<?> attrBaseClass = Nsg.load(loader, ATTR_BASE);

            builderLabelMethod = builderClass.getMethod("M",
                    float.class, float.class, float.class, float.class);
            builderBarMethod = builderClass.getMethod("N",
                    float.class, float.class, float.class, float.class);

            labelText = labelClass.getField("f");
            labelAlign = labelClass.getField("g");
            labelSpan = labelClass.getField("h");

            barBinding = barClass.getDeclaredField("g");
            barBinding.setAccessible(true);
            barValue = barClass.getDeclaredField("f");
            barValue.setAccessible(true);
            barColor = barClass.getDeclaredField("i");
            barColor.setAccessible(true);

            barStyleMethod = barClass.getMethod("f", int.class, float.class);
            barUpdateMethod = barClass.getMethod("a", long.class, dataSourceClass, short.class);

            attrKey = attrBaseClass.getDeclaredField("a");
            attrFormat = attrBaseClass.getDeclaredField("b");
            attrIndex = attrBaseClass.getDeclaredField("c");
            attrKey.setAccessible(true);
            attrFormat.setAccessible(true);
            attrIndex.setAccessible(true);

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
            elementRowField = elementClass.getDeclaredField("b");
            elementRowField.setAccessible(true);

            pageBuilderField = pageClass.getField("X");

            // LegendManager for SRS color redirect.
            // NR_Power_Tx_SRS is not in attribute2legend_map.xml, so LegendManager
            // returns null for color and -1 for progress. We redirect the lookup to
            // the PUSCH key (which IS mapped to the NR_TxPower legend) so SRS uses
            // the exact same dynamic color thresholds as PUSCH/PUCCH TX.
            Class<?> legendClass = Nsg.load(loader, LEGEND_MANAGER);
            legendMethodC = legendClass.getDeclaredMethod("b", attrClass, double.class);
            legendMethodC.setAccessible(true);
            legendMethodA = legendClass.getDeclaredMethod("a", attrClass, double.class);
            legendMethodA.setAccessible(true);

            puschLegendBVar = unsafeAllocateInstance.invoke(unsafe, attrClass);
            attrKey.set(puschLegendBVar, PUSCH_TX_KEY);
            attrFormat.set(puschLegendBVar, "%.1f dBm");
            attrIndex.set(puschLegendBVar, -1);

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
            Class<?> pageClass = Nsg.load(loader, Nsg.PAGE_SA_CA_MATRIX_UL);
            Method buildMethod = pageClass.getMethod("h0", Context.class);

            xposed.hook(buildMethod).intercept(new Hooker() {
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
                xposed.hook(barUpdateMethod).intercept(new Hooker() {
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
     * Install LegendManager.a() and .b() hooks that redirect SRS key lookups
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
            srsColorRedirectInstalled = true;
            try {
                xposed.hook(legendMethodA).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object bVar = chain.getArg(0);
                        if (bVar != null) {
                            Object keyObj = attrKey.get(bVar);
                            if (keyObj != null && SRS_TX_KEY.equals(keyObj.toString())) {
                                return chain.proceed(new Object[]{puschLegendBVar, chain.getArg(1)});
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
                            Object keyObj = attrKey.get(bVar);
                            if (keyObj != null && SRS_TX_KEY.equals(keyObj.toString())) {
                                return chain.proceed(new Object[]{puschLegendBVar, chain.getArg(1)});
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
        if (bar == null) {
            return;
        }
        try {
            Object binding = barBinding.get(bar);
            if (binding == null) {
                return;
            }
            Object keyObj = attrKey.get(binding);
            if (keyObj == null || !HEADROOM_KEY.equals(keyObj.toString())) {
                return;
            }

            Object valueObj = barValue.get(bar);
            if (!(valueObj instanceof Number)) {
                return;
            }
            double value = ((Number) valueObj).doubleValue();

            int color;
            if (value > 35.0) {
                color = 0xFF1B5E20;
            } else if (value > 25.0) {
                color = 0xFF2E7D32;
            } else if (value > 15.0) {
                color = 0xFF43A047;
            } else if (value > 10.0) {
                color = 0xFFFBC02D;
            } else if (value > 5.0) {
                color = 0xFFFF9800;
            } else {
                color = 0xFFF44336;
            }

            barColor.set(bar, color);
        } catch (Exception e) {
            Log.w(TAG, "NrSaPucchTxRowHook: updateHeadroomBarColor failed: " + e);
        }
    }

    private void injectPucchTxRow(Object thiz) {
        try {
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "NrSaPucchTxRowHook: this.X is null after h0(), skipping");
                return;
            }

            // Step 1: shift all elements at row >= 8.0 by +3.0
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) builderListField.get(builder);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) elementRowField.get(elem);
                    if (elemRow >= 8.0f) {
                        elementRowField.set(elem, elemRow + 3.0f);
                    }
                }
            }

            final float headroomRow = 8.0f;
            final float srsRow      = 9.0f;
            final float pucchRow    = 10.0f;
            final float h = 1.0f;

            // Step 2: inject "Pwr Headr." label at row=8.0, h=1.0, col=0.0, w=27.0
            Object headroomLabel = builderLabelMethod.invoke(builder, headroomRow, h, 0.0f, 27.0f);
            if (headroomLabel != null) {
                labelText.set(headroomLabel, "Pwr Headr.");
                labelAlign.set(headroomLabel, 0);
                labelSpan.set(headroomLabel, 1);
            }

            // Step 3: inject headroom bar at row=8.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object headroomBar = builderBarMethod.invoke(builder, headroomRow, h, 30.0f, 34.0f);
            if (headroomBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, HEADROOM_KEY);
                attrFormat.set(prop, "%.0f dB");
                attrIndex.set(prop, -1);
                barBinding.set(headroomBar, prop);
                barStyleMethod.invoke(headroomBar, 0xFF2E7D32, 40.0f);
            }

            // Step 4: inject "SRS TX" label at row=9.0, h=1.0, col=0.0, w=27.0
            Object srsLabel = builderLabelMethod.invoke(builder, srsRow, h, 0.0f, 27.0f);
            if (srsLabel != null) {
                labelText.set(srsLabel, "SRS TX");
                labelAlign.set(srsLabel, 0);
                labelSpan.set(srsLabel, 1);
            }

            // Step 5: inject SRS TX bar at row=9.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object srsBar = builderBarMethod.invoke(builder, srsRow, h, 30.0f, 34.0f);
            if (srsBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, SRS_TX_KEY);
                attrFormat.set(prop, "%.1f dBm");
                attrIndex.set(prop, -1);
                barBinding.set(srsBar, prop);
                // No .f(color, max) call — LegendManager auto-coloring, same as NSG's PUSCH TX row
            }

            // Step 6: inject "PUCCH TX" label at row=10.0, h=1.0, col=0.0, w=27.0
            Object pucchLabel = builderLabelMethod.invoke(builder, pucchRow, h, 0.0f, 27.0f);
            if (pucchLabel != null) {
                labelText.set(pucchLabel, "PUCCH TX");
                labelAlign.set(pucchLabel, 0);
                labelSpan.set(pucchLabel, 1);
            }

            // Step 7: inject PUCCH TX bar at row=10.0, h=1.0, col=30.0, w=34.0 (PCell only)
            Object pucchBar = builderBarMethod.invoke(builder, pucchRow, h, 30.0f, 34.0f);
            if (pucchBar != null) {
                Object prop = unsafeAllocateInstance.invoke(unsafe, attrClass);
                attrKey.set(prop, PUCCH_TX_KEY);
                attrFormat.set(prop, "%.1f dBm");
                attrIndex.set(prop, -1);
                barBinding.set(pucchBar, prop);
                // No .f(color, max) call — LegendManager auto-coloring via the binding
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaPucchTxRowHook: injectPucchTxRow failed: " + e);
        }
    }
}
