package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks u00.c(Object) (R8-merged attribute formatter, switch on field e) to reformat NR MIMO display:
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

    private static final String ATTR_BASE = "l8";       // attribute base class: key field a
    private static final String FORMATTER = "u00";      // m8 subclass with merged formatters; c(Object)

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean ready = false;

    // l8.a (String key), inherited by u00 via m8
    private Field attrKeyField;

    // Context captured from dh0.a(sampleKey, DataSource, moduleIndex) while it formats
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
    private Field  wsJ;           // Workspace.j enum singleton
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
            attrKeyField = Nsg.load(loader, ATTR_BASE).getField("a");

            Class<?> wsClass = Nsg.load(loader, Nsg.WORKSPACE);
            dsClass = Nsg.load(loader, Nsg.DATA_SOURCE);
            Class<?> propClass = Nsg.load(loader, Nsg.PROPERTY);
            Class<?> iterClass = Nsg.load(loader, Nsg.PROPERTY_ITER);

            wsJ = wsClass.getField("j");
            wsG = wsClass.getDeclaredField("g"); wsG.setAccessible(true);
            wsModuleIndex = wsClass.getField("a");
            wsDataSource = wsClass.getField("c");

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
            Class<?> formatterClass = Nsg.load(loader, FORMATTER);
            Method cMethod = formatterClass.getMethod("c", Object.class);

            xposed.hook(cMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object thisObj = chain.getThisObject();
                    String key = (String) attrKeyField.get(thisObj);
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
            Log.e(TAG, "NrSaMimoFormatHook: install u00 failed: " + e);
        }
    }

    private void installGridValueLabelHook() {
        try {
            Class<?> gridValueLabelClass = Nsg.load(loader, Nsg.GRID_MULTI);
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
            Log.e(TAG, "NrSaMimoFormatHook: install dh0 failed: " + e);
        }
    }

    /** Read NR_PCell_Num_CSI_Ports using the exact sample key being formatted by dh0.a.
     *  Falls back to the current Workspace cursor if not called from dh0.a. */
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
