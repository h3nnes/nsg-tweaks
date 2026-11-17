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
 * Colors SCell data-value labels (dh0) on the NR-SA CA Matrix DL page by SCell index.
 *
 * v4.8.14 targets:
 *   r31  grid builder; b = ArrayList of grid elements
 *   xg0  grid element base: a = View, b = row (float), d = column (float)
 *   yg0  grid page fragment: static g0(r31) stores the builder, X = r31, E = onCreateView
 *   dh0  multi-attribute value cell: f = ArrayList of l8 bindings, i = text color
 *   l8   attribute binding: c = SCell index
 *   u00  formatter binding; e = case id (27 = Rank)
 *
 * k0-style rebuild (db0.k0) only stores the builder via yg0.g0(r31); no TextViews exist yet.
 * yg0.E() (onCreateView) runs later on the UI thread, after db0.k0() has completed and cleared
 * the carrierCountInO0 ThreadLocal, so guarding on that ThreadLocal would always miss.
 *
 * Therefore:
 *   Hook yg0.E() as an after-hook. By then every element has built its view and
 *   xg0.a on each dh0 is a live, attached TextView.
 *   Identify SA CA Matrix fragments by looking up this.X (the r31 field) in
 *   NrSaCsiSnrRowHook.saK2aCarriers — a WeakHashMap populated during db0.k0().
 *
 * Also keep the yg0.g0 hook to update dh0.i so future view builds (fragment recreation)
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
        0xFFEF9A9A, // SCell 5 — light red (Material Red 200)
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Shared map from NrSaCsiSnrRowHook: r31 → carrier count, populated during db0.k0()
    private final Map<Object, Integer> saK2aCarriers;

    private static final String ATTR_L8 = "l8";   // attribute binding
    private static final String FORMATTER_U00 = "u00"; // formatter binding with case id

    private Class<?> vgClass;
    private Field vgBindingsField;   // dh0.f: ArrayList<m8>
    private Field vgColorField;      // dh0.i: int text color
    private Field vgViewField;       // xg0.a: View (TextView)
    private Field sysACIndexField;   // l8.c: int SCell index
    private Field k2aElementsField;  // r31.b: ArrayList<xg0>
    private Field v6bYField;         // yg0.X: r31 builder stored on the fragment
    private Field v6aColField;       // xg0.d: float column position
    private Field v6aRowField;       // xg0.b: float row position
    private Class<?> rankBindingClass; // u00 — formatter binding; its c is always 0 in 3-carrier inline path
    private Field rankCaseField;      // u00.e (int case id) — 27 for Rank
    private static final int RANK_CASE_ID = 27;

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
            vgClass = Nsg.load(loader, Nsg.GRID_MULTI);
            Class<?> v6aClass  = Nsg.load(loader, Nsg.GRID_ELEMENT);
            Class<?> v6bClass  = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> k2aClass  = Nsg.load(loader, Nsg.GRID_BUILDER);
            Class<?> sysAClass = Nsg.load(loader, ATTR_L8);
            rankBindingClass   = Nsg.load(loader, FORMATTER_U00);

            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);

            vgColorField = vgClass.getDeclaredField("i");
            vgColorField.setAccessible(true);

            vgViewField = v6aClass.getDeclaredField("a");
            vgViewField.setAccessible(true);

            sysACIndexField = sysAClass.getDeclaredField("c");
            sysACIndexField.setAccessible(true);

            k2aElementsField = k2aClass.getDeclaredField("b");
            k2aElementsField.setAccessible(true);

            v6bYField = v6bClass.getDeclaredField("X");
            v6bYField.setAccessible(true);

            v6aColField = v6aClass.getDeclaredField("d");
            v6aColField.setAccessible(true);

            v6aRowField = v6aClass.getDeclaredField("b");
            v6aRowField.setAccessible(true);

            rankCaseField = rankBindingClass.getDeclaredField("e");
            rankCaseField.setAccessible(true);

            ready = true;
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
            Class<?> v6bClass = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> k2aClass = Nsg.load(loader, Nsg.GRID_BUILDER);

            // Hook 1: yg0.g0(r31) — update dh0.i on all matching dh0 objects so that
            // any future view build (e.g. fragment recreation) bakes the right color.
            Method k0Method = v6bClass.getDeclaredMethod("g0", k2aClass);
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
            Log.i(TAG, "NrSaCellColorHook: installed");

            // Hook 2: yg0.E(LayoutInflater, ViewGroup, Bundle) — after the elements built their
            // views, all TextViews in xg0.a are live. Walk this.X's element list and setTextColor.
            Method iMethod = v6bClass.getDeclaredMethod("E",
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
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColorHook: install failed: " + e);
        }
    }

    /** Update dh0.i on all SCell dh0 elements (scell index > 0) for future view builds. */
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
     * After yg0.E() returns, fresh TextViews exist in xg0.a.
     * Walk all dh0 elements with SCell index > 0 and call setTextColor on the live view.
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

                // Also patch dh0.i in case fragment is recreated
                vgColorField.set(elem, targetColor);

                try {
                    View v = (View) vgViewField.get(elem);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(targetColor);
                        applied++;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColorHook: applyLiveTextColors failed: " + e);
        }
    }

    /**
     * Build a map from dh0 object → SCell index for all Rank elements (u00 case-27 bindings).
     *
     * Strategy: collect ALL Rank bars regardless of column, sort globally by (col, row),
     * treat the first entry (smallest col, then smallest row) as the PCell and skip it,
     * then assign global SCell indices 0, 1, 2, … to the remaining elements in order.
     *
     * This correctly handles all three NSG layout paths:
     *   2-carrier (g0):     col=30:[PCell]          col=65:[SCell0]            → idx 0
     *   3-carrier (inline): col=30:[PCell]          col=65:[SCell0, SCell1]    → idx 0, 1
     *   4-carrier (h0):     col=30:[PCell, SCell0]  col=65:[SCell1, SCell2]    → idx 0, 1, 2
     *
     * The previous col<60 filter accidentally excluded SCell[0] at col=30 in the 4-carrier
     * path, causing SCell[1] and SCell[2] to be assigned indices 0 and 1 (off by one) and
     * therefore display the wrong colors (holo_purple and turquoise instead of turquoise and
     * yellow).
     */
    private Map<Object, Integer> buildRankScellMap(ArrayList<?> elements) {
        Map<Object, Integer> result = new HashMap<>();

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
                if ((int) rankCaseField.get(b) != RANK_CASE_ID) continue;
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
     * Determine the SCell index for a dh0 element.
     * rankMap overrides when provided (for Rank u00 bindings with ambiguous c=0).
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
