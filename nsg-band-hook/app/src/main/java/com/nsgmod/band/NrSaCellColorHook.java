package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Colors SCell data-value labels (v6.g) on the NR-SA CA Matrix DL page by SCell index.
 *
 * Background:
 *   NSG calls v6.g.j(int size, int color) on every SCell value label (SCS, MIMO, Rank,
 *   RBs, CQI, Modulation, Layers, Scheduled count, Throughput, etc.) passing
 *   android.R.color.holo_purple (0xFF9C27B0) as the color, regardless of SCell index.
 *   This makes it impossible to visually distinguish SCell 2, 3, 4 values.
 *
 * Strategy:
 *   Hook v6.g.j(int size, int color). When the color matches holo_purple, read the
 *   SCell index from the first binding object in this.f (ArrayList<com.qtrun.sys.b>),
 *   field c (the index field on com.qtrun.sys.a, parent of b). Replace the color
 *   argument before proceeding.
 *
 * Color mapping (0-based SCell index):
 *   index 0 → 0xFF9C27B0  violet    (keep existing NSG color — SCell 1)
 *   index 1 → 0xFF00BCD4  turquoise (SCell 2)
 *   index 2 → 0xFFFFEB3B  yellow    (SCell 3)
 *   index 3 → 0xFF4CAF50  green     (SCell 4, future-proofing)
 *
 * PCell labels are unaffected: NSG never calls j() on PCell labels.
 */
public class NrSaCellColorHook {

    private static final String TAG = "NSGBandHook";

    // holo_purple ARGB — the color NSG passes to v6.g.j() for all SCell labels.
    // We only intercept calls with this exact color to avoid affecting other screens.
    private static final int HOLO_PURPLE = 0xFF9C27B0;

    // Per-SCell colors (0-based index).  index 0 keeps holo_purple.
    private static final int[] SCELL_COLORS = {
        0xFF9C27B0, // SCell 1 — violet  (unchanged from NSG default)
        0xFF00BCD4, // SCell 2 — turquoise
        0xFFFFEB3B, // SCell 3 — yellow
        0xFF4CAF50, // SCell 4 — green   (future-proof)
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // v6.g field "f" (f8124f): ArrayList<com.qtrun.sys.b> — data-binding list
    private Field vgBindingsField;
    // v6.g fields written by j(): "i"=color, "j"=size, "k"=gravity
    private Field vgIField; // f8126i — text color
    private Field vgJField; // f8127j — size level
    private Field vgKField; // f8128k — gravity

    // com.qtrun.sys.a field "c" (f3870c): int — SCell index (-1 = PCell/no index)
    private Field sysACIndexField;

    private boolean ready = false;

    public NrSaCellColorHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> vgClass   = loader.loadClass("v6.g");
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");

            // v6.g.f — ArrayList of data-binding objects
            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);
            // v6.g fields set by j()
            vgIField = vgClass.getDeclaredField("i");
            vgIField.setAccessible(true);
            vgJField = vgClass.getDeclaredField("j");
            vgJField.setAccessible(true);
            vgKField = vgClass.getDeclaredField("k");
            vgKField.setAccessible(true);

            // com.qtrun.sys.a.c — SCell index (int, -1 means PCell / not indexed)
            sysACIndexField = sysAClass.getDeclaredField("c");
            sysACIndexField.setAccessible(true);

            ready = true;
            Log.i(TAG, "NrSaCellColorHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColorHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCellColorHook: skipping install — reflection not ready");
            return;
        }
        try {
            Class<?> vgClass = loader.loadClass("v6.g");
            Method   jMethod = vgClass.getMethod("j", int.class, int.class);

            xposed.hook(jMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    int size  = (int) chain.getArg(0);
                    int color = (int) chain.getArg(1);

                    // Only intercept calls with holo_purple — ignore all other screens/colors
                    if (color == HOLO_PURPLE) {
                        int scellIndex = readScellIndex(chain.getThisObject());
                        Log.d(TAG, "NrSaCellColorHook: j() intercepted scellIndex=" + scellIndex
                                + " size=" + size);
                        if (scellIndex >= 0 && scellIndex < SCELL_COLORS.length) {
                            int newColor = SCELL_COLORS[scellIndex];
                            if (newColor != color) {
                                // libxposed chain has no setArg — write the color field directly
                                // and skip proceed(), then set size/gravity fields to match
                                // what j() would have done.
                                Log.d(TAG, "NrSaCellColorHook: replacing color 0x"
                                        + Integer.toHexString(color)
                                        + " -> 0x" + Integer.toHexString(newColor)
                                        + " for scell=" + scellIndex);
                                applyJ(chain.getThisObject(), size, newColor);
                                return null; // skip original j() call
                            }
                        }
                    }

                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrSaCellColorHook: v6.g.j hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColorHook: install failed: " + e);
        }
    }

    /**
     * Replicates v6.g.j(size, color) with a replacement color.
     * v6.g.j() body:
     *   this.f8127j = size;    // field "j"
     *   this.f8128k = 2;       // field "k" (gravity = center)
     *   this.f8126i = color;   // field "i"
     */
    private void applyJ(Object vgObj, int size, int color) {
        try {
            vgJField.set(vgObj, size);
            vgKField.set(vgObj, 2);
            vgIField.set(vgObj, color);
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: applyJ failed: " + e);
        }
    }

    /**
     * Read the SCell index from the first data-binding object on a v6.g instance.
     * Returns -1 if the index cannot be determined (treat as PCell/unknown).
     */
    private int readScellIndex(Object vgObj) {
        try {
            ArrayList<?> bindings = (ArrayList<?>) vgBindingsField.get(vgObj);
            if (bindings == null || bindings.isEmpty()) {
                Log.d(TAG, "NrSaCellColorHook: no bindings on v6.g object");
                return -1;
            }
            Object firstBinding = bindings.get(0);
            if (firstBinding == null) return -1;
            int idx = (int) sysACIndexField.get(firstBinding);
            return idx;
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: readScellIndex failed: " + e);
            return -1;
        }
    }
}
