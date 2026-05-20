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
 * Hooks a8.f (LTECellsFragment) to inject a BW (DL bandwidth) column between
 * the Band and EARFCN columns in the LTE cell table.
 *
 * Data path:
 *   Workspace.j → Workspace singleton
 *   Workspace.a → short moduleIndex
 *   Workspace.c → DataSource
 *   Workspace.g → long current cursor time (replay position; -1 if live/unset)
 *   DataSource.getProperty(key, moduleIndex) → Property
 *   Property.b(cursorTime) → Property$Iterator positioned at cursor
 *   Property$Iterator.key() → long sample key at that position
 *   Property.get(key)       → Object value (matches NSG k8.b.f() pattern)
 *
 * Keys:
 *   LTE::Serving_Cell::LTE_Bandwidth_PCell_DL          (intraRow == 0)
 *   LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell{N}_DL  (intraRow 1–7)
 *
 * Enum decoding: 0=1.4M, 1=3M, 2=5M, 3=10M, 4=15M, 5=20M
 *
 * Concrete adapter class: a8.f$a (inner class of a8.f, extends a8.b$b)
 * onCreateView method: a8.f.I(LayoutInflater, ViewGroup, Bundle) — uses android.R.id.list
 */
public class LteBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";
    private static final String TAG_BW_INJECTED = "nsg_ltebw_injected";
    private static final int MAX_SCELLS = 7;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace reflection
    private Field wsSingleton;    // Workspace.j → Workspace (static)
    private Field wsModuleIndex;  // Workspace.a → short
    private Field wsDataSource;   // Workspace.c → DataSource
    private Field wsCurrentTime;  // Workspace.g → long (replay cursor; -1 = live/unset)

    // Property reflection
    private Method dsGetProperty;   // DataSource.getProperty(String, int) → Property
    private Method propIterMethod;  // Property.b(long) → Property$Iterator
    private Method propGetMethod;   // Property.get(long key) → Object  (NSG-native pattern)
    private Method iterEndMethod;   // Property$Iterator.end() → boolean
    private Method iterKeyMethod;   // Property$Iterator.key() → long
    private Method iterValueMethod; // Property$Iterator.value() → Object (kept as fallback)

    // Adapter reflection
    private Field  eField;  // a8.b$b.e → per-source array
    private Method hMethod; // a8.b$b.h(int) → Pair<source, intraRow>

    private boolean reflectionReady = false;

    public LteBandwidthColumnHook(XposedInterface xposed, ClassLoader loader) {
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
            wsCurrentTime  = wsClass.getField("g");  // long replay cursor

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            propGetMethod  = propClass.getMethod("get", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            eField  = bbClass.getField("e");
            hMethod = bbClass.getMethod("h", int.class);

            reflectionReady = true;
            Log.i(TAG, "LteBandwidthColumnHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "LteBandwidthColumnHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Data reading
    // -----------------------------------------------------------------------

    /**
     * Reads DL bandwidth for a given row.
     * @param isServingSource true if the row comes from the serving-cell source bucket
     * @param intraRow        0 = PCell, 1–7 = SCell N
     * @return bandwidth string (e.g. "20M", "1.4M") or null if unavailable
     */
    private String readBandwidth(boolean isServingSource, int intraRow) {
        if (!reflectionReady) return null;
        if (!isServingSource) return null; // neighbor rows have no individual bandwidth

        String key;
        if (intraRow == 0) {
            key = "LTE::Serving_Cell::LTE_Bandwidth_PCell_DL";
        } else if (intraRow >= 1 && intraRow <= MAX_SCELLS) {
            key = "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell" + intraRow + "_DL";
        } else {
            return null;
        }

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
            if (!(value instanceof Integer)) return null;

            return bwEnumToString((Integer) value);
        } catch (Exception e) {
            Log.w(TAG, "LteBandwidthColumnHook.readBandwidth failed: " + e);
            return null;
        }
    }

    /**
     * Converts 3GPP lte-BandwidthDL enum index to display string with "M" suffix.
     * 0=1.4M, 1=3M, 2=5M, 3=10M, 4=15M, 5=20M, other=computed.
     */
    private static String bwEnumToString(int i) {
        switch (i) {
            case 0: return "1.4";
            case 1: return "3";
            case 2: return "5";
            case 3: return "10";
            case 4: return "15";
            case 5: return "20";
            default:
                float mhz = i / 5.0f;
                if (mhz == (int) mhz) return String.valueOf((int) mhz);
                return String.format("%.1f", mhz);
        }
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // Hook: a8.f$a.getView — inject BW column
    // -----------------------------------------------------------------------

    private void hookGetView() {
        if (!reflectionReady) {
            Log.e(TAG, "LteBWHook.hookGetView skipped — reflection not ready");
            return;
        }
        try {
            Class<?> adapterClass  = loader.loadClass("a8.f$a");
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

                    LinearLayout row = findHorizontalRow(rowView);
                    if (row == null) return result;

                    // Resolve (isServingSource, intraRow)
                    boolean isServingSource = true;
                    int     intraRow        = position;
                    try {
                        Object   adapter = chain.getThisObject();
                        Object[] sources = (Object[]) eField.get(adapter);
                        android.util.Pair<?, ?> pair =
                                (android.util.Pair<?, ?>) hMethod.invoke(adapter, position);
                        if (pair != null && pair.second != null) {
                            intraRow        = (int) pair.second;
                            isServingSource = (pair.first != null && sources != null
                                    && sources.length > 0 && pair.first == sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "LteBWHook: h(position) failed: " + e);
                    }

                    String bwText = "-";
                    String bw     = readBandwidth(isServingSource, intraRow);
                    if (bw != null) bwText = bw;

                    // Already injected — just refresh text at child[2]
                    if (TAG_BW_INJECTED.equals(row.getTag())) {
                        TextView existing = (TextView) row.getChildAt(2);
                        existing.setText(bwText);
                        if (bw != null) {
                            existing.setTextColor(0xFFFFFFFF);
                        } else {
                            existing.setTextColor(((TextView) row.getChildAt(3)).getTextColors());
                        }
                        return result;
                    }

                    // First injection — must have exactly 6 children
                    if (row.getChildCount() != 6) return result;

                    // Copy style from Band column (child[1])
                    TextView bandView = (TextView) row.getChildAt(1);
                    TextView bwView   = new TextView(rowView.getContext());
                    bwView.setText(bwText);
                    bwView.setGravity(android.view.Gravity.CENTER);
                    bwView.setMaxLines(1);
                    bwView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            bandView.getTextSize());
                    bwView.setTypeface(bandView.getTypeface());
                    if (bw != null) {
                        bwView.setTextColor(0xFFFFFFFF);
                    } else {
                        bwView.setTextColor(bandView.getTextColors());
                    }

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * rowView.getContext().getResources()
                                    .getDisplayMetrics().density));
                    lp.weight = 0.10f;
                    bwView.setLayoutParams(lp);

                    // Insert at index 2 (between Band and EARFCN)
                    row.addView(bwView, 2);

                    // Redistribute weights across all 7 columns
                    float[] weights = {0.05f, 0.11f, 0.10f, 0.18f, 0.14f, 0.21f, 0.21f};
                    for (int i = 0; i < row.getChildCount() && i < weights.length; i++) {
                        LinearLayout.LayoutParams clp =
                                (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
                        clp.weight = weights[i];
                        row.getChildAt(i).setLayoutParams(clp);
                    }

                    row.setTag(TAG_BW_INJECTED);
                    Log.d(TAG, "LteBWHook: injected BW col pos=" + position + " bw=" + bwText);
                    return result;
                }
            });
            Log.i(TAG, "LteBWHook: hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "LteBWHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.f.I — inject "BW" header label after layout is posted
    // -----------------------------------------------------------------------

    private void hookOnCreateView() {
        try {
            Class<?> fragClass = loader.loadClass("a8.f");
            Method   iMethod   = fragClass.getMethod("I",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result       = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View   fragmentView = (View) result;
                    if (fragmentView == null) return result;

                    // a8.f uses android.R.id.list (not getIdentifier("list_1"))
                    ListView listView = (ListView) fragmentView.findViewById(android.R.id.list);
                    if (listView == null) return result;

                    listView.post(() -> injectBwHeader(listView));
                    return result;
                }
            });
            Log.i(TAG, "LteBWHook: hookOnCreateView installed");
        } catch (Exception e) {
            Log.e(TAG, "LteBWHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectBwHeader(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL
                        || ll.getChildCount() != 6) continue;
                if (TAG_BW_INJECTED.equals(ll.getTag())) return;

                TextView bwLabel = new TextView(listView.getContext());
                bwLabel.setText("BW");
                bwLabel.setGravity(android.view.Gravity.CENTER);
                bwLabel.setMaxLines(1);
                bwLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = 0.10f;
                bwLabel.setLayoutParams(lp);

                ll.addView(bwLabel, 2);

                float[] weights = {0.05f, 0.11f, 0.10f, 0.18f, 0.14f, 0.21f, 0.21f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams clp =
                            (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    clp.weight = weights[j];
                    ll.getChildAt(j).setLayoutParams(clp);
                }

                ll.setTag(TAG_BW_INJECTED);
                Log.i(TAG, "LteBWHook: injectBwHeader done at child " + i);
                return;
            }
            Log.w(TAG, "LteBWHook: injectBwHeader: header row not found ("
                    + listView.getChildCount() + " children)");
        } catch (Exception e) {
            Log.w(TAG, "LteBWHook: injectBwHeader failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Finds the horizontal 6-child (or 7-child after injection) LinearLayout.
     * Handles both flat (lte_cell_list_row) and wrapped (lte_cell_list_row_double).
     */
    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() == 6 || ll.getChildCount() == 7)) {
            return ll;
        }
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() == 6 || inner.getChildCount() == 7)) {
                    return inner;
                }
            }
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
