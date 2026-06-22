package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks d7.i$c.c(Object) to reformat NR MIMO display:
 *   - PCell: "{CSI_PORTS} x {RX_ANTENNAS}" (e.g. "2 x 4")
 *   - SCell: "{N}Rx" (e.g. "4Rx", "2Rx", "1Rx")
 *
 * The property NR_PCell_Num_Antenna_Rx is UE RX-side only. NSG's formatter
 * hardcodes symmetric "NxN" strings which misleadingly implies gNB TX
 * knowledge. This hook replaces that with honest formatting:
 *   - SCell: just show RX count (no fabricated TX side)
 *   - PCell: combine with actual gNB CSI ports (NR_PCell_Num_CSI_Ports)
 */
public class NrSaMimoFormatHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean ready = false;

    // Parent class com.qtrun.sys.a fields (inherited by d7.i$c via com.qtrun.sys.b)
    private Field sysAKeyField;   // field "a" (String key)

    // Workspace / DataSource reflection (shared pattern)
    private Field  wsJ;           // Workspace.j singleton
    private Field  wsG;           // Workspace.g — current sample key (cursor)
    private Field  wsModuleIndex; // Workspace.a (module index)
    private Field  wsDataSource;  // Workspace.c (DataSource)
    private Method dsGetProperty; // DataSource.getProperty(String, int)
    private Method propBMethod;   // Property.b(long) → Iterator
    private Method iterEndMethod; // Iterator.end()Z
    private Method iterValueMethod; // Iterator.value()Object

    public NrSaMimoFormatHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");
            sysAKeyField = sysAClass.getField("a");

            Class<?> wsClass = loader.loadClass("com.qtrun.sys.Workspace");
            Class<?> dsClass = loader.loadClass("com.qtrun.sys.DataSource");
            Class<?> propClass = loader.loadClass("com.qtrun.sys.Property");
            Class<?> iterClass = loader.loadClass("com.qtrun.sys.Property$Iterator");

            wsJ = wsClass.getField("j");
            wsG = wsClass.getDeclaredField("g"); wsG.setAccessible(true);
            wsModuleIndex = wsClass.getField("a");
            wsDataSource = wsClass.getField("c");

            dsGetProperty = dsClass.getMethod("getProperty", String.class, int.class);
            propBMethod = propClass.getMethod("b", long.class);
            iterEndMethod = iterClass.getMethod("end");
            iterValueMethod = iterClass.getMethod("value");

            ready = true;
            Log.i(TAG, "NrSaMimoFormatHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaMimoFormatHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaMimoFormatHook: skipping install");
            return;
        }
        try {
            Class<?> formatterClass = loader.loadClass("d7.i$c");
            Method cMethod = formatterClass.getMethod("c", Object.class);

            xposed.hook(cMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object thisObj = chain.getThisObject();
                    String key = (String) sysAKeyField.get(thisObj);
                    Object value = chain.getArg(0);

                    if (key == null || !(value instanceof Number)) {
                        return chain.proceed();
                    }

                    int rx = ((Number) value).intValue();

                    // SCell: show RX-only (no fabricated TX side)
                    if (key.contains("SCell_Num_Antenna_Rx")) {
                        return rx + "Rx";
                    }

                    // PCell: combine with actual gNB CSI ports
                    if (key.contains("PCell_Num_Antenna_Rx")) {
                        Integer csiPorts = readCsiPorts();
                        if (csiPorts != null) {
                            return csiPorts + " x " + rx;
                        }
                        // Fallback if CSI ports unavailable
                        return rx + "Rx";
                    }

                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaMimoFormatHook: d7.i$c.c() hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaMimoFormatHook: install failed: " + e);
        }
    }

    /** Read NR_PCell_Num_CSI_Ports from DataSource at the current cursor sample key.
     *  Uses Workspace.g (sample key) so replay/freeze mode shows the historical value
     *  that matches the MIMO value being formatted, not the latest head sample. */
    private Integer readCsiPorts() {
        try {
            Object ws = wsJ.get(null);
            if (ws == null) return null;
            long sampleKey = wsG.getLong(ws);
            int moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object ds = wsDataSource.get(ws);
            if (ds == null) return null;

            Object property = dsGetProperty.invoke(ds,
                    "NR5G::Downlink_Measurements::PCell::NR_PCell_Num_CSI_Ports",
                    moduleIndex);
            if (property == null) return null;

            Object iterator = propBMethod.invoke(property, sampleKey);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) {
                return null;
            }
            Object value = iterValueMethod.invoke(iterator);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "NrSaMimoFormatHook: readCsiPorts failed: " + e);
            return null;
        }
    }
}
