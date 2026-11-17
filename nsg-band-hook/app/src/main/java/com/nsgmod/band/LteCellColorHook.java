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
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Colors SCell data-value labels (dh0) on the LTE CA Matrix DL (w00) and
 * NR-NSA EUTRA CA Matrix (ua0) pages by SCell index.
 *
 * NSG v4.8.14 targets:
 *   - yg0 (grid page fragment): static g0(r31) wraps a grid builder; yg0.X holds the
 *     r31 builder; yg0.E(LayoutInflater, ViewGroup, Bundle) is onCreateView.
 *   - r31.b: ArrayList of grid elements (xg0: view a, row b, col d).
 *   - dh0: bindings f (ArrayList of m8), text colour i.
 *   - u00 (m8 subclass, int switch id e): formatter used by the Rank rows.
 *
 * Identification:
 *   - yg0.g0(r31) is called synchronously from w00.j0() / ua0.j0() while the
 *     LTE/EUTRA CA Matrix page is building its builder. We inspect the current
 *     thread's stack trace for the caller class "w00" or "ua0" and mark the
 *     r31 builder in a WeakHashMap.
 *   - yg0.E() (onCreateView) fires later on the UI thread when the wrapped
 *     fragment creates its view. At that point yg0.X is the r31 builder, its
 *     element list is populated and xg0.a holds live TextViews, so we walk
 *     the SCell elements and apply setTextColor.
 *
 * Color mapping (0-based SCell index parsed from binding property key):
 *   index 0 → 0xFFAA66CC  holo_purple (SCell 1 — unchanged from NSG default)
 *   index 1 → 0xFF00BCD4  turquoise   (SCell 2)
 *   index 2 → 0xFFFFEB3B  yellow      (SCell 3)
 *   index 3 → 0xFF4CAF50  green       (SCell 4, future-proof)
 */
public class LteCellColorHook {

    private static final String TAG = "NSGBandHook";

    private static final int HOLO_PURPLE = 0xFFAA66CC;

    private static final String CLASS_LTE_CA_MATRIX = Nsg.PAGE_LTE_CA_MATRIX_DL;       // w00
    private static final String CLASS_NSA_LTE_CA_MATRIX = Nsg.PAGE_NSA_LTE_CA_MATRIX;  // ua0
    /** m8 subclass with int switch field e; case 27 is the Rank formatter. */
    private static final String RANK_BINDING_CLASS = "u00";

    private static final int[] SCELL_COLORS = {
        0xFFAA66CC, // SCell 1 — holo_purple (unchanged)
        0xFF00BCD4, // SCell 2 — turquoise
        0xFFFFEB3B, // SCell 3 — yellow
        0xFF4CAF50, // SCell 4 — green
        0xFFEF9A9A, // SCell 5 — light red (Material Red 200)
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    /**
     * Marks r31 builders that originated inside w00.j0() or ua0.j0().
     * Populated by the yg0.g0 hook using stack-trace inspection.
     * Used by the yg0.E hook to decide whether to apply SCell colors.
     */
    private final Map<Object, Boolean> lteBuilders =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private Class<?> multiClass;          // dh0
    private Field multiBindingsField;     // dh0.f: ArrayList<m8>
    private Field multiColorField;        // dh0.i: int text color
    private Field elementViewField;       // xg0.a: View (TextView)
    private Field builderElementsField;   // r31.b: ArrayList<xg0>
    private Field pageBuilderField;       // yg0.X: r31 builder stored on the fragment
    private Field elementColField;        // xg0.d: float column position
    private Field elementRowField;        // xg0.b: float row position
    private Class<?> rankBindingClass;    // u00
    private Field rankCaseField;          // u00.e: int case id
    private static final int RANK_CASE_ID = 27;

    private boolean ready = false;

    public LteCellColorHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            multiClass = Nsg.load(loader, Nsg.GRID_MULTI);                  // dh0
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);     // xg0
            Class<?> pageClass    = Nsg.load(loader, Nsg.GRID_PAGE);        // yg0
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);     // r31

            multiBindingsField = multiClass.getDeclaredField("f");
            multiBindingsField.setAccessible(true);

            multiColorField = multiClass.getDeclaredField("i");
            multiColorField.setAccessible(true);

            elementViewField = elementClass.getDeclaredField("a");
            elementViewField.setAccessible(true);

            builderElementsField = builderClass.getDeclaredField("b");
            builderElementsField.setAccessible(true);

            pageBuilderField = pageClass.getDeclaredField("X");
            pageBuilderField.setAccessible(true);

            elementColField = elementClass.getDeclaredField("d");
            elementColField.setAccessible(true);

            elementRowField = elementClass.getDeclaredField("b");
            elementRowField.setAccessible(true);

            rankBindingClass = Nsg.load(loader, RANK_BINDING_CLASS);
            rankCaseField = rankBindingClass.getDeclaredField("e");
            rankCaseField.setAccessible(true);

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "LteCellColorHook: skipping install — reflection not ready");
            return;
        }
        try {
            installGridCallbackHook();
            installCreateViewHook();
            Log.i(TAG, "LteCellColorHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: install failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 1: static yg0.g0(r31) — identify LTE/EUTRA builders by call stack
    // -----------------------------------------------------------------------

    private void installGridCallbackHook() {
        try {
            Class<?> pageClass = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);

            Method g0Method = pageClass.getDeclaredMethod("g0", builderClass);
            xposed.hook(g0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object builder = chain.getArg(0);
                    if (builder == null) return result;

                    if (findLteMatrixCaller() != null) {
                        lteBuilders.put(builder, Boolean.TRUE);
                        updateFieldColors(builder);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: yg0.g0 hook failed: " + e);
        }
    }

    /**
     * Walk the current thread's stack trace looking for the LTE CA Matrix or
     * NR-NSA EUTRA CA Matrix page class. This is reliable because yg0.g0()
     * is called synchronously from w00.j0() / ua0.j0() while the page is
     * constructing its builder.
     */
    private String findLteMatrixCaller() {
        try {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String cls = element.getClassName();
                if (CLASS_LTE_CA_MATRIX.equals(cls)) return CLASS_LTE_CA_MATRIX;
                if (CLASS_NSA_LTE_CA_MATRIX.equals(cls)) return CLASS_NSA_LTE_CA_MATRIX;
            }
        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: stack trace inspection failed: " + e);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Hook 2: yg0.E(...) after-hook — apply live text colors to attached TextViews
    // -----------------------------------------------------------------------

    private void installCreateViewHook() {
        try {
            Class<?> pageClass = Nsg.load(loader, Nsg.GRID_PAGE);

            // yg0.E(LayoutInflater, ViewGroup, Bundle): onCreateView
            Method createView = pageClass.getDeclaredMethod("E",
                    android.view.LayoutInflater.class,
                    android.view.ViewGroup.class,
                    android.os.Bundle.class);
            xposed.hook(createView).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object builder = pageBuilderField.get(chain.getThisObject());
                        if (builder == null) {
                            Log.w(TAG, "LteCellColorHook: yg0.E() has no builder (X is null)");
                            return result;
                        }

                        if (!lteBuilders.containsKey(builder)) {
                            return result;
                        }

                        applyLiveTextColors(builder);
                    } catch (Exception e) {
                        Log.w(TAG, "LteCellColorHook: createView hook failed: " + e);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: yg0.E hook failed: " + e);
        }
    }

    /** Update the text color field on all SCell dh0 elements (scell index > 0) for future c() calls. */
    private void updateFieldColors(Object builder) {
        try {
            ArrayList<?> elements = (ArrayList<?>) builderElementsField.get(builder);
            if (elements == null) {
                Log.w(TAG, "LteCellColorHook: updateFieldColors — no elements in builder");
                return;
            }
            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            for (Object elem : elements) {
                if (!multiClass.isInstance(elem)) continue;
                int cur = (int) multiColorField.get(elem);
                if (cur != HOLO_PURPLE) continue;
                int idx = resolveScellIndex(elem, rankMap);
                if (idx > 0 && idx < SCELL_COLORS.length) {
                    multiColorField.set(elem, SCELL_COLORS[idx]);
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: updateFieldColors failed: " + e);
        }
    }

    /**
     * After yg0.E() returns, c() has created fresh TextViews in xg0.a.
     * Walk all dh0 elements with SCell index > 0 and call setTextColor on the live view.
     */
    private void applyLiveTextColors(Object builder) {
        try {
            ArrayList<?> elements = (ArrayList<?>) builderElementsField.get(builder);
            if (elements == null) {
                Log.w(TAG, "LteCellColorHook: applyLiveTextColors — no elements in builder");
                return;
            }

            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            for (Object elem : elements) {
                if (!multiClass.isInstance(elem)) continue;

                String[] keyHolder = new String[1];
                int idx = resolveScellIndex(elem, rankMap, keyHolder);
                if (idx <= 0 || idx >= SCELL_COLORS.length) continue;

                int targetColor = SCELL_COLORS[idx];

                // Also patch the color field in case the fragment is recreated
                multiColorField.set(elem, targetColor);

                try {
                    View v = (View) elementViewField.get(elem);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(targetColor);
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: applyLiveTextColors failed: " + e);
        }
    }

    /**
     * Build a map from dh0 object → SCell index for all Rank elements (u00 bindings).
     *
     * Strategy: collect ALL Rank bars regardless of column, sort globally by (col, row),
     * treat the first entry (smallest col, then smallest row) as the PCell and skip it,
     * then assign global SCell indices 0, 1, 2, … to the remaining elements in order.
     */
    private Map<Object, Integer> buildRankScellMap(ArrayList<?> elements) {
        Map<Object, Integer> result = new HashMap<>();
        ArrayList<Object> rankElems = new ArrayList<>();
        ArrayList<Float>  rankCols  = new ArrayList<>();
        ArrayList<Float>  rankRows  = new ArrayList<>();

        for (Object elem : elements) {
            if (!multiClass.isInstance(elem)) continue;
            try {
                ArrayList<?> bindings = (ArrayList<?>) multiBindingsField.get(elem);
                if (bindings == null || bindings.isEmpty()) continue;
                Object b = bindings.get(0);
                if (b == null || !rankBindingClass.isInstance(b)) continue;
                if ((int) rankCaseField.get(b) != RANK_CASE_ID) continue;
                rankElems.add(elem);
                rankCols.add((float) elementColField.get(elem));
                rankRows.add((float) elementRowField.get(elem));
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
     * rankMap overrides when provided (for u00 elements with ambiguous c=0).
     */
    private int resolveScellIndex(Object multiElem, Map<Object, Integer> rankMap) {
        return resolveScellIndex(multiElem, rankMap, null);
    }

    private int resolveScellIndex(Object multiElem, Map<Object, Integer> rankMap, String[] keyOut) {
        // Check Rank override map first
        if (rankMap != null && rankMap.containsKey(multiElem)) {
            if (keyOut != null) keyOut[0] = "rank";
            return rankMap.get(multiElem);
        }
        return readScellIndex(multiElem, keyOut);
    }

    private int readScellIndex(Object multiElem) {
        return readScellIndex(multiElem, null);
    }

    private int readScellIndex(Object multiElem, String[] keyOut) {
        try {
            ArrayList<?> bindings = (ArrayList<?>) multiBindingsField.get(multiElem);
            if (bindings == null || bindings.isEmpty()) return -1;

            int pcellCandidate = -1;
            String pcellKey = null;
            for (Object b : bindings) {
                String key = extractKey(b);
                int idx = indexFromKey(key);
                if (idx > 0) {
                    if (keyOut != null) keyOut[0] = key;
                    return idx;
                }
                if (idx == 0 && pcellCandidate < 0) {
                    pcellCandidate = 0;
                    pcellKey = key;
                }
            }
            if (keyOut != null) keyOut[0] = pcellKey;
            return pcellCandidate;
        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: readScellIndex failed: " + e);
            return -1;
        }
    }

    /**
     * Extract a property key string from a binding object.
     * First tries the declared String field "a" (l8.a),
     * then falls back to scanning all declared String fields for an NSG key.
     */
    private String extractKey(Object binding) {
        if (binding == null) return null;
        Class<?> cls = binding.getClass();

        try {
            Field f = cls.getDeclaredField("a");
            if (f.getType() == String.class) {
                f.setAccessible(true);
                Object val = f.get(binding);
                if (val instanceof String) return (String) val;
            }
        } catch (Exception ignored) {}

        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(binding);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.contains("::") || s.contains("SCell") || s.contains("PCell")) {
                            return s;
                        }
                    }
                } catch (Exception ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static final Pattern SCELL_PATTERN = Pattern.compile("SCell(\\d+)");

    /**
     * Parse the 0-based SCell index from a property key string.
     */
    private int indexFromKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        if (key.contains("PCell")) return 0;
        Matcher m = SCELL_PATTERN.matcher(key);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) - 1;
            } catch (NumberFormatException ignored) {}
        }
        if (key.contains("SCC")) return 0;
        return -1;
    }
}
