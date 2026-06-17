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
 * Hooks a8.h$a (NRNSACellsFragment adapter) to inject an ARFCN column (labelled "Band")
 * at index 1 of the main NR cell row, and removes the NSG info sub-row.
 *
 * ARFCN is read directly from the DataSource via Workspace/Property reflection —
 * the same pattern as NrNsaBandwidthColumnHook and NrSaCellColumnsHook.
 * This avoids dependence on NSG's sub-row UI, which is not reliably populated
 * during log replay.
 *
 * Signal paths:
 *   serving  : NR5G::Cell_Measurements::NR_Cells_ARFCN       (Integer[], index=intraRow)
 *   detected : NR5G::Detected_Cells::NR_DetectedCells_ARFCN  (Integer[], index=intraRow)
 *
 * Row-to-intraRow mapping uses a8.b$b.h(position) → Pair<source, intraRow>
 * and a8.b$b.e (Object[] sources array).
 *
 * In NSG v4.8.6 the row layout is nr_cell_list_row_double_nsa — a vertical LinearLayout:
 *   child 0: horizontal main row  [Serving, PCI, Beam, RSRP, RSRQ, SINR]  (6 cols)
 *   child 1: horizontal info strip [tvRowRFID=ARFCN, tvRowCellName, tvRowCellID]
 *
 * After injection:
 *   outer LL child 0 only; main row: [Serving, ARFCN("Band"), PCI, Beam, RSRP, RSRQ, SINR] (7 cols)
 *   NrNsaBandwidthColumnHook then adds BW at index 2 → 8 cols.
 */
public class BandColumnHook {

    private static final String TAG = "NSGBandHook_Band";

    // Integer tag key used on the main-row LinearLayout to mark injection state.
    private static final int    BAND_TAG_KEY      = "nsg_band_injected".hashCode();
    private static final String TAG_BAND_INJECTED = "nsg_band_injected";

    // Tag slot: store the injected ARFCN TextView reference for O(1) update on recycle.
    private static final int ARFCN_VIEW_TAG_KEY = "nsg_band_arfcn_view".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // Workspace / DataSource / Property reflection — resolved once.
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;
    private Method iterValueMethod;

    // Adapter intraRow resolution — a8.b$b (base class of a8.h$a)
    private Field  eField;   // a8.b$b.e — Object[] sources array
    private Method hMethod;  // a8.b$b.h(int) — returns Pair<source, intraRow>

    private boolean reflectionReady = false;
    /** k8.c field "c" (f5509c) — the data sample key the adapter used for its current data. */
    private Field  f5509cField;

    public BandColumnHook(XposedInterface xposed, ClassLoader loader) {
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
            Class<?> bbClass   = loader.loadClass("a8.b$b");

            wsSingleton    = wsClass.getField("j");
            wsModuleIndex  = wsClass.getField("a");
            wsDataSource   = wsClass.getField("c");

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            eField  = bbClass.getField("e");   // Object[] sources array
            hMethod = bbClass.getMethod("h", int.class);  // Pair<source, intraRow>
            f5509cField = loader.loadClass("k8.c").getField("c"); // data sample key (f5509c)

            reflectionReady = true;
            Log.i(TAG, "BandColumnHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "BandColumnHook: initReflection failed: " + e);
        }
    }

    public void install() {
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // ARFCN reading — direct DataSource access, no sub-row UI dependency
    // -----------------------------------------------------------------------

    /**
     * Read the NR ARFCN for a given cell row from the DataSource.
     *
     * @param isServing true = serving bucket (PCell/SCells); false = detected/neighbour
     * @param intraRow  row index within the bucket (0-based)
     */
    private String readArfcn(boolean isServing, int intraRow, long queryTime) {
        String key = isServing
                ? "NR5G::Cell_Measurements::NR_Cells_ARFCN"
                : "NR5G::Detected_Cells::NR_DetectedCells_ARFCN";
        return readPropertyWithIndex(key, intraRow, queryTime);
    }

    private String readPropertyWithIndex(String key, int idx, long queryTime) {
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) { Log.w(TAG, "Workspace null"); return null; }
            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object ds          = wsDataSource.get(ws);
            if (ds == null) { Log.w(TAG, "DataSource null"); return null; }

            Object prop = dsGetProperty.invoke(ds, key, moduleIndex);
            if (prop == null) return null;

            Object iter = propIterMethod.invoke(prop, queryTime);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) {
                return null;
            }

            long   sampleKey = (long) iterKeyMethod.invoke(iter);
            Object value     = iterValueMethod.invoke(iter);

            if (value == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iter = propIterMethod.invoke(prop, sampleKey - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                sampleKey = (long) iterKeyMethod.invoke(iter);
                value = iterValueMethod.invoke(iter);
                if (value == null) return null;
            }

            String extracted = extractArrayValue(value, idx);
            if (extracted == null && value != null) {
                // Array element is null — prev-tick fallback.
                Object prevIter = propIterMethod.invoke(prop, sampleKey - 1);
                if (prevIter != null && !(boolean) iterEndMethod.invoke(prevIter)) {
                    Object prevVal = iterValueMethod.invoke(prevIter);
                    extracted = extractArrayValue(prevVal, idx);
                }
            }
            return extracted;
        } catch (Exception e) {
            Log.w(TAG, "readPropertyWithIndex(" + key + ") failed: " + e);
            return null;
        }
    }

    private String extractArrayValue(Object value, int idx) {
        if (value == null) return null;
        if (value instanceof Integer[]) {
            Integer[] arr = (Integer[]) value;
            if (idx >= 0 && idx < arr.length && arr[idx] != null) return String.valueOf(arr[idx]);
            return null;
        }
        if (value instanceof int[]) {
            int[] arr = (int[]) value;
            if (idx >= 0 && idx < arr.length) return String.valueOf(arr[idx]);
            return null;
        }
        // Object[] containing boxed Numbers (seen on some devices)
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            if (idx >= 0 && idx < arr.length && arr[idx] instanceof Number)
                return String.valueOf(((Number) arr[idx]).intValue());
            return null;
        }
        if (value instanceof Integer) return String.valueOf((Integer) value);
        if (value instanceof Long)    return String.valueOf((Long) value);
        return value.toString();
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h$a.getView
    // -----------------------------------------------------------------------
    private void hookGetView() {
        try {
            Class<?> adapterClass  = loader.loadClass("a8.h$a");
            Method   getViewMethod = adapterClass.getMethod("getView",
                    int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Let NSG handle convertView recycling normally.
                    Object result = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View rowView = (View) result;
                    if (rowView == null) return result;

                    // Only handle vertical outer-LL (nr_cell_list_row_double_nsa).
                    if (!(rowView instanceof LinearLayout)) return result;
                    LinearLayout outerVert = (LinearLayout) rowView;
                    if (outerVert.getOrientation() != LinearLayout.VERTICAL) return result;
                    if (outerVert.getChildCount() < 1) return result;

                    View firstChild = outerVert.getChildAt(0);
                    if (!(firstChild instanceof LinearLayout)) return result;
                    LinearLayout mainRow = (LinearLayout) firstChild;
                    if (mainRow.getOrientation() != LinearLayout.HORIZONTAL) return result;

                    // Hide the info sub-row (child[1]) if present — reclaims vertical space.
                    if (outerVert.getChildCount() >= 2)
                        outerVert.getChildAt(1).setVisibility(View.GONE);

                    // Resolve serving/intraRow via adapter reflection.
                    int     position  = (int) chain.getArg(0);
                    boolean isServing = false;
                    int     intraRow  = position;
                    // queryTime: use f5509c (adapter's own sample key) when > 0, otherwise
                    // fall back to Long.MAX_VALUE. f5509c is set by a8.b$b.g() before
                    // notifyDataSetChanged(), so it is valid by the time getView fires.
                    // Do NOT use Workspace.g — it is frozen at the first post-event dispatch
                    // and never advances; new ticks land at sk > wsG and Property.b(wsG)
                    // cannot see them. Long.MAX_VALUE fallback handles reset state (f5509c=-1).
                    long    queryTime = Long.MAX_VALUE;
                    if (reflectionReady) {
                        try {
                            Object   adapter = chain.getThisObject();
                            Object[] sources = (Object[]) eField.get(adapter);
                            android.util.Pair<?, ?> pair =
                                    (android.util.Pair<?, ?>) hMethod.invoke(adapter, position);
                            if (pair != null && pair.second != null) {
                                intraRow  = (int) pair.second;
                                isServing = (pair.first != null && sources != null
                                        && sources.length > 0 && pair.first == sources[0]);
                            }
                            if (f5509cField != null && sources != null && sources.length > 0) {
                                long sk = f5509cField.getLong(sources[0]);
                                if (sk > 0) queryTime = sk;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "h(position) failed: " + e);
                        }
                    }

                    // Read ARFCN using adapter-aligned sample key (not Workspace.g).
                    String arfcn = readArfcn(isServing, intraRow, queryTime);
                    String arfcnText = (arfcn != null) ? arfcn : "-";

                    // ---- Already-injected fast path (recycled view) ----
                    Object arfcnTag = mainRow.getTag(ARFCN_VIEW_TAG_KEY);
                    if (arfcnTag instanceof TextView) {
                        TextView arfcnView = (TextView) arfcnTag;
                        arfcnView.setText(arfcnText);
                        // Re-read color from PCI (child[2] after injection) each time.
                        android.content.res.ColorStateList color = pciColor(mainRow);
                        if (color != null) arfcnView.setTextColor(color);
                        return result;
                    }

                    // ---- First-injection path (fresh inflated view, 6-child main row) ----
                    if (mainRow.getChildCount() != 6) return result;

                    View refRaw = mainRow.getChildAt(1); // PCI column
                    if (!(refRaw instanceof TextView)) return result;
                    TextView refView = (TextView) refRaw;

                    TextView arfcnView = new TextView(rowView.getContext());
                    arfcnView.setText(arfcnText);
                    arfcnView.setGravity(android.view.Gravity.CENTER);
                    arfcnView.setMaxLines(1);
                    arfcnView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            refView.getTextSize());
                    arfcnView.setTypeface(refView.getTypeface());
                    arfcnView.setTextColor(refView.getTextColors());

                    float density = rowView.getContext().getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * density));
                    lp.weight = 0.12f;
                    arfcnView.setLayoutParams(lp);

                    mainRow.addView(arfcnView, 1);

                    // Redistribute 7 columns. NrNsaBandwidthColumnHook redistributes to 8.
                    float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                    for (int i = 0; i < 7 && i < mainRow.getChildCount(); i++) {
                        View child = mainRow.getChildAt(i);
                        ViewGroup.LayoutParams rawLp = child.getLayoutParams();
                        if (!(rawLp instanceof LinearLayout.LayoutParams)) continue;
                        ((LinearLayout.LayoutParams) rawLp).weight = weights[i];
                        child.setLayoutParams(rawLp);
                    }

                    mainRow.setTag(ARFCN_VIEW_TAG_KEY, arfcnView);
                    mainRow.setTag(BAND_TAG_KEY, TAG_BAND_INJECTED);
                    return result;
                }
            });
            Log.i(TAG, "BandColumnHook: hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "BandColumnHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h.I — inject "Band" header label
    // -----------------------------------------------------------------------
    private void hookOnCreateView() {
        try {
            Class<?> fragClass = loader.loadClass("a8.h");
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
                            .getIdentifier("list_1", "id", "com.qtrun.QuickTest");
                    if (listId == 0) return result;

                    ListView listView = (ListView) fragmentView.findViewById(listId);
                    if (listView == null) return result;

                    listView.post(() -> injectBandHeader(listView));
                    return result;
                }
            });
            Log.i(TAG, "BandColumnHook: hookOnCreateView installed");
        } catch (Exception e) {
            Log.e(TAG, "BandColumnHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectBandHeader(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL
                        || ll.getChildCount() != 6) continue;
                if (TAG_BAND_INJECTED.equals(ll.getTag(BAND_TAG_KEY))) return;

                TextView bandLabel = new TextView(listView.getContext());
                bandLabel.setText("ARFCN");
                bandLabel.setGravity(android.view.Gravity.CENTER);
                bandLabel.setMaxLines(1);
                bandLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = 0.12f;
                bandLabel.setLayoutParams(lp);

                ll.addView(bandLabel, 1);

                float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams clp =
                            (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    clp.weight = weights[j];
                    ll.getChildAt(j).setLayoutParams(clp);
                }

                ll.setTag(BAND_TAG_KEY, TAG_BAND_INJECTED);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "BandColumnHook: injectBandHeader failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns PCI column's ColorStateList from an already-injected row (child[2] = PCI). */
    private android.content.res.ColorStateList pciColor(LinearLayout mainRow) {
        // After injection: [Serving(0), ARFCN(1), PCI(2), Beam(3), RSRP(4), RSRQ(5), SINR(6)]
        if (mainRow.getChildCount() <= 2) return null;
        View v = mainRow.getChildAt(2);
        if (!(v instanceof TextView)) return null;
        return ((TextView) v).getTextColors();
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }
}
