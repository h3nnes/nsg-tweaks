package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Colors SCell data-value labels (v6.g) on the NR-SA CA Matrix DL page by SCell index.
 *
 * Root cause of previous failures:
 *   - v6.b.k0(k2.a) only stores the builder reference; no TextViews exist yet.
 *   - k2.a.j(Context, int) is called from v6.b.I() (onCreateView), which fires on the
 *     UI thread during fragment attachment — AFTER h8.b.o0() has completed and cleared
 *     the carrierCountInO0 ThreadLocal. So guarding on that ThreadLocal always misses.
 *
 * Fix:
 *   Hook v6.b.I() as an after-hook. By then k2.a.j() has run, c() has been called on
 *   every element, and f8100a on each v6.g is a live, attached TextView.
 *   Identify SA CA Matrix fragments by looking up this.Y (the k2.a field) in
 *   NrSaCsiSnrRowHook.saK2aCarriers — a WeakHashMap populated during o0().
 *
 * Also keep the v6.b.k0 hook to update f8126i so future c() calls (fragment recreation)
 * also bake the correct color into new TextViews.
 *
 * Color mapping (0-based SCell index from binding field "c"):
 *   index 0 → 0xFFAA66CC  holo_purple (SCell 1 — unchanged from NSG default)
 *   index 1 → 0xFF00BCD4  turquoise   (SCell 2)
 *   index 2 → 0xFFFFEB3B  yellow      (SCell 3)
 *   index 3 → 0xFF4CAF50  green       (SCell 4, future-proof)
 */
public class NrSaCellColorHook {

    private static final String TAG = "NSGBandHook";

    private static final int HOLO_PURPLE = 0xFFAA66CC;

    private static final int[] SCELL_COLORS = {
        0xFFAA66CC, // SCell 1 — holo_purple (unchanged)
        0xFF00BCD4, // SCell 2 — turquoise
        0xFFFFEB3B, // SCell 3 — yellow
        0xFF4CAF50, // SCell 4 — green
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Shared map from NrSaCsiSnrRowHook: k2.a → carrier count, populated during o0()
    private final Map<Object, Integer> saK2aCarriers;

    private Class<?> vgClass;
    private Field vgBindingsField;   // v6.g "f": ArrayList<com.qtrun.sys.b>
    private Field vgColorField;      // v6.g "i": int text color
    private Field vgViewField;       // v6.a "a": View (TextView)
    private Field sysACIndexField;   // com.qtrun.sys.a "c": int SCell index
    private Field k2aElementsField;  // k2.a "d": ArrayList<v6.a>
    private Field v6bYField;         // v6.b "Y": k2.a builder stored on the fragment
    private Field v6aColField;       // v6.a "d" (f8103d): float column position
    private Field v6aRowField;       // v6.a "b" (f8101b): float row position
    private Class<?> rankBindingClass; // d7.i$k — Rank formatter; its c is always 0 in 3-carrier inline path

    private boolean ready = false;

    public NrSaCellColorHook(XposedInterface xposed, ClassLoader loader,
                             Map<Object, Integer> saK2aCarriers) {
        this.xposed = xposed;
        this.loader = loader;
        this.saK2aCarriers = saK2aCarriers;
        initReflection();
    }

    private void initReflection() {
        try {
            vgClass = loader.loadClass("v6.g");
            Class<?> v6aClass  = loader.loadClass("v6.a");
            Class<?> v6bClass  = loader.loadClass("v6.b");
            Class<?> k2aClass  = loader.loadClass("k2.a");
            Class<?> sysAClass = loader.loadClass("com.qtrun.sys.a");

            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);

            vgColorField = vgClass.getDeclaredField("i");
            vgColorField.setAccessible(true);

            vgViewField = v6aClass.getDeclaredField("a");
            vgViewField.setAccessible(true);

            sysACIndexField = sysAClass.getDeclaredField("c");
            sysACIndexField.setAccessible(true);

            k2aElementsField = k2aClass.getDeclaredField("d");
            k2aElementsField.setAccessible(true);

            v6bYField = v6bClass.getDeclaredField("Y");
            v6bYField.setAccessible(true);

            v6aColField = v6aClass.getDeclaredField("d");
            v6aColField.setAccessible(true);

            v6aRowField = v6aClass.getDeclaredField("b");
            v6aRowField.setAccessible(true);

            try {
                rankBindingClass = loader.loadClass("d7.i$k");
            } catch (ClassNotFoundException e2) {
                Log.w(TAG, "NrSaCellColorHook: d7.i$k not found, Rank fallback disabled");
            }

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
            Class<?> v6bClass = loader.loadClass("v6.b");
            Class<?> k2aClass = loader.loadClass("k2.a");

            // Hook 1: v6.b.k0(k2.a) — update f8126i on all matching v6.g objects so that
            // any future c() call (e.g. fragment recreation) bakes the right color.
            Method k0Method = v6bClass.getMethod("k0", k2aClass);
            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object k2aArg = chain.getArg(0);
                    if (k2aArg != null && saK2aCarriers.containsKey(k2aArg)) {
                        updateFieldColors(k2aArg);
                    }
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColorHook: v6.b.k0 hook installed");

            // Hook 2: v6.b.I(LayoutInflater, ViewGroup, Bundle) — after k2.a.j() has run,
            // all TextViews in f8100a are live. Walk this.Y's element list and setTextColor.
            Method iMethod = v6bClass.getMethod("I",
                    android.view.LayoutInflater.class,
                    android.view.ViewGroup.class,
                    android.os.Bundle.class);
            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object v6bInstance = chain.getThisObject();
                        Object k2aObj = v6bYField.get(v6bInstance);
                        if (k2aObj == null) return result;
                        Integer carriers = saK2aCarriers.get(k2aObj);
                        if (carriers == null) return result; // not an SA CA Matrix fragment
                        applyLiveTextColors(k2aObj, carriers);
                    } catch (Exception e) {
                        Log.w(TAG, "NrSaCellColorHook: I-hook outer failed: " + e);
                    }
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColorHook: v6.b.I hook installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColorHook: install failed: " + e);
        }
    }

    /** Update f8126i on all SCell v6.g elements (scell index > 0) for future c() calls. */
    private void updateFieldColors(Object k2aObj) {
        try {
            ArrayList<?> elements = (ArrayList<?>) k2aElementsField.get(k2aObj);
            if (elements == null) return;
            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            for (Object elem : elements) {
                if (!vgClass.isInstance(elem)) continue;
                int cur = (int) vgColorField.get(elem);
                if (cur != HOLO_PURPLE) continue;
                int idx = resolveScellIndex(elem, rankMap);
                if (idx > 0 && idx < SCELL_COLORS.length) {
                    vgColorField.set(elem, SCELL_COLORS[idx]);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: updateFieldColors failed: " + e);
        }
    }

    /**
     * After v6.b.I() returns, c() has created fresh TextViews in f8100a.
     * Walk all v6.g elements with SCell index > 0 and call setTextColor on the live view.
     */
    private void applyLiveTextColors(Object k2aObj, int carriers) {
        try {
            ArrayList<?> elements = (ArrayList<?>) k2aElementsField.get(k2aObj);
            if (elements == null) return;

            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            int applied = 0;
            for (Object elem : elements) {
                if (!vgClass.isInstance(elem)) continue;

                int idx = resolveScellIndex(elem, rankMap);
                if (idx <= 0 || idx >= SCELL_COLORS.length) continue;

                int targetColor = SCELL_COLORS[idx];

                // Also patch f8126i in case fragment is recreated
                vgColorField.set(elem, targetColor);

                try {
                    View v = (View) vgViewField.get(elem);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(targetColor);
                        applied++;
                    }
                } catch (Exception ignored) {}
            }
            Log.i(TAG, "NrSaCellColorHook: applied " + applied
                    + " colors (carriers=" + carriers
                    + " elements=" + elements.size() + ")");
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: applyLiveTextColors failed: " + e);
        }
    }

    /**
     * Build a map from v6.g object → SCell index for all Rank elements (d7.i$k bindings).
     *
     * Strategy: collect ALL Rank bars regardless of column, sort globally by (col, row),
     * treat the first entry (smallest col, then smallest row) as the PCell and skip it,
     * then assign global SCell indices 0, 1, 2, … to the remaining elements in order.
     *
     * This correctly handles all three NSG layout paths:
     *   2-carrier (k0):     col=30:[PCell]          col=65:[SCell0]            → idx 0
     *   3-carrier (inline): col=30:[PCell]          col=65:[SCell0, SCell1]    → idx 0, 1
     *   4-carrier (l0):     col=30:[PCell, SCell0]  col=65:[SCell1, SCell2]    → idx 0, 1, 2
     *
     * The previous col<60 filter accidentally excluded SCell[0] at col=30 in the 4-carrier
     * path, causing SCell[1] and SCell[2] to be assigned indices 0 and 1 (off by one) and
     * therefore display the wrong colors (holo_purple and turquoise instead of turquoise and
     * yellow).
     */
    private Map<Object, Integer> buildRankScellMap(ArrayList<?> elements) {
        Map<Object, Integer> result = new HashMap<>();
        if (rankBindingClass == null) return result;

        ArrayList<Object> rankElems = new ArrayList<>();
        ArrayList<Float>  rankCols  = new ArrayList<>();
        ArrayList<Float>  rankRows  = new ArrayList<>();

        for (Object elem : elements) {
            if (!vgClass.isInstance(elem)) continue;
            try {
                ArrayList<?> bindings = (ArrayList<?>) vgBindingsField.get(elem);
                if (bindings == null || bindings.isEmpty()) continue;
                Object b = bindings.get(0);
                if (b == null || !rankBindingClass.isInstance(b)) continue;
                rankElems.add(elem);
                rankCols.add((float) v6aColField.get(elem));
                rankRows.add((float) v6aRowField.get(elem));
            } catch (Exception ignored) {}
        }

        if (rankElems.isEmpty()) return result;

        // Sort globally by (col, row) — PCell is always first (smallest col, then smallest row)
        Integer[] indices = new Integer[rankElems.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        final ArrayList<Float> finalCols = rankCols;
        final ArrayList<Float> finalRows = rankRows;
        java.util.Arrays.sort(indices, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b2) {
                int cc = Float.compare(finalCols.get(a), finalCols.get(b2));
                if (cc != 0) return cc;
                return Float.compare(finalRows.get(a), finalRows.get(b2));
            }
        });

        // Skip indices[0] (PCell); assign global SCell indices 0, 1, 2, … to the rest
        int scellIdx = 0;
        for (int si = 1; si < indices.length; si++) {
            result.put(rankElems.get(indices[si]), scellIdx++);
        }
        return result;
    }

    /**
     * Determine the SCell index for a v6.g element.
     * rankMap overrides when provided (for d7.i$k elements with ambiguous c=0).
     */
    private int resolveScellIndex(Object vgObj, Map<Object, Integer> rankMap) {
        // Check Rank override map first
        if (rankMap != null && rankMap.containsKey(vgObj)) {
            return rankMap.get(vgObj);
        }
        return readScellIndex(vgObj);
    }

    private int readScellIndex(Object vgObj) {
        try {
            ArrayList<?> bindings = (ArrayList<?>) vgBindingsField.get(vgObj);
            if (bindings == null || bindings.isEmpty()) return -1;
            Object b = bindings.get(0);
            if (b == null) return -1;
            try {
                return (int) sysACIndexField.get(b);
            } catch (IllegalArgumentException ex) {
                Field f = findField(b.getClass(), "c");
                if (f == null) return -1;
                f.setAccessible(true);
                return (int) f.get(b);
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: readScellIndex failed: " + e);
            return -1;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }
}
