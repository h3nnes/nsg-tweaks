package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks nc.getCount() to allow NR-NSA and LTE cell tables to display more than 8 rows.
 *
 * NSG's shared cell-table adapter {@code nc} (extends q00) hardcodes a cap of 8 in its
 * getCount() override. The underlying data layer ({@code de[]} buckets, extending q00)
 * receives raw cell counts directly from the modem JNI layer and is NOT limited to 8.
 *
 * When an extended-cells toggle is enabled this hook bypasses the cap and returns
 * min(rawTotal, 16) - matching the maximum allowed by NSG's NR-SA setting.
 *
 * The table type is selected by {@code nc.f} (int): 4 = LTE (y00), 6 = NR-NSA (oa0).
 *   NR-NSA cell table -> SettingsToggleHook.nrNsaExtCellsEnabled()
 *   LTE cell table    -> SettingsToggleHook.lteExtCellsEnabled()
 * All other adapter types (WCDMA, CDMA, GSM, ...) are unaffected.
 *
 * Fields: nc.e (de[] buckets), q00.d (per-bucket cell count, int).
 */
public class NrNsaExtCellsHook {

    private static final String TAG = "NSGBandHook";

    private static final String ADAPTER_BASE = "q00";
    private static final int ADAPTER_TYPE_LTE = 4;
    private static final int ADAPTER_TYPE_NR_NSA = 6;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean  ready = false;

    private Class<?> adapterClass;     // nc
    private Field    eField;           // nc.e   - de[] bucket array
    private Field    dField;           // q00.d  - per-bucket cell count
    private Field    adapterTypeField; // nc.f   - 4=LTE, 6=NR-NSA

    public NrNsaExtCellsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            adapterClass = Nsg.load(loader, CellTableRowDispatcher.ADAPTER);
            Class<?> baseClass = Nsg.load(loader, ADAPTER_BASE);
            if (adapterClass == null || baseClass == null) {
                Log.i(TAG, "NrNsaExtCellsHook: adapter class missing, skipping");
                return;
            }
            eField = adapterClass.getField("e");
            dField = baseClass.getField("d");
            adapterTypeField = adapterClass.getDeclaredField("f");
            adapterTypeField.setAccessible(true);
            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrNsaExtCellsHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrNsaExtCellsHook: skipping install - reflection not ready");
            return;
        }
        try {
            Method getCountMethod = adapterClass.getMethod("getCount");

            xposed.hook(getCountMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object thisObj = chain.getThisObject();

                    int type = adapterTypeField.getInt(thisObj);
                    boolean isNrNsa = (type == ADAPTER_TYPE_NR_NSA);
                    boolean isLte   = (type == ADAPTER_TYPE_LTE);

                    if (!isNrNsa && !isLte) return chain.proceed();
                    if (isNrNsa && !SettingsToggleHook.nrNsaExtCellsEnabled()) return chain.proceed();
                    if (isLte   && !SettingsToggleHook.lteExtCellsEnabled())   return chain.proceed();

                    // Raw total across all buckets - bypasses the hardcoded cap of 8.
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
