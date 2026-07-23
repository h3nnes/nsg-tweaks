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
 *   - SCell: "- x {RX_ANTENNAS}" (e.g. "- x 4", "- x 2")
 *
 * The property NR_PCell_Num_Antenna_Rx is UE RX-side only. NSG's formatter
 * hardcodes symmetric "NxN" strings which misleadingly implies gNB TX
 * knowledge. This hook replaces that with honest formatting:
 *   - SCell: show RX count with a dash for the unknown TX side ("- x N")
 *   - PCell: combine with actual gNB CSI ports (NR_PCell_Num_CSI_Ports)
 */
public class NrSaMimoFormatHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean ready = false;

    // Parent class com.qtrun.sys.a fields (inherited by d7.i$c via com.qtrun.sys.b)
    private Field sysAKeyField;   // field "a" (String key)

    // Context captured from v6.g.a(sampleKey, DataSource, moduleIndex) while it formats
    // a label. This is the exact sample key / module index the formatter is rendering,
    // which may differ from Workspace.g in replay/freeze mode.
    private static final ThreadLocal<FormatContext> formatContext = new ThreadLocal<>();

    private static final class FormatContext {
        final long   sampleKey;
        final int    moduleIndex;
        final Object dataSource;

        FormatContext(long sampleKey, int moduleIndex, Object dataSource) {
            this.sampleKey = sampleKey;
            this.moduleIndex = moduleIndex;
            this.dataSource = dataSource;
        }
    }

    // Workspace / DataSource reflection (shared pattern)
    private Field  wsJ;           // Workspace.j singleton
    private Field  wsG;           // Workspace.g — current sample key (cursor)
    private Field  wsModuleIndex; // Workspace.a (module index)
    private Field  wsDataSource;  // Workspace.c (DataSource)
    private Class<?> dsClass;     // com.qtrun.sys.DataSource class
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
            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            sysAKeyField = sysAClass.getField("a");

            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Class<?> propClass = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterClass = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);

            String wsJName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader);
            String wsGName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "g", loader);
            String wsAName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "a", loader);
            String wsCName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "c", loader);
            wsJ = wsClass.getField(wsJName);
            wsG = wsClass.getDeclaredField(wsGName); wsG.setAccessible(true);
            wsModuleIndex = wsClass.getField(wsAName);
            wsDataSource = wsClass.getField(wsCName);

            dsGetProperty = dsClass.getMethod("getProperty", String.class, int.class);
            propBMethod = propClass.getMethod("b", long.class);
            iterEndMethod = iterClass.getMethod("end");
            iterValueMethod = iterClass.getMethod("value");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaMimoFormatHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaMimoFormatHook: skipping install");
            return;
        }
        installFormatterHook();
        installGridValueLabelHook();
    }

    private void installFormatterHook() {
        try {
            Class<?> formatterClass = ClassMapping.loadClass("d7.i$c", loader);
            if (formatterClass == null) {
                Log.i(TAG, "NrSaMimoFormatHook: d7.i$c not available, skipping formatter hook");
                return;
            }
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

                    // SCell: show RX count with a dash for the unknown TX side.
                    if (key.contains("SCell_Num_Antenna_Rx")) {
                        return "- x " + rx;
                    }

                    // PCell: combine with actual gNB CSI ports
                    if (key.contains("PCell_Num_Antenna_Rx")) {
                        Integer csiPorts = readCsiPorts();
                        if (csiPorts != null) {
                            return csiPorts + " x " + rx;
                        }
                        // Fallback if CSI ports unavailable
                        return "- x " + rx;
                    }

                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaMimoFormatHook: formatter hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaMimoFormatHook: install d7.i$c failed: " + e);
        }
    }

    private void installGridValueLabelHook() {
        try {
            Class<?> gridValueLabelClass = ClassMapping.loadClass("v6.g", loader);
            Method aMethod = gridValueLabelClass.getMethod("a", long.class, dsClass, short.class);

            xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object sampleKeyObj = chain.getArg(0);
                    Object ds = chain.getArg(1);
                    Object moduleIndexObj = chain.getArg(2);

                    if (sampleKeyObj instanceof Number && moduleIndexObj instanceof Number) {
                        formatContext.set(new FormatContext(
                                ((Number) sampleKeyObj).longValue(),
                                ((Number) moduleIndexObj).intValue(),
                                ds));
                    }

                    try {
                        return chain.proceed();
                    } finally {
                        formatContext.remove();
                    }
                }
            });
            Log.i(TAG, "NrSaMimoFormatHook: grid value label hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaMimoFormatHook: install v6.g failed: " + e);
        }
    }

    /** Read NR_PCell_Num_CSI_Ports using the exact sample key being formatted by v6.g.a.
     *  Falls back to the current Workspace cursor if not called from v6.g.a. */
    private Integer readCsiPorts() {
        FormatContext ctx = formatContext.get();

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
                if (ws == null) {
                    return null;
                }
                sampleKey = wsG.getLong(ws);
                moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
                ds = wsDataSource.get(ws);
            } catch (Exception e) {
                Log.w(TAG, "NrSaMimoFormatHook: readCsiPorts fallback failed: " + e);
                return null;
            }
        }

        if (ds == null) {
            return null;
        }

        try {
            Object property = dsGetProperty.invoke(ds,
                    "NR5G::Downlink_Measurements::PCell::NR_PCell_Num_CSI_Ports",
                    moduleIndex);
            if (property == null) {
                return null;
            }

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
