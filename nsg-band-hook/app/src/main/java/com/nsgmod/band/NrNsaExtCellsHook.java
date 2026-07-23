package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks a8.b$b.getCount() to allow NR-NSA and LTE cell tables to display more than 8 rows.
 *
 * NSG's shared cell-table adapter base (a8.b$b, "AbstractC0008b") hardcodes a cap of 8
 * in its getCount() override.  The underlying data layer (a8.b$a buckets extending k8.c)
 * receives raw cell counts directly from the modem JNI layer and is NOT limited to 8.
 *
 * When an extended-cells toggle is enabled this hook bypasses the cap and returns
 * min(rawTotal, 16) — matching the maximum allowed by NSG's NR-SA setting.
 *
 * Each table type has its own independent toggle:
 *   NR-NSA NR cell table  → a8.h$a  → SettingsToggleHook.nrNsaExtCellsEnabled()
 *   LTE cell table        → a8.f$a  → SettingsToggleHook.lteExtCellsEnabled()
 *     (a8.f is reused identically in both pure-LTE mode and as the LTE sub-table
 *      in NR-NSA mode; one toggle covers both placements)
 *
 * All other adapters extending a8.b$b (WCDMA, CDMA, GSM, etc.) are unaffected.
 *
 * Field bytecode names (JADX renames with f{num} prefix; suffix is real name):
 *   a8.b$b.e  (JADX: f127e)  — a8.b$a[] bucket array
 *   k8.c.d    (JADX: f5510d) — per-bucket cell count (int)
 */
public class NrNsaExtCellsHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean  ready = false;

    private Class<?> nrNsaAdapterClass; // a8.h$a — NR-NSA NR cell table adapter
    private Class<?> lteAdapterClass;   // a8.f$a — LTE cell table adapter (LTE + NR-NSA mode)
    private Field    eField;            // a8.b$b.e  — a8.b$a[] bucket array
    private Field    dField;            // k8.c.d    — per-bucket cell count

    public NrNsaExtCellsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            nrNsaAdapterClass = ClassMapping.loadClass("a8.h$a", loader);
            lteAdapterClass   = ClassMapping.loadClass("a8.f$a", loader);
            Class<?> bbClass  = ClassMapping.loadClass("a8.b$b", loader);
            Class<?> kcClass  = ClassMapping.loadClass("k8.c", loader);
            if (nrNsaAdapterClass == null || lteAdapterClass == null || bbClass == null || kcClass == null) {
                Log.i(TAG, "NrNsaExtCellsHook: essential adapter class missing, skipping");
                return;
            }

            String eFieldName = ClassMapping.runtimeFieldName("a8.b$b", "e", loader);
            eField = bbClass.getField(eFieldName); // public final a8.b$a[] f127e
            String dFieldName = ClassMapping.runtimeFieldName("k8.c", "d", loader);
            dField = kcClass.getField(dFieldName); // public int f5510d

            ready = true;
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
            Class<?> bbClass        = ClassMapping.loadClass("a8.b$b", loader);
            if (bbClass == null) {
                Log.i(TAG, "NrNsaExtCellsHook: a8.b$b not available, skipping install");
                return;
            }
            Method   getCountMethod = bbClass.getMethod("getCount");

            xposed.hook(getCountMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object thisObj = chain.getThisObject();

                    boolean isNrNsa = nrNsaAdapterClass.isInstance(thisObj);
                    boolean isLte   = !isNrNsa && lteAdapterClass.isInstance(thisObj);

                    if (!isNrNsa && !isLte) return chain.proceed();
                    if (isNrNsa && !SettingsToggleHook.nrNsaExtCellsEnabled()) return chain.proceed();
                    if (isLte   && !SettingsToggleHook.lteExtCellsEnabled())   return chain.proceed();

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
            Log.i(TAG, "NrNsaExtCellsHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaExtCellsHook: install failed: " + e);
        }
    }
}
