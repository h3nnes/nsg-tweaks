package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "CSI SNR" row (PCell only) to the NR-SA CA Matrix DL page (db0).
 *
 * v4.8.14 targets:
 *   db0  NR-SA CA Matrix DL page; k0() builds the grid (three layout paths by SCell count,
 *        all ending with yg0.g0(r31)); Z = carrier count (int)
 *   yg0  grid page fragment; static g0(r31) stores the builder
 *   r31  grid builder; b = ArrayList of grid elements; M(row,h,col,w) → bh0 label,
 *        N(row,h,col,w) → ch0 bar
 *   xg0  grid element base; b = row position (float)
 *   bh0  label cell: f = text, g = alignment, h = span
 *   ch0  bar cell: g = m8 attribute binding
 *   l8 / m8  attribute binding (a = key, b = format, c = index); m8 is allocated via Unsafe
 *   LegendManager  e = singleton; a(m8,double) → Integer color, b(m8,double) → float progress
 *
 * Strategy:
 *   Hook db0.k0() with a before+after wrapper that sets a ThreadLocal flag.
 *   Hook yg0.g0(r31) — inject the CSI SNR row into the r31 when flag is set.
 *
 * Row layout (float xg0.b = row position, used via Math.floor for layout):
 *   20/21 = SS-SNR (existing)
 *   21.5  = CSI SNR injected — floors to row 21, placed at end of ArrayList
 *           but rows are layout-positioned by xg0.b not insertion order
 *   22/23 = RBs (existing)
 */
public class NrSaCsiSnrRowHook {

    private static final String TAG = "NSGBandHook";

    private static final String PDSCH_SINR_KEY =
            "NR5G::Downlink_Measurements::NR_PDSCH_SINR";
    private static final String SS_SINR_KEY =
            "NR5G::Downlink_Measurements::NR_SS_SINR";

    static final ThreadLocal<Integer> carrierCountInO0 = new ThreadLocal<>();

    /**
     * Maps r31 builder instances created inside db0.k0() to their carrier count.
     * Populated by the yg0.g0 hook (which fires inside k0()).
     * Used by NrSaCellColorHook to identify SA CA Matrix builders when yg0.E() fires later.
     * WeakHashMap so r31 objects are not prevented from being GC'd.
     */
    static final Map<Object, Integer> saK2aCarriers =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private static final String ATTR_L8 = "l8";
    private static final String ATTR_M8 = "m8";
    private static final String LEGEND  = "com.qtrun.legend.LegendManager";

    private Method k2aRMethod; // r31.M: add label cell
    private Method k2aSMethod; // r31.N: add bar cell

    private Field veText; // bh0.f: label text
    private Field veAlign; // bh0.g: alignment
    private Field veH;      // bh0.h: span

    private Field vfBinding; // ch0.g: attribute binding

    private Class<?> sysBClass; // m8
    private Field sysAFieldA; // l8.a: key string, final
    private Field sysAFieldB; // l8.b: format string, final
    private Field sysAFieldC; // l8.c: index int
    private Object unsafe;    // sun.misc.Unsafe instance, held as Object to avoid compile dep
    private java.lang.reflect.Method unsafeAllocateInstance;

    private Field  legendSingleton;
    private Method legendMethodC;
    private Method legendMethodA;
    private Object ssSinrLegendBVar;
    private static boolean pdschColorRedirectInstalled = false;

    // db0.Z = carrier count (2 = PCell only/1SCell, 3 = 2SCells, ...)
    private Field h8bCarrierCountField;

    // r31.b: ArrayList of grid elements
    private Field k2aListField;
    // xg0.b: row position float
    private Field vaRowField;

    private boolean ready = false;

    public NrSaCsiSnrRowHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass  = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> veClass   = Nsg.load(loader, Nsg.GRID_LABEL);
            Class<?> vfClass   = Nsg.load(loader, Nsg.GRID_VALUE);

            k2aRMethod = k2aClass.getMethod("M", float.class, float.class, float.class, float.class);
            k2aSMethod = k2aClass.getMethod("N", float.class, float.class, float.class, float.class);

            veText = veClass.getField("f");
            veAlign = veClass.getField("g");
            veH      = veClass.getField("h");

            // ch0.g field type is m8; R8 stripped m8's constructor.
            // Use Unsafe.allocateInstance(m8.class) to create a real m8 instance without a ctor,
            // then set the inherited final fields from l8 via reflection.
            vfBinding = vfClass.getDeclaredField("g");
            vfBinding.setAccessible(true);

            sysBClass = Nsg.load(loader, ATTR_M8);
            Class<?> sysAClass = Nsg.load(loader, ATTR_L8);
            sysAFieldA = sysAClass.getDeclaredField("a"); // final String key
            sysAFieldB = sysAClass.getDeclaredField("b"); // final String format
            sysAFieldC = sysAClass.getDeclaredField("c"); // int index
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);

            // Obtain sun.misc.Unsafe via reflection (not importable in Android SDK compile)
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

            Class<?> h8bClass = Nsg.load(loader, Nsg.PAGE_SA_CA_MATRIX_DL);
            h8bCarrierCountField = h8bClass.getDeclaredField("Z");
            h8bCarrierCountField.setAccessible(true);

            k2aListField = k2aClass.getDeclaredField("b");
            k2aListField.setAccessible(true);
            Class<?> vaClass = Nsg.load(loader, Nsg.GRID_ELEMENT);
            vaRowField = vaClass.getDeclaredField("b");
            vaRowField.setAccessible(true);

            // LegendManager for PDSCH SINR color redirect.
            // NR_PDSCH_SINR is not in attribute2legend_map.xml, so LegendManager
            // returns null for color and -1 for progress. We redirect the lookup to
            // NR_SS_SINR (which IS mapped to the NR SNR legend) so PDSCH SINR uses
            // the exact same dynamic color thresholds as SS-SNR/CSI-SNR.
            Class<?> legendClass = Nsg.load(loader, LEGEND);
            if (legendClass != null) {
                legendSingleton = legendClass.getDeclaredField("e"); // LegendManager.e: singleton
                legendSingleton.setAccessible(true);

                legendMethodC = legendClass.getDeclaredMethod("b", sysBClass, double.class); // b(m8,double) → float progress
                legendMethodC.setAccessible(true);
                legendMethodA = legendClass.getDeclaredMethod("a", sysBClass, double.class); // a(m8,double) → Integer color
                legendMethodA.setAccessible(true);

                ssSinrLegendBVar = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(ssSinrLegendBVar, SS_SINR_KEY);
                sysAFieldB.set(ssSinrLegendBVar, "%.1f dB");
                sysAFieldC.set(ssSinrLegendBVar, -1);
            }

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCsiSnrRowHook: skipping install — reflection not ready");
            return;
        }
        installCarrierFlagHook();
        installGridPageHook();
        if (SettingsToggleHook.nrPdschSnrEnabled()) {
            installPdschColorRedirect();
        }
        Log.i(TAG, "NrSaCsiSnrRowHook: installed");
    }

    // -----------------------------------------------------------------------
    // Hook 1: db0.k0() — set/clear ThreadLocal flag around execution
    // -----------------------------------------------------------------------

    private void installCarrierFlagHook() {
        try {
            Class<?> h8bClass = Nsg.load(loader, Nsg.PAGE_SA_CA_MATRIX_DL);
            Method   o0Method = h8bClass.getDeclaredMethod("k0"); // db0.k0: rebuild grid

            xposed.hook(o0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int carriers = -1;
                    try {
                        carriers = (int) h8bCarrierCountField.get(chain.getThisObject());
                    } catch (Exception e) {
                        Log.w(TAG, "NrSaCsiSnrRowHook: could not read carrier count: " + e);
                    }
                    carrierCountInO0.set(carriers);
                    try {
                        return chain.proceed();
                    } finally {
                        carrierCountInO0.remove();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: db0.k0 flag hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: static yg0.g0(r31) — inject when called from db0.k0()
    // -----------------------------------------------------------------------

    private void installGridPageHook() {
        try {
            Class<?> v6bClass = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> k2aClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Method   k0Method = v6bClass.getDeclaredMethod("g0", k2aClass); // yg0.g0(r31)

            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object k2aArg = chain.getArg(0);
                    Integer carriers = carrierCountInO0.get();
                    boolean inO0 = carriers != null;
                    if (inO0 && k2aArg != null) {
                        // Record this r31 as belonging to the SA CA Matrix screen
                        saK2aCarriers.put(k2aArg, carriers);
                        // carriers == 2: 1 SCell (Path A) — RSRP shift +2.0
                        // carriers == 3: 2 SCells (Path B) — RSRP shift +4.0
                        // carriers >= 4: 3 SCells (Path C) — RSRP shift +3.0
                        // row param is used to compute insertRow = ceil(row)
                        float row;
                        if (carriers >= 4) {
                            row = 24.5f;  // Path C: SNR at 20+3=23, insert before RBs at 24
                        } else if (carriers == 3) {
                            row = 25.5f;  // Path B: SNR at 20+4=24, insert before RBs at 25
                        } else {
                            row = 16.5f;  // Path A: SNR at 14+2=16, insert before RBs at 17
                        }
                        boolean pdschEnabled = SettingsToggleHook.nrPdschSnrEnabled();
                        if (pdschEnabled) {
                            installPdschColorRedirect();
                        }
                        injectCsiSnrRow(k2aArg, row, carriers != null ? carriers : 2, pdschEnabled);
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCsiSnrRowHook: yg0.g0 hook failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // LegendManager redirect for PDSCH SINR color coding
    // -----------------------------------------------------------------------

    private void installPdschColorRedirect() {
        synchronized (NrSaCsiSnrRowHook.class) {
            if (pdschColorRedirectInstalled) return;
            if (legendMethodA == null || legendMethodC == null || ssSinrLegendBVar == null) {
                Log.w(TAG, "NrSaCsiSnrRowHook: PDSCH color redirect not available, skipping");
                return;
            }
            pdschColorRedirectInstalled = true;
            try {
                xposed.hook(legendMethodA).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object bVar = chain.getArg(0);
                        if (bVar != null) {
                            Object keyObj = sysAFieldA.get(bVar);
                            if (keyObj != null && PDSCH_SINR_KEY.equals(keyObj.toString())) {
                                return chain.proceed(new Object[]{ssSinrLegendBVar, chain.getArg(1)});
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
                            if (keyObj != null && PDSCH_SINR_KEY.equals(keyObj.toString())) {
                                return chain.proceed(new Object[]{ssSinrLegendBVar, chain.getArg(1)});
                            }
                        }
                        return chain.proceed();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "NrSaCsiSnrRowHook: PDSCH color redirect hook failed: " + e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Injection: append CSI SNR + PDSCH SINR rows to r31 builder
    // -----------------------------------------------------------------------

    private void injectCsiSnrRow(Object k2aObj, float row, int carriers, boolean pdschEnabled) {
        try {
            float insertRow = (float) Math.ceil(row); // 14.5 → 15, 21.5 → 22

            // In the 3-SCell inline path (carriers == 3), labels use height 2.0f and
            // PCell bars use a +0.3f row offset with height 1.4f — match that exactly.
            boolean isInline3 = (carriers == 3);
            float labelHeight = isInline3 ? 2.0f : 1.0f;
            float barRowOffset = isInline3 ? 0.3f : 0.0f;
            float barHeight    = isInline3 ? 1.4f : 1.0f;
            java.util.ArrayList<?> list = (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= insertRow) {
                        vaRowField.set(elem, elemRow + labelHeight);
                    }
                }
            }

            // Inject CSI SNR label at insertRow, col 0..27
            Object labelElem = k2aRMethod.invoke(k2aObj, insertRow, labelHeight, 0.0f, 27.0f);
            if (labelElem != null) {
                veText.set(labelElem, "CSI SNR");
                veAlign.set(labelElem, 0);
                veH.set(labelElem, 1);
            }

            // Inject CSI SNR PCell bar at same row (+offset), col 30..34
            Object barElem = k2aSMethod.invoke(k2aObj, insertRow + barRowOffset, barHeight, 30.0f, 34.0f);
            if (barElem != null) {
                Object propBinding = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(propBinding, "NR5G::Downlink_Measurements::NR_CSI_SINR");
                sysAFieldB.set(propBinding, "%.1f dB");
                sysAFieldC.set(propBinding, -1);
                vfBinding.set(barElem, propBinding);
            }

            // ── PDSCH SINR row (below CSI SNR, above RBs) — gated by toggle ──
            if (!pdschEnabled) {
                return;
            }

            float pdschRow = insertRow + labelHeight;

            // Shift existing rows >= pdschRow by another labelHeight
            if (list != null) {
                for (Object elem : list) {
                    float elemRow = (float) vaRowField.get(elem);
                    if (elemRow >= pdschRow) {
                        vaRowField.set(elem, elemRow + labelHeight);
                    }
                }
            }

            // Inject PDSCH SINR label
            Object pdschLabel = k2aRMethod.invoke(k2aObj, pdschRow, labelHeight, 0.0f, 27.0f);
            if (pdschLabel != null) {
                veText.set(pdschLabel, "PDSCH SINR");
                veAlign.set(pdschLabel, 0);
                veH.set(pdschLabel, 1);
            }

            // Inject PDSCH SINR PCell bar
            Object pdschBar = k2aSMethod.invoke(k2aObj, pdschRow + barRowOffset, barHeight, 30.0f, 34.0f);
            if (pdschBar != null) {
                Object pdschProp = unsafeAllocateInstance.invoke(unsafe, sysBClass);
                sysAFieldA.set(pdschProp, PDSCH_SINR_KEY);
                sysAFieldB.set(pdschProp, "%.1f dB");
                sysAFieldC.set(pdschProp, -1);
                vfBinding.set(pdschBar, pdschProp);
            }

        } catch (Exception e) {
            Log.w(TAG, "NrSaCsiSnrRowHook: injectCsiSnrRow failed: " + e);
        }
    }
}
