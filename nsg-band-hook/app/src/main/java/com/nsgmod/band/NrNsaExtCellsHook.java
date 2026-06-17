package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks a8.b$b.getCount() to allow NR-NSA cell table to display more than 8 rows.
 *
 * NSG's shared cell-table adapter base (a8.b$b, "AbstractC0008b") hardcodes a cap of 8
 * in its getCount() override.  The underlying data layer (a8.b$a buckets extending k8.c)
 * receives raw cell counts directly from the modem JNI layer and is NOT limited to 8.
 *
 * When the "NR-NSA extended cells" toggle is enabled this hook bypasses the cap and
 * returns min(rawTotal, 16) — matching the maximum allowed by NSG's NR-SA setting.
 *
 * Only the NR-NSA adapter (a8.h$a) is affected; all other cell-table adapters that
 * also extend a8.b$b (LTE, WCDMA, CDMA, etc.) continue to use the original 8-cell cap.
 *
 * Field bytecode names (JADX renames with f{num} prefix; suffix is real name):
 *   a8.b$b.e  (JADX: f127e)  — a8.b$a[] bucket array
 *   k8.c.d    (JADX: f5510d) — per-bucket cell count (int)
 */
public class NrNsaExtCellsHook {

    private static final String TAG = "NSGBandHook_NrNsaExt";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean  ready = false;

    private Class<?> nrNsaAdapterClass; // a8.h$a — for instanceof check
    private Field    eField;            // a8.b$b.e  — a8.b$a[] bucket array
    private Field    dField;            // k8.c.d    — per-bucket cell count

    public NrNsaExtCellsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            nrNsaAdapterClass = loader.loadClass("a8.h$a");
            Class<?> bbClass  = loader.loadClass("a8.b$b");
            Class<?> kcClass  = loader.loadClass("k8.c");

            eField = bbClass.getField("e"); // public final a8.b$a[] f127e
            dField = kcClass.getField("d"); // public int f5510d

            ready = true;
            Log.i(TAG, "NrNsaExtCellsHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaExtCellsHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrNsaExtCellsHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> bbClass        = loader.loadClass("a8.b$b");
            Method   getCountMethod = bbClass.getMethod("getCount");

            xposed.hook(getCountMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Only override for NR-NSA adapter instances
                    Object thisObj = chain.getThisObject();
                    if (!nrNsaAdapterClass.isInstance(thisObj)) {
                        return chain.proceed();
                    }
                    if (!SettingsToggleHook.nrNsaExtCellsEnabled()) {
                        return chain.proceed();
                    }
                    // Compute raw total across all buckets — bypass the hardcoded cap of 8
                    Object[] buckets = (Object[]) eField.get(thisObj);
                    if (buckets == null) return chain.proceed();
                    int total = 0;
                    for (Object bucket : buckets) {
                        total += dField.getInt(bucket);
                    }
                    return Math.min(total, 16);
                }
            });
            Log.i(TAG, "NrNsaExtCellsHook: installed on a8.b$b.getCount()");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaExtCellsHook: install failed: " + e);
        }
    }
}
