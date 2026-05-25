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
 * Hooks a8.h (NRNSACellsFragment) to inject a BW column after the Band column
 * already injected by BandColumnHook.
 *
 * Final column order: [Serving, Band, BW, PCI, Beam, RSRP, RSRQ, SINR]
 * Final weights:      {0.03f,  0.12f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f}
 *
 * Data keys (same as NrSaCellColumnsHook):
 *   PCell (intraRow=0):  NR5G::Serving_Cell::NR_Bandwidth_DL           → Integer MHz
 *   SCell (intraRow>=1): NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL → Object[] indexed by (intraRow-1)
 *
 * Replay-safe: uses Workspace.g cursor; -1 (live) falls back to Long.MAX_VALUE.
 */
public class NrNsaBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String KEY_PCELL_BW = "NR5G::Serving_Cell::NR_Bandwidth_DL";
    private static final String KEY_SCELL_BW = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
    private static final int MAX_SCELLS = 4;

    private static final String TAG_BW_INJECTED = "nsg_nsa_bw_injected";
    // Keyed tag slot — distinct from BandColumnHook's BAND_TAG_KEY so both hooks track
    // injection state independently on the same View without overwriting each other.
    private static final int BW_TAG_KEY = "nsg_nsa_bw_injected".hashCode();

    // Final 8-column weights: [Serving, Band, BW, PCI, Beam, RSRP, RSRQ, SINR]
    private static final float[] FINAL_WEIGHTS = {0.03f, 0.12f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f};

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace reflection
    private Field wsSingleton;
    private Field wsModuleIndex;
    private Field wsDataSource;
    private Field wsCurrentTime;

    // Property reflection
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method propGetMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;

    // Adapter reflection
    private Field  eField;
    private Method hMethod;

    private boolean reflectionReady = false;

    public NrNsaBandwidthColumnHook(XposedInterface xposed, ClassLoader loader) {
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
            wsCurrentTime  = wsClass.getField("g");

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            propGetMethod  = propClass.getMethod("get", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            // iterValueMethod intentionally omitted — not needed; we use property.get(key) directly.

            eField  = bbClass.getField("e");
            hMethod = bbClass.getMethod("h", int.class);

            reflectionReady = true;
            Log.i(TAG, "NrNsaBandwidthColumnHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: initReflection failed: " + e);
        }
    }

    private String readBandwidth(boolean isSource0, int intraRow) {
        if (!reflectionReady) return null;
        if (!isSource0) return null; // detected cells have no serving bandwidth
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) return null;

            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) return null;

            String key;
            int    sCellIndex;
            if (intraRow == 0) {
                key = KEY_PCELL_BW;
                sCellIndex = -1;
            } else {
                int si = intraRow - 1;
                if (si >= MAX_SCELLS) return null;
                key = KEY_SCELL_BW;
                sCellIndex = si;
            }

            Object property = dsGetProperty.invoke(dataSource, key, moduleIndex);
            if (property == null) {
                Log.w(TAG, "NrNsaBandwidthColumnHook: no property for key=" + key + " moduleIndex=" + moduleIndex);
                return null;
            }

            long cursorTime = wsCurrentTime.getLong(ws);
            long queryTime  = (cursorTime >= 0) ? cursorTime : Long.MAX_VALUE;

            Object iterator = propIterMethod.invoke(property, queryTime);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;

            long   sampleKey = (long) iterKeyMethod.invoke(iterator);
            Object value     = propGetMethod.invoke(property, sampleKey);
            if (value == null) return null;

            if (sCellIndex >= 0) {
                if (value.getClass().isArray()) {
                    Object[] arr = (Object[]) value;
                    if (sCellIndex >= arr.length) return null;
                    value = arr[sCellIndex];
                } else {
                    if (sCellIndex != 0) return null;
                }
            }

            return (value instanceof Number) ? String.valueOf(((Number) value).intValue()) : null;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBandwidthColumnHook: readBandwidth failed: " + e);
            return null;
        }
    }

    public void install() {
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h$a.getView — inject BW cell at index 2 (after Band at index 1)
    // -----------------------------------------------------------------------
    private void hookGetView() {
        if (!reflectionReady) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookGetView skipped — reflection not ready");
            return;
        }
        try {
            Class<?> adapterClass  = loader.loadClass("a8.h$a");
            Method   getViewMethod = adapterClass.getMethod("getView", int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Belt-and-suspenders: if the convertView already has our BW column
                    // injected, null it so NSG inflates fresh (BandColumnHook does this
                    // first since it is outermost, but guard here too).
                    View convertView = (View) chain.getArg(1);
                    boolean needsNullConvert = false;
                    if (convertView instanceof LinearLayout) {
                        LinearLayout cvOuter = (LinearLayout) convertView;
                        LinearLayout cvMain = (cvOuter.getOrientation() == LinearLayout.VERTICAL
                                && cvOuter.getChildCount() >= 1
                                && cvOuter.getChildAt(0) instanceof LinearLayout)
                                ? (LinearLayout) cvOuter.getChildAt(0) : null;
                        if (cvMain != null && TAG_BW_INJECTED.equals(cvMain.getTag(BW_TAG_KEY))) {
                            needsNullConvert = true;
                        }
                    }

                    Object result = needsNullConvert
                            ? chain.proceed(new Object[]{ chain.getArg(0), null, chain.getArg(2) })
                            : chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    int    position = (int) chain.getArg(0);
                    View   rowView  = (View) result;
                    if (rowView == null) return result;

                    LinearLayout row = findHorizontalRow(rowView);
                    if (row == null) return result;

                    // Resolve (isSource0, intraRow)
                    boolean isSource0 = false; // default false: neighbour rows must not read serving-cell data
                    int     intraRow  = position;
                    try {
                        Object   adapter = chain.getThisObject();
                        Object[] sources = (Object[]) eField.get(adapter);
                        android.util.Pair<?, ?> pair =
                                (android.util.Pair<?, ?>) hMethod.invoke(adapter, position);
                        if (pair != null && pair.second != null) {
                            intraRow  = (int) pair.second;
                            isSource0 = (pair.first != null && sources != null
                                    && sources.length > 0 && pair.first == sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NrNsaBandwidthColumnHook: h(position) failed: " + e);
                    }

                    final String bwText0 = readBandwidth(isSource0, intraRow);
                    final String bwText  = (bwText0 != null) ? bwText0 : "-";

                    // Already injected — refresh text at child[2]
                    if (TAG_BW_INJECTED.equals(row.getTag(BW_TAG_KEY))) {                        TextView existing = (TextView) row.getChildAt(2);
                        existing.setText(bwText);
                        // Refresh color: grey (from PCI ref) for "-", white for actual values
                        if (!"-".equals(bwText)) {
                            existing.setTextColor(0xFFFFFFFF);
                        } else {
                            TextView refView = (TextView) row.getChildAt(3);
                            existing.setTextColor(refView.getTextColors());
                        }
                        return result;
                    }

                    // BandColumnHook hasn't run yet on this recycled view (still 6 children).
                    // Schedule a retry after the current getView call stack unwinds — by then
                    // BandColumnHook will have injected its column (7 children).
                    if (row.getChildCount() == 6) {
                        final LinearLayout retryRow = row;
                        row.post(() -> {
                            if (TAG_BW_INJECTED.equals(retryRow.getTag(BW_TAG_KEY))) return;
                            if (retryRow.getChildCount() != 7) return;
                            try {
                                injectBwIntoRow(retryRow, bwText, rowView);
                            } catch (Exception ex) {
                                Log.w(TAG, "NrNsaBandwidthColumnHook: post-retry inject failed: " + ex);
                            }
                        });
                        return result;
                    }

                    // Row must have 7 children (BandColumnHook already ran)
                    if (row.getChildCount() != 7) return result;

                    injectBwIntoRow(row, bwText, rowView);
                    return result;
                }
            });
            Log.i(TAG, "NrNsaBandwidthColumnHook: hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h.I — inject "BW" header label after layout is posted
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
                    if (listId == 0) { Log.w(TAG, "NrNsaBandwidthColumnHook: list_1 id not found"); return result; }

                    ListView listView = (ListView) fragmentView.findViewById(listId);
                    if (listView == null) return result;

                    listView.post(() -> injectBwHeader(listView));
                    return result;
                }
            });
            Log.i(TAG, "NrNsaBandwidthColumnHook: hookOnCreateView installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectBwHeader(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                // Header row has 7 children after BandColumnHook ran (Band already injected)
                if (ll.getOrientation() != LinearLayout.HORIZONTAL || ll.getChildCount() != 7) continue;
                if (TAG_BW_INJECTED.equals(ll.getTag(BW_TAG_KEY))) return;

                TextView bwLabel = new TextView(listView.getContext());
                bwLabel.setText("BW");
                bwLabel.setGravity(android.view.Gravity.CENTER);
                bwLabel.setMaxLines(1);
                bwLabel.setTextColor(resolveColor(listView.getContext(), android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = FINAL_WEIGHTS[2]; // 0.10f
                bwLabel.setLayoutParams(lp);

                ll.addView(bwLabel, 2);

                // Redistribute all 8 columns to final weights
                for (int j = 0; j < ll.getChildCount() && j < FINAL_WEIGHTS.length; j++) {
                    LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    clp.weight = FINAL_WEIGHTS[j];
                    ll.getChildAt(j).setLayoutParams(clp);
                }

                ll.setTag(BW_TAG_KEY, TAG_BW_INJECTED);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBandwidthColumnHook: injectBwHeader failed: " + e);
        }
    }

    /**
     * Injects the BW TextView at index 2 of an already-Band-injected row (childCount==7)
     * and redistributes all 8 column weights.
     * Called both inline from getView and from the post() retry lambda.
     */
    private void injectBwIntoRow(LinearLayout row, String bwText, View contextView) {
        // Copy style from PCI view (child[3] after Band injection = original child[1])
        View refRaw = row.getChildAt(3);
        if (!(refRaw instanceof TextView)) {
            Log.e(TAG, "NrNsaBW injectBwIntoRow: child[3] is not a TextView — aborting");
            return;
        }
        TextView refView = (TextView) refRaw;
        TextView bwView  = new TextView(contextView.getContext());
        bwView.setText(bwText);
        bwView.setGravity(android.view.Gravity.CENTER);
        bwView.setMaxLines(1);
        bwView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, refView.getTextSize());
        bwView.setTypeface(refView.getTypeface());
        if (!"-".equals(bwText)) {
            bwView.setTextColor(0xFFFFFFFF);
        } else {
            bwView.setTextColor(refView.getTextColors());
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, (int) (21 * contextView.getContext().getResources().getDisplayMetrics().density));
        lp.weight = FINAL_WEIGHTS[2]; // 0.10f
        bwView.setLayoutParams(lp);

        row.addView(bwView, 2);

        // Redistribute all 8 columns
        for (int i = 0; i < row.getChildCount() && i < FINAL_WEIGHTS.length; i++) {
            View child = row.getChildAt(i);
            android.view.ViewGroup.LayoutParams rawLp = child.getLayoutParams();
            if (!(rawLp instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) rawLp;
            clp.weight = FINAL_WEIGHTS[i];
            child.setLayoutParams(clp);
        }

        row.setTag(BW_TAG_KEY, TAG_BW_INJECTED);
    }

    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        // v4.8.6: outer container is a vertical LL (nr_cell_list_row_double_nsa).
        // BandColumnHook already removed the info strip (child 1), leaving only
        // the main horizontal row as child 0.
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() >= 1) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() >= 6 && inner.getChildCount() <= 8)) {
                    return inner;
                }
            }
        }
        // Flat horizontal row (header row, or legacy layout).
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() >= 6 && ll.getChildCount() <= 8)) {
            return ll;
        }
        return null;
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }
}
