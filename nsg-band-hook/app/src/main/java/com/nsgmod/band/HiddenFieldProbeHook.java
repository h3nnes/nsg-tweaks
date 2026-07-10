package com.nsgmod.band;

import android.util.Log;
import android.util.LongSparseArray;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Probe hook that tries to read hidden/native fields that are not exposed in the
 * NSG UI/workspace, currently targeting NR5G::Uplink_Measurements::NR_Power_Tx_SRS.
 *
 * It hooks v6.g.a(long sampleKey, DataSource, short moduleIndex) so it can read
 * the property using the exact sample/module being formatted, and falls back to
 * the current Workspace cursor if no formatting context is active.
 */
public class HiddenFieldProbeHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean ready = false;

    // Workspace / DataSource reflection
    private Field wsJ;           // Workspace.j singleton
    private Field wsG;           // Workspace.g — current sample key (cursor)
    private Field wsModuleIndex; // Workspace.a — current module index
    private Field wsDataSource;  // Workspace.c — current DataSource
    private Class<?> dsClass;    // com.qtrun.sys.DataSource
    private Method dsGetProperty; // DataSource.getProperty(String, int)
    private Method propBMethod;  // Property.b(long) → Iterator
    private Method iterEndMethod; // Iterator.end()Z
    private Method iterValueMethod; // Iterator.value()Object

    private static final ThreadLocal<ProbeContext> probeContext = new ThreadLocal<>();

    private static final class ProbeContext {
        final long sampleKey;
        final int moduleIndex;
        final Object dataSource;

        ProbeContext(long sampleKey, int moduleIndex, Object dataSource) {
            this.sampleKey = sampleKey;
            this.moduleIndex = moduleIndex;
            this.dataSource = dataSource;
        }
    }

    private static final String[] TARGET_KEYS = {
            "Power_Tx_SRS",
            "NR5G::Uplink_Measurements::NR_Power_Tx_SRS",
            "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level1_DL"
    };

    // One dedup set per target key to avoid logcat spam.
    private final LongSparseArray<Long>[] loggedKeys;

    @SuppressWarnings("unchecked")
    public HiddenFieldProbeHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        this.loggedKeys = new LongSparseArray[TARGET_KEYS.length];
        for (int i = 0; i < loggedKeys.length; i++) {
            loggedKeys[i] = new LongSparseArray<>();
        }
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> wsClass = loader.loadClass("com.qtrun.sys.Workspace");
            dsClass = loader.loadClass("com.qtrun.sys.DataSource");
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
        } catch (Exception e) {
            Log.e(TAG, "HiddenFieldProbeHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "HiddenFieldProbeHook: skipping install — reflection not ready");
            return;
        }
        installGridValueLabelHook();
    }

    private void installGridValueLabelHook() {
        try {
            Class<?> gridValueLabelClass = loader.loadClass("v6.g");
            Method aMethod = gridValueLabelClass.getMethod("a", long.class, dsClass, short.class);

            xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object sampleKeyObj = chain.getArg(0);
                    Object ds = chain.getArg(1);
                    Object moduleIndexObj = chain.getArg(2);

                    if (sampleKeyObj instanceof Number && moduleIndexObj instanceof Number) {
                        probeContext.set(new ProbeContext(
                                ((Number) sampleKeyObj).longValue(),
                                ((Number) moduleIndexObj).intValue(),
                                ds));
                    }

                    try {
                        for (int i = 0; i < TARGET_KEYS.length; i++) {
                            readAndLog(TARGET_KEYS[i], i);
                        }
                        return chain.proceed();
                    } finally {
                        probeContext.remove();
                    }
                }
            });
            Log.i(TAG, "HiddenFieldProbeHook: grid value label hook installed");
        } catch (Exception e) {
            Log.e(TAG, "HiddenFieldProbeHook: install v6.g failed: " + e);
        }
    }

    /** Read the named property for the currently active context and log once per sampleKey. */
    private void readAndLog(String key, int keyIndex) {
        ProbeContext ctx = probeContext.get();

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
                    Log.w(TAG, "HiddenFieldProbeHook: no Workspace instance for " + key);
                    return;
                }
                sampleKey = wsG.getLong(ws);
                moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
                ds = wsDataSource.get(ws);
            } catch (Exception e) {
                Log.w(TAG, "HiddenFieldProbeHook: Workspace fallback failed for " + key + ": " + e);
                return;
            }
        }

        if (ds == null) {
            Log.w(TAG, "HiddenFieldProbeHook: DataSource is null for " + key);
            return;
        }

        // Deduplicate: log each unique sampleKey only once per key.
        if (loggedKeys[keyIndex].indexOfKey(sampleKey) >= 0) {
            return;
        }
        loggedKeys[keyIndex].put(sampleKey, sampleKey);

        try {
            Object property = dsGetProperty.invoke(ds, key, moduleIndex);
            if (property == null) {
                Log.i(TAG, "HiddenFieldProbeHook: getProperty returned null for " + key
                        + " sampleKey=" + sampleKey + " moduleIndex=" + moduleIndex);
                return;
            }

            Object iterator = propBMethod.invoke(property, sampleKey);
            if (iterator == null) {
                Log.i(TAG, "HiddenFieldProbeHook: Property.b returned null for " + key
                        + " sampleKey=" + sampleKey + " moduleIndex=" + moduleIndex);
                return;
            }

            if ((boolean) iterEndMethod.invoke(iterator)) {
                Log.i(TAG, "HiddenFieldProbeHook: iterator at end for " + key
                        + " sampleKey=" + sampleKey + " moduleIndex=" + moduleIndex);
                return;
            }

            Object value = iterValueMethod.invoke(iterator);
            Log.i(TAG, "HiddenFieldProbeHook: " + key
                    + " sampleKey=" + sampleKey
                    + " moduleIndex=" + moduleIndex
                    + " value=" + value
                    + " valueClass=" + (value != null ? value.getClass().getName() : "null"));
        } catch (Exception e) {
            Log.w(TAG, "HiddenFieldProbeHook: readAndLog failed for " + key
                    + " sampleKey=" + sampleKey + " moduleIndex=" + moduleIndex + ": " + e);
        }
    }
}
