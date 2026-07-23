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
 * Colors SCell data-value labels (v6.g) on the LTE CA Matrix DL (e8.b) and
 * NR-NSA EUTRA CA Matrix (g8.i) pages by SCell index.
 *
 * Robust identification:
 *   - v6.b.k0(k2.a) is called synchronously from e8.b.n0() / g8.i.n0() while the
 *     LTE/EUTRA CA Matrix page is building its builder. We inspect the current
 *     thread's stack trace for the caller class "e8.b" or "g8.i" and mark the
 *     k2.a builder in a WeakHashMap.
 *   - v6.b.I() (onCreateView) fires later on the UI thread when the wrapped
 *     v6.b fragment creates its view. At that point this.Y is the k2.a builder,
 *     its element list is populated and f8100a holds live TextViews, so we walk
 *     the SCell elements and apply setTextColor.
 *
 * The old ThreadLocal + e8.b.n0()/g8.i.n0() flag approach is removed because
 * the builder-creation path is synchronous and can be identified directly from
 * the call stack, while the fragment-recreation path is handled by the same
 * v6.b.k0() / v6.b.I() hooks.
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

    private final String classLteCaMatrix;
    private final String classNrNsaEutraMatrix;

    private static final int[] SCELL_COLORS = {
        0xFFAA66CC, // SCell 1 — holo_purple (unchanged)
        0xFF00BCD4, // SCell 2 — turquoise
        0xFFFFEB3B, // SCell 3 — yellow
        0xFF4CAF50, // SCell 4 — green
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    /**
     * Marks k2.a builders that originated inside e8.b.n0() or g8.i.n0().
     * Populated by the v6.b.k0 hook using stack-trace inspection.
     * Used by the v6.b.I hook to decide whether to apply SCell colors.
     */
    private final Map<Object, Boolean> lteK2aBuilders =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private Class<?> vgClass;
    private Field vgBindingsField;   // v6.g "f": ArrayList<com.qtrun.sys.b>
    private Field vgColorField;      // v6.g "i": int text color
    private Field vgViewField;       // v6.a "a": View (TextView)
    private Field k2aElementsField;  // k2.a "d": ArrayList<v6.a>
    private Field v6bYField;         // v6.b "Y": k2.a builder stored on the fragment
    private Field v6aColField;       // v6.a "d" (f8103d): float column position
    private Field v6aRowField;       // v6.a "b" (f8101b): float row position
    private Class<?> rankBindingClass; // d7.i$k — Rank formatter; its c may be 0 for SCells

    private boolean ready = false;

    public LteCellColorHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        this.classLteCaMatrix = ClassMapping.runtimeName("e8.b", loader);
        this.classNrNsaEutraMatrix = ClassMapping.runtimeName("g8.i", loader);
        initReflection();
    }

    private void initReflection() {
        try {
            vgClass = ClassMapping.loadClass("v6.g", loader);
            Class<?> v6aClass  = ClassMapping.loadClass("v6.a", loader);
            Class<?> v6bClass  = ClassMapping.loadClass("v6.b", loader);
            Class<?> k2aClass  = ClassMapping.loadClass("k2.a", loader);

            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);

            vgColorField = vgClass.getDeclaredField("i");
            vgColorField.setAccessible(true);

            vgViewField = v6aClass.getDeclaredField("a");
            vgViewField.setAccessible(true);

            k2aElementsField = k2aClass.getDeclaredField("d");
            k2aElementsField.setAccessible(true);

            v6bYField = v6bClass.getDeclaredField("Y");
            v6bYField.setAccessible(true);

            v6aColField = v6aClass.getDeclaredField("d");
            v6aColField.setAccessible(true);

            v6aRowField = v6aClass.getDeclaredField("b");
            v6aRowField.setAccessible(true);

            rankBindingClass = ClassMapping.loadClass("d7.i$k", loader);
            if (rankBindingClass == null) {
                Log.w(TAG, "LteCellColorHook: d7.i$k not available, Rank fallback disabled");
            }

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
            installV6bK0Hook();
            installV6bIHook();
            Log.i(TAG, "LteCellColorHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: install failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 1: static v6.b.k0(k2.a) — identify LTE/EUTRA builders by call stack
    // -----------------------------------------------------------------------

    private void installV6bK0Hook() {
        try {
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);

            Method k0Method = ClassMapping.getMethod(v6bClass, "v6.b", "k0", loader, k2aClass);
            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object k2aArg = chain.getArg(0);
                    if (k2aArg == null) return result;

                    String sourceClass = findLteMatrixCaller();
                    if (sourceClass != null) {
                        lteK2aBuilders.put(k2aArg, Boolean.TRUE);
                        updateFieldColors(k2aArg);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: v6.b.k0 hook failed: " + e);
        }
    }

    /**
     * Walk the current thread's stack trace looking for the LTE CA Matrix or
     * NR-NSA EUTRA CA Matrix page class. This is reliable because v6.b.k0()
     * is called synchronously from e8.b.n0() / g8.i.n0() while the page is
     * constructing its builder.
     */
    private String findLteMatrixCaller() {
        try {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String cls = element.getClassName();
                if (classLteCaMatrix.equals(cls)) return classLteCaMatrix;
                if (classNrNsaEutraMatrix.equals(cls)) return classNrNsaEutraMatrix;
            }
        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: stack trace inspection failed: " + e);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Hook 2: v6.b.I(...) after-hook — apply live text colors to attached TextViews
    // -----------------------------------------------------------------------

    private void installV6bIHook() {
        try {
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);

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
                        if (k2aObj == null) {
                            Log.w(TAG, "LteCellColorHook: v6.b.I() has no k2.a builder (Y is null)");
                            return result;
                        }

                        if (!lteK2aBuilders.containsKey(k2aObj)) {
                            return result;
                        }

                        applyLiveTextColors(k2aObj, 0);
                    } catch (Exception e) {
                        Log.w(TAG, "LteCellColorHook: I-hook outer failed: " + e);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteCellColorHook: v6.b.I hook failed: " + e);
        }
    }

    /** Update f8126i on all SCell v6.g elements (scell index > 0) for future c() calls. */
    private void updateFieldColors(Object k2aObj) {
        try {
            ArrayList<?> elements = (ArrayList<?>) k2aElementsField.get(k2aObj);
            if (elements == null) {
                Log.w(TAG, "LteCellColorHook: updateFieldColors — no elements in builder");
                return;
            }
            int patched = 0;
            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            for (Object elem : elements) {
                if (!vgClass.isInstance(elem)) continue;
                int cur = (int) vgColorField.get(elem);
                if (cur != HOLO_PURPLE) continue;
                int idx = resolveScellIndex(elem, rankMap);
                if (idx > 0 && idx < SCELL_COLORS.length) {
                    vgColorField.set(elem, SCELL_COLORS[idx]);
                    patched++;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: updateFieldColors failed: " + e);
        }
    }

    /**
     * After v6.b.I() returns, c() has created fresh TextViews in f8100a.
     * Walk all v6.g elements with SCell index > 0 and call setTextColor on the live view.
     */
    private void applyLiveTextColors(Object k2aObj, int carriers) {
        try {
            ArrayList<?> elements = (ArrayList<?>) k2aElementsField.get(k2aObj);
            if (elements == null) {
                Log.w(TAG, "LteCellColorHook: applyLiveTextColors — no elements in builder");
                return;
            }

            Map<Object, Integer> rankMap = buildRankScellMap(elements);
            int applied = 0;
            int scellCount = 0;
            for (Object elem : elements) {
                if (!vgClass.isInstance(elem)) continue;

                String[] keyHolder = new String[1];
                int idx = resolveScellIndex(elem, rankMap, keyHolder);
                if (idx <= 0 || idx >= SCELL_COLORS.length) continue;
                scellCount++;

                int targetColor = SCELL_COLORS[idx];

                // Also patch f8126i in case fragment is recreated
                vgColorField.set(elem, targetColor);

                float col = 0f, row = 0f;
                try {
                    col = (float) v6aColField.get(elem);
                    row = (float) v6aRowField.get(elem);
                } catch (Exception ignored) {}

                try {
                    View v = (View) vgViewField.get(elem);
                    if (v instanceof TextView) {
                        ((TextView) v).setTextColor(targetColor);
                        applied++;
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            Log.w(TAG, "LteCellColorHook: applyLiveTextColors failed: " + e);
        }
    }

    /**
     * Build a map from v6.g object → SCell index for all Rank elements (d7.i$k bindings).
     *
     * Strategy: collect ALL Rank bars regardless of column, sort globally by (col, row),
     * treat the first entry (smallest col, then smallest row) as the PCell and skip it,
     * then assign global SCell indices 0, 1, 2, … to the remaining elements in order.
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
        return resolveScellIndex(vgObj, rankMap, null);
    }

    private int resolveScellIndex(Object vgObj, Map<Object, Integer> rankMap, String[] keyOut) {
        // Check Rank override map first
        if (rankMap != null && rankMap.containsKey(vgObj)) {
            if (keyOut != null) keyOut[0] = "rank";
            return rankMap.get(vgObj);
        }
        return readScellIndex(vgObj, keyOut);
    }

    private int readScellIndex(Object vgObj) {
        return readScellIndex(vgObj, null);
    }

    private int readScellIndex(Object vgObj, String[] keyOut) {
        try {
            ArrayList<?> bindings = (ArrayList<?>) vgBindingsField.get(vgObj);
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
     * First tries the declared String field "a" (f3868a on com.qtrun.sys.a/b),
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

    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { cls = cls.getSuperclass(); }
        }
        return null;
    }
}
