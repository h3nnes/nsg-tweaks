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
 *   ARFCN serving    : NR5G::Cell_Measurements::NR_Cells_ARFCN       (Integer[], index=intraRow)
 *   ARFCN detected   : NR5G::Detected_Cells::NR_DetectedCells_ARFCN  (Integer[], index=intraRow)
 *   BW PCell         : NR5G::Serving_Cell::NR_Bandwidth_DL           (direct MHz int)
 *   BW SCell N       : NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL  (index N-1)
 */
public class NrSaCellColumnsHook {

    private static final String TAG              = "NSGBandHook";
    private static final String TAG_COLS_INJECTED = "nsg_nrsa_cols_injected";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // Workspace / DataSource reflection (shared pattern with LTE hook)
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Field  wsCurrentTime;  // Workspace.g → long (replay cursor; -1 = live/unset)
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method propGetMethod;   // Property.get(long key) → Object
    private Method iterEndMethod;
    private Method iterKeyMethod;   // Property$Iterator.key() → long
    private Method iterValueMethod; // kept as fallback

    // Adapter reflection — a8.d$b
    private Field  sourcesField; // actual bytecode name "d"  (JADX: f135d) — a8.d.a[]
    private Method gMethod;      // g(int) → Pair<a8.d.a, Integer>

    private boolean ready = false;

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
            wsCurrentTime  = wsClass.getField("g");  // long replay cursor

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            propGetMethod  = propClass.getMethod("get", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            sourcesField = dbClass.getField("d");   // a8.d.a[] sources
            gMethod      = dbClass.getMethod("g", int.class);

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
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // Data reading
    // -----------------------------------------------------------------------

    /** Read ARFCN for a row.
     * @param isServing true = serving bucket (PCell/SCells); false = detected/neighbour bucket
     * @param intraRow  bucket-relative row index (0-based)
     */
    private String readArfcn(boolean isServing, int intraRow) {
        String key = isServing
                ? "NR5G::Cell_Measurements::NR_Cells_ARFCN"
                : "NR5G::Detected_Cells::NR_DetectedCells_ARFCN";
        return readPropertyWithIndex(key, intraRow);
    }

    /**
     * Read DL bandwidth for a serving row.
     * @param intraRow 0=PCell, ≥1=SCell (1-based index: SCell1 = intraRow 1)
     */
    private String readBandwidth(int intraRow) {
        String key;
        if (intraRow == 0) {
            key = "NR5G::Serving_Cell::NR_Bandwidth_DL";
        } else {
            key = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
        }
        return readPropertyWithIndex(key, intraRow > 0 ? intraRow - 1 : -1);
    }

    private String readPropertyWithIndex(String key, int scellIndex) {
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) return null;
            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) return null;

            // Use replay cursor time so values track the playback position.
            // wsCurrentTime (Workspace.g) is -1 when live; fall back to Long.MAX_VALUE.
            long cursorTime = wsCurrentTime.getLong(ws);
            long queryTime  = (cursorTime >= 0) ? cursorTime : Long.MAX_VALUE;

            Object property = dsGetProperty.invoke(dataSource, key, moduleIndex);
            if (property == null) return null;

            Object iterator = propIterMethod.invoke(property, queryTime);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;

            // Use property.get(iterator.key()) — matches NSG's own k8.b.f() read pattern.
            long   sampleKey = (long) iterKeyMethod.invoke(iterator);
            Object value     = propGetMethod.invoke(property, sampleKey);
            if (value == null) return null;

            // ARFCN property returns Integer[] (one value per cell); index by scellIndex
            if (value instanceof Integer[]) {
                Integer[] arr = (Integer[]) value;
                int idx = scellIndex >= 0 ? scellIndex : 0;
                if (idx < arr.length && arr[idx] != null) return String.valueOf(arr[idx]);
                return null;
            }
            if (value instanceof int[]) {
                int[] arr = (int[]) value;
                int idx = scellIndex >= 0 ? scellIndex : 0;
                if (idx < arr.length) return String.valueOf(arr[idx]);
                return null;
            }
            if (value instanceof Integer) return String.valueOf((Integer) value);
            if (value instanceof Long)    return String.valueOf((Long) value);
            if (value instanceof Float)   return String.valueOf(((Float) value).intValue());
            if (value instanceof Double)  return String.valueOf(((Double) value).intValue());
            return value.toString();
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook.readProperty(" + key + ") failed: " + e);
            return null;
        }
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

                    // Sub-row (child[1]) — hide it always
                    View subRow = outer.getChildAt(1);
                    if (subRow != null) subRow.setVisibility(View.GONE);

                    // Resolve serving/intraRow
                    boolean isServing = false;
                    int     intraRow  = 0;
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

                    // Read values
                    String arfcnText = "-";
                    String arfcn = readArfcn(isServing, intraRow);
                    if (arfcn != null) arfcnText = arfcn;

                    String bwText = "-";
                    if (isServing) {
                        String bw = readBandwidth(intraRow);
                        if (bw != null) bwText = bw;
                    }

                    // Already injected — refresh text only
                    if (TAG_COLS_INJECTED.equals(topRow.getTag())) {
                        if (topRow.getChildCount() >= 8) {
                            TextView tvArfcn = (TextView) topRow.getChildAt(1);
                            TextView tvBw    = (TextView) topRow.getChildAt(3);
                            tvArfcn.setText(arfcnText);
                            tvBw.setText(bwText);
                        }
                        return result;
                    }

                    // First time — must have exactly 6 children
                    if (topRow.getChildCount() != 6) return result;

                    // Copy style from PCI column (child[1]) for new text views
                    TextView pciView = (TextView) topRow.getChildAt(1);
                    android.content.Context ctx = rowView.getContext();
                    float density = ctx.getResources().getDisplayMetrics().density;
                    int   rowH    = (int) (26 * density + 0.5f); // match XML layout_height="26dp"

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
                    Log.d(TAG, "NrSaCellColumnsHook: injected cols pos=" + position
                            + " arfcn=" + arfcnText + " bw=" + bwText);
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
                Log.i(TAG, "NrSaCellColumnsHook: header injected");
                return;
            }
            Log.w(TAG, "NrSaCellColumnsHook: header row not found ("
                    + listView.getChildCount() + " children)");
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
}
