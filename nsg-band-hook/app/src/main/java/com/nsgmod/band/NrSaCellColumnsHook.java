package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks a8.i (NRSACellsFragment) to add two columns to the NR-SA cell table:
 *   - ARFCN  — inserted at column index 1 (before PCI), per-row value
 *   - BW     — inserted at column index 3 (after PCI, before Beam), serving rows only
 *
 * Also hides the sub-row (child[1] of the outer vertical LinearLayout per row)
 * which previously showed ARFCN/Band combined in tvRowRFID, reclaiming 22dp per row.
 *
 * Final top-row column order (8 columns):
 *   [0] Serving  [1] ARFCN  [2] PCI  [3] BW  [4] Beam  [5] RSRP  [6] RSRQ  [7] SINR
 *
 * Data path (same as LteBandwidthColumnHook):
 *   Workspace.j → singleton; .a → moduleIndex; .c → DataSource; .g → cursor time
 *   DataSource.getProperty(key, moduleIndex) → Property
 *   Property.b(cursorTime) → Iterator; .key() → long; Property.get(key) → Object
 *
 * Adapter row→source mapping:
 *   a8.d$b.g(int position) → Pair<a8.d.a source, Integer intraRow>
 *   pair.first == sources[0]  → serving bucket (PCell + SCells)
 *   pair.second               → intraRow: 0=PCell, 1=SCell1, 2=SCell2 …
 *
 * Keys:
 *   ARFCN serving    : NR5G::Cell_Measurements::NR_Cells_ARFCN           (Integer[], index=intraRow, covers ALL cells incl. non-metrics SCells)
 *   ARFCN detected   : NR5G::Detected_Cells::NR_DetectedCells_ARFCN      (Integer[], index=intraRow)
 *   BW PCell         : NR5G::Serving_Cell::NR_Bandwidth_DL               (direct MHz int)
 *   BW SCell N       : NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL  (index N-1)
 *
 * NOTE: Do NOT use NR5G::Serving_Cell::NR_ARFCN_SSB / NR_SCell_ARFCN_SSB for ARFCN.
 * Those are the metrics-page (g8.m / NRNSANRCC) keys which only cover SCells visible
 * on the NR-SA metrics tab. Aggregated SCells not on that page will show "-".
 */
public class NrSaCellColumnsHook {

    private static final String TAG              = "NSGBandHook_NrSa";
    private static final String TAG_COLS_INJECTED = "nsg_nrsa_cols_injected";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // Workspace / DataSource reflection (shared pattern with LTE hook)
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;

    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;   // Property$Iterator.key() → long
    private Method iterValueMethod;

    // Adapter reflection — a8.d$b
    private Field  sourcesField; // actual bytecode name "d"  (JADX: f135d) — a8.d.a[]
    private Method gMethod;      // g(int) → Pair<a8.d.a, Integer>

    private boolean ready = false;
    /**
     * Sample key captured from a8.d.b(DataSource, long j10, …) callback.
     * a8.d.a extends k8.b, NOT k8.c, so there is no f5509c field to read.
     * Instead we hook the data-callback to capture j10 before getView() fires.
     */
    private volatile long lastNrSaSampleKey = 0L;

    public NrSaCellColumnsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> wsClass   = loader.loadClass("com.qtrun.sys.Workspace");
            Class<?> dsClass   = loader.loadClass("com.qtrun.sys.DataSource");
            Class<?> propClass = loader.loadClass("com.qtrun.sys.Property");
            Class<?> iterClass = loader.loadClass("com.qtrun.sys.Property$Iterator");
            Class<?> dbClass   = loader.loadClass("a8.d$b");

            wsSingleton    = wsClass.getField("j");
            wsModuleIndex  = wsClass.getField("a");
            wsDataSource   = wsClass.getField("c");

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            sourcesField = dbClass.getField("d");   // a8.d.a[] sources
            gMethod      = dbClass.getMethod("g", int.class);
            // NOTE: a8.d.a extends k8.b (not k8.c), so there is no f5509c field.
            // Sample key is captured via hookDataCallback() instead.

            ready = true;
            Log.i(TAG, "NrSaCellColumnsHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCellColumnsHook: skipping install — reflection not ready");
            return;
        }
        hookDataCallback();
        hookGetView();
        hookOnCreateView();
        hookH8bQ();
    }

    // -----------------------------------------------------------------------
    // Data-callback hook — captures the sample key used to fill the buffer
    // -----------------------------------------------------------------------

    /**
     * Hook a8.d.b(DataSource, long j10, short, Object) — the Workspace.a callback
     * invoked when new data arrives.  Captures j10 (arg index 1) into lastNrSaSampleKey.
     *
     * This runs BEFORE notifyDataSetChanged() / getView(), so by the time getView()
     * fires the field already holds the correct sample key for this render cycle.
     *
     * All a8.d subclasses share this callback; capturing from any of them is fine
     * because they all receive the same monotonically-increasing sample key stream.
     */
    private void hookDataCallback() {
        try {
            Class<?> dClass  = loader.loadClass("a8.d");
            Class<?> dsClass = loader.loadClass("com.qtrun.sys.DataSource");
            Method   bMethod = dClass.getDeclaredMethod("b",
                    dsClass, long.class, short.class, Object.class);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    long sk = (long) chain.getArg(1);
                    if (sk > 0) lastNrSaSampleKey = sk;
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColumnsHook: hookDataCallback installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookDataCallback failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Data reading
    // -----------------------------------------------------------------------

    /** Read ARFCN for a row.
     *
     * Uses the same Cell_Measurements keys that a8.i (NRSACellsFragment) itself registers:
     *   serving  → NR5G::Cell_Measurements::NR_Cells_ARFCN       (Integer[], index = intraRow)
     *   detected → NR5G::Detected_Cells::NR_DetectedCells_ARFCN  (Integer[], index = intraRow)
     *
     * Do NOT use NR5G::Serving_Cell::NR_ARFCN_SSB or NR_SCell_ARFCN_SSB here.
     * Those are the metrics-page (Serving_Cell namespace) keys used by g8.m (NRNSANRCC).
     * SCells that are aggregated but not shown on the metrics page have no entry there,
     * so they would show "-". The Cell_Measurements array covers all cells in the table.
     *
     * @param isServing true = serving bucket (PCell/SCells); false = detected/neighbour bucket
     * @param intraRow  bucket-relative row index (0-based; 0=PCell, 1=SCell1, 2=SCell2 …)
     */
    private String readArfcn(boolean isServing, int intraRow, long queryTime) {
        if (!isServing) {
            return readPropertyWithIndex(
                    "NR5G::Detected_Cells::NR_DetectedCells_ARFCN", intraRow, queryTime);
        }
        // Both PCell (intraRow=0) and SCells (intraRow≥1) are in the same flat array.
        return readPropertyWithIndex(
                "NR5G::Cell_Measurements::NR_Cells_ARFCN", intraRow, queryTime);
    }

    /**
     * Read DL bandwidth for a serving row.
     * @param intraRow 0=PCell, ≥1=SCell (1-based index: SCell1 = intraRow 1)
     */
    private String readBandwidth(int intraRow, long queryTime) {
        String key;
        if (intraRow == 0) {
            key = "NR5G::Serving_Cell::NR_Bandwidth_DL";
        } else {
            key = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
        }
        return readPropertyWithIndex(key, intraRow > 0 ? intraRow - 1 : -1, queryTime);
    }

    private String readPropertyWithIndex(String key, int scellIndex, long queryTime) {
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) { Log.w(TAG, "readProp: Workspace null"); return null; }
            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) { Log.w(TAG, "readProp: DataSource null"); return null; }

            Object property = dsGetProperty.invoke(dataSource, key, moduleIndex);
            if (property == null) return null;

            Object iterator = propIterMethod.invoke(property, queryTime);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) {
                return null;
            }

            // Use iter.value() — matches NSG's own h8/b.java read pattern.
            // property.get(sk) returns null for array-type properties (e.g. SCell BW
            // Object[]) even though the iterator IS positioned at a valid sample key.
            long   sampleKey = (long) iterKeyMethod.invoke(iterator);
            Object value     = iterValueMethod.invoke(iterator);
            if (value == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iterator = propIterMethod.invoke(property, sampleKey - 1);
                if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;
                sampleKey = (long) iterKeyMethod.invoke(iterator);
                value = iterValueMethod.invoke(iterator);
                if (value == null) {
                    return null;
                }
            }

            String extracted = extractArrayValue(value, scellIndex);
            if (extracted == null && value != null) {
                // Array element is null — prev-tick fallback.
                Object prevIter = propIterMethod.invoke(property, sampleKey - 1);
                if (prevIter != null && !(boolean) iterEndMethod.invoke(prevIter)) {
                    Object prevVal = iterValueMethod.invoke(prevIter);
                    extracted = extractArrayValue(prevVal, scellIndex);
                }
            }
            return extracted;
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook.readProperty(" + key + ") failed: " + e);
            return null;
        }
    }

    /** Extract a value from scalar or array Property values. */
    private String extractArrayValue(Object value, int idx) {
            // ARFCN property returns Integer[] (one value per cell); index by scellIndex
            if (value instanceof Integer[]) {
                Integer[] arr = (Integer[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length && arr[i] != null) return String.valueOf(arr[i]);
                return null;
            }
            if (value instanceof int[]) {
                int[] arr = (int[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length) return String.valueOf(arr[i]);
                return null;
            }
            // Object[] containing boxed Numbers (seen on some devices)
            if (value instanceof Object[]) {
                Object[] arr = (Object[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length && arr[i] instanceof Number)
                    return String.valueOf(((Number) arr[i]).intValue());
                return null;
            }
            if (value instanceof Integer) return String.valueOf((Integer) value);
            if (value instanceof Long)    return String.valueOf((Long) value);
            if (value instanceof Float)   return String.valueOf(((Float) value).intValue());
            if (value instanceof Double)  return String.valueOf(((Double) value).intValue());
            return value.toString();
    }

    // -----------------------------------------------------------------------
    // Hook 1: a8.i$a.getView — inject ARFCN + BW columns, hide sub-row
    // -----------------------------------------------------------------------

    private void hookGetView() {
        try {
            Class<?> adapterClass  = loader.loadClass("a8.i$a");
            Method   getViewMethod = adapterClass.getMethod("getView",
                    int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result   = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    int    position = (int) chain.getArg(0);
                    View   rowView  = (View) result;
                    if (rowView == null) return result;

                    // Outer layout must be vertical with 2 children (top row + sub-row)
                    if (!(rowView instanceof LinearLayout)) return result;
                    LinearLayout outer = (LinearLayout) rowView;
                    if (outer.getOrientation() != LinearLayout.VERTICAL
                            || outer.getChildCount() < 2) return result;

                    // Top horizontal row (child[0])
                    View topChild = outer.getChildAt(0);
                    if (!(topChild instanceof LinearLayout)) return result;
                    LinearLayout topRow = (LinearLayout) topChild;
                    if (topRow.getOrientation() != LinearLayout.HORIZONTAL) return result;

                    // Match NR-NSA row height: container 22dp, children 21dp
                    android.content.Context ctx = rowView.getContext();
                    float density = ctx.getResources().getDisplayMetrics().density;
                    int   rowH    = (int) (21 * density + 0.5f);
                    int   topH    = (int) (22 * density + 0.5f);
                    ViewGroup.LayoutParams topRowLp = topRow.getLayoutParams();
                    if (topRowLp != null) {
                        topRowLp.height = topH;
                        topRow.setLayoutParams(topRowLp);
                    }

                    // Sub-row (child[1]) — hide it always
                    View subRow = outer.getChildAt(1);
                    if (subRow != null) subRow.setVisibility(View.GONE);

                    // Resolve serving/intraRow
                    boolean isServing = false;
                    int     intraRow  = 0;
                    // queryTime: use lastNrSaSampleKey captured from the a8.d.b() data
                    // callback (set before notifyDataSetChanged → getView).  Falls back
                    // to Long.MAX_VALUE on first render before any callback fires
                    // (live mode: MAX_VALUE = latest data = correct).
                    long    queryTime = lastNrSaSampleKey > 0 ? lastNrSaSampleKey : Long.MAX_VALUE;
                    try {
                        Object   adapter = chain.getThisObject();
                        Object[] sources = (Object[]) sourcesField.get(adapter);
                        android.util.Pair<?, ?> pair =
                                (android.util.Pair<?, ?>) gMethod.invoke(adapter, position);
                        if (pair != null && pair.second != null) {
                            intraRow  = (int) pair.second;
                            isServing = (pair.first != null && sources != null
                                    && sources.length > 0 && pair.first == sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NrSaCellColumnsHook: g(position) failed: " + e);
                    }

                    // Read values using adapter-aligned sample key (not Workspace.g).
                    String arfcnText = "-";
                    String arfcn = readArfcn(isServing, intraRow, queryTime);
                    if (arfcn != null) arfcnText = arfcn;

                    String bwText = "-";
                    if (isServing) {
                        String bw = readBandwidth(intraRow, queryTime);
                        if (bw != null) bwText = bw;
                    }

                    // Already injected — refresh text and color
                    // (NSG's k0() has already set pciView's color correctly for this row)
                    if (TAG_COLS_INJECTED.equals(topRow.getTag())) {
                        if (topRow.getChildCount() >= 8) {
                            // After injection layout is: [0]Serving [1]ARFCN [2]PCI [3]BW ...
                            TextView pciViewR  = (TextView) topRow.getChildAt(2);
                            TextView tvArfcn   = (TextView) topRow.getChildAt(1);
                            TextView tvBw      = (TextView) topRow.getChildAt(3);
                            android.content.res.ColorStateList colors = pciViewR.getTextColors();
                            tvArfcn.setText(arfcnText);
                            tvArfcn.setTextColor(colors);
                            tvBw.setText(bwText);
                            tvBw.setTextColor(colors);
                        }
                        return result;
                    }

                    // First time — must have exactly 6 children
                    if (topRow.getChildCount() != 6) return result;

                    // Copy style from PCI column (child[1]) for new text views
                    TextView pciView = (TextView) topRow.getChildAt(1);

                    // Resize all 6 pre-existing NSG children from 26dp to 21dp to match NR-NSA
                    for (int i = 0; i < topRow.getChildCount(); i++) {
                        ViewGroup.LayoutParams lp = topRow.getChildAt(i).getLayoutParams();
                        if (lp != null) {
                            lp.height = rowH;
                            topRow.getChildAt(i).setLayoutParams(lp);
                        }
                    }

                    // --- ARFCN TextView ---
                    TextView tvArfcn = new TextView(ctx);
                    tvArfcn.setText(arfcnText);
                    tvArfcn.setGravity(android.view.Gravity.CENTER);
                    tvArfcn.setMaxLines(1);
                    tvArfcn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            pciView.getTextSize());
                    tvArfcn.setTypeface(pciView.getTypeface());
                    tvArfcn.setTextColor(pciView.getTextColors());
                    LinearLayout.LayoutParams lpArfcn = new LinearLayout.LayoutParams(0, rowH);
                    lpArfcn.weight = 0.18f;
                    lpArfcn.gravity = android.view.Gravity.CENTER_VERTICAL;
                    tvArfcn.setLayoutParams(lpArfcn);

                    // --- BW TextView ---
                    TextView tvBw = new TextView(ctx);
                    tvBw.setText(bwText);
                    tvBw.setGravity(android.view.Gravity.CENTER);
                    tvBw.setMaxLines(1);
                    tvBw.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            pciView.getTextSize());
                    tvBw.setTypeface(pciView.getTypeface());
                    tvBw.setTextColor(pciView.getTextColors());
                    LinearLayout.LayoutParams lpBw = new LinearLayout.LayoutParams(0, rowH);
                    lpBw.weight = 0.10f;
                    lpBw.gravity = android.view.Gravity.CENTER_VERTICAL;
                    tvBw.setLayoutParams(lpBw);

                    // Insert: ARFCN at index 1 (before PCI), BW at index 3 (after PCI)
                    topRow.addView(tvArfcn, 1);
                    topRow.addView(tvBw, 3);

                    // Redistribute weights across all 8 columns:
                    // [Serving, ARFCN, PCI, BW, Beam, RSRP, RSRQ, SINR]
                    float[] weights = {0.03f, 0.18f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f};
                    for (int i = 0; i < topRow.getChildCount() && i < weights.length; i++) {
                        LinearLayout.LayoutParams lp =
                                (LinearLayout.LayoutParams) topRow.getChildAt(i).getLayoutParams();
                        lp.weight = weights[i];
                        topRow.getChildAt(i).setLayoutParams(lp);
                    }

                    topRow.setTag(TAG_COLS_INJECTED);
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColumnsHook: hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: a8.i.I (onCreateView) — inject header labels after layout posted
    // -----------------------------------------------------------------------

    private void hookOnCreateView() {
        try {
            Class<?> fragClass = loader.loadClass("a8.i");
            Method   iMethod   = fragClass.getMethod("I",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result       = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View   fragmentView = (View) result;
                    if (fragmentView == null) return result;

                    int listId = fragmentView.getContext().getResources()
                            .getIdentifier("list_1", "id",
                                    fragmentView.getContext().getPackageName());
                    if (listId == 0) {
                        Log.w(TAG, "NrSaCellColumnsHook: list_1 id not found");
                        return result;
                    }
                    ListView listView = (ListView) fragmentView.findViewById(listId);
                    if (listView == null) {
                        Log.w(TAG, "NrSaCellColumnsHook: ListView not found");
                        return result;
                    }

                    listView.post(() -> injectHeaders(listView));
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColumnsHook: hookOnCreateView installed");
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectHeaders(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL) continue;
                if (ll.getChildCount() != 6) continue;
                if (TAG_COLS_INJECTED.equals(ll.getTag())) return;

                android.content.Context ctx = listView.getContext();

                // ARFCN header at index 1
                TextView tvArfcnHdr = makeHeaderLabel(ctx, "ARFCN", 0.18f);
                ll.addView(tvArfcnHdr, 1);

                // BW header at index 3
                TextView tvBwHdr = makeHeaderLabel(ctx, "BW", 0.10f);
                ll.addView(tvBwHdr, 3);

                // Redistribute header weights to match row weights
                float[] weights = {0.03f, 0.18f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    lp.weight = weights[j];
                    ll.getChildAt(j).setLayoutParams(lp);
                }

                ll.setTag(TAG_COLS_INJECTED);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook: injectHeaders failed: " + e);
        }
    }

    private TextView makeHeaderLabel(android.content.Context ctx, String text, float weight) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setTextColor(resolveColor(ctx, android.R.attr.textColorTertiary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        tv.setLayoutParams(lp);
        return tv;
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }

    // -----------------------------------------------------------------------
    // Hook 3: h8.b.Q() — force o0() rebuild when NR-SA CA Matrix becomes visible
    // -----------------------------------------------------------------------

    private void hookH8bQ() {
        try {
            Class<?> cls    = loader.loadClass("h8.b");
            Method   qMeth  = cls.getDeclaredMethod("Q");
            qMeth.setAccessible(true);
            Method   o0Meth = cls.getDeclaredMethod("o0");
            o0Meth.setAccessible(true);

            xposed.hook(qMeth).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result   = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    Object fragment = chain.getThisObject();
                    try {
                        o0Meth.invoke(fragment);
                    } catch (Throwable t) {
                        Log.w(TAG, "NrSaCellColumnsHook: h8.b.o0() invoke failed: " + t);
                    }
                    return result;
                }
            });
            Log.i(TAG, "NrSaCellColumnsHook: hookH8bQ installed");
        } catch (Throwable t) {
            Log.e(TAG, "NrSaCellColumnsHook: hookH8bQ failed: " + t);
        }
    }
}
