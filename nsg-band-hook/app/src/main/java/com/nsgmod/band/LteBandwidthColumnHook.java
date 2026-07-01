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
 * Hooks a8.f$a (LTECellsFragment adapter) to inject a BW column between Band and EARFCN.
 *
 * Keys:
 *   LTE::Serving_Cell::LTE_Bandwidth_PCell_DL          (intraRow == 0)
 *   LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell{N}_DL  (intraRow 1–7)
 * Enum: 0=1.4, 1=3, 2=5, 3=10, 4=15, 5=20 (MHz)
 *
 * Data is read fresh on every getView() call using the adapter's own sample key (f5509c)
 * as the queryTime anchor. This mirrors NSG's v6/f.java (QtGridValueBar.a()) pattern:
 *   Property.b(queryTime) → iter.value()
 * No persistent cache — avoids any stale-data risk.
 *
 * A prev-tick fallback handles partially-written head entries where iter.value() returns null.
 */
public class LteBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";
    private static final int MAX_SCELLS = 7;

    // Tag keys on the row LinearLayout.
    private static final String TAG_BW_INJECTED  = "nsg_ltebw_injected";
    private static final int    BW_TAG_KEY        = "nsg_ltebw_injected".hashCode();
    private static final int    BW_VIEW_TAG_KEY   = "nsg_ltebw_view".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace / Property reflection.
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;
    private Method iterValueMethod;

    // Adapter reflection.
    private Field  eField;       // a8.b$b.e — Object[] sources array
    private Method hMethod;      // a8.b$b.h(int) — returns Pair<source, intraRow>
    private Field  f5509cField;  // k8.c.c — adapter's current data sample key

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

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            eField      = bbClass.getField("e");
            hMethod     = bbClass.getMethod("h", int.class);
            f5509cField = loader.loadClass("k8.c").getField("c");

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "LteBandwidthColumnHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // BW reading — fresh on every getView(), no persistent cache.
    // Pattern: NSG v6/f.java — Property.b(queryTime) → iter.value().
    // Prev-tick fallback for partially-written head entries.
    // -----------------------------------------------------------------------

    /**
     * Read LTE BW for the given row fresh from the DataSource.
     * @param adsk adapter's current sample key (f5509c); used as queryTime anchor.
     * @param intraRow 0 = PCell, 1..7 = SCell N (matches the SCell number in the key).
     */
    private String readLteBwFresh(Object ds, int modIdx, long adsk, int intraRow) {
        long qt = (adsk > 0) ? adsk : Long.MAX_VALUE;
        String key = (intraRow == 0)
                ? "LTE::Serving_Cell::LTE_Bandwidth_PCell_DL"
                : "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell" + intraRow + "_DL";
        return readScalarBw(ds, key, modIdx, qt);
    }

    private String readScalarBw(Object ds, String key, int modIdx, long qt) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, qt);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sk = (long) iterKeyMethod.invoke(iter);
            Object val = iterValueMethod.invoke(iter);
            if (val == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                val = iterValueMethod.invoke(iter);
            }
            if (!(val instanceof Integer)) return null;
            return bwEnumToString((Integer) val);
        } catch (Exception e) {
            Log.w(TAG, "LteBW readScalarBw(" + key + ") failed: " + e);
            return null;
        }
    }

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
    // Hook: a8.f$a.getView
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
                    Object result = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View rowView = (View) result;
                    if (rowView == null) return result;

                    LinearLayout row = findHorizontalRow(rowView);
                    if (row == null) return result;

                    // ---- Already-injected fast path ----
                    Object bvTag = row.getTag(BW_VIEW_TAG_KEY);
                    if (bvTag instanceof TextView) {
                        TextView bwView = (TextView) bvTag;
                        // Re-resolve (isServing, intraRow, adsk) every time — prevents
                        // stale-tag bug where a recycled row keeps wrong intraRow.
                        int     position2        = (int) chain.getArg(0);
                        boolean isServing2       = false;
                        int     intraRow2        = position2;
                        long    adapterSampleKey2 = -1;
                        try {
                            Object   adapter2 = chain.getThisObject();
                            Object[] sources2 = (Object[]) eField.get(adapter2);
                            android.util.Pair<?, ?> pair2 =
                                    (android.util.Pair<?, ?>) hMethod.invoke(adapter2, position2);
                            if (pair2 != null && pair2.second != null) {
                                intraRow2  = (int) pair2.second;
                                isServing2 = (pair2.first != null && sources2 != null
                                        && sources2.length > 0 && pair2.first == sources2[0]);
                            }
                            if (f5509cField != null && sources2 != null && sources2.length > 0) {
                                adapterSampleKey2 = f5509cField.getLong(sources2[0]);
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "LteBWHook: fast-path h(position) failed: " + ex);
                        }
                        // Read BW fresh — no persistent cache.
                        String bwText = null;
                        if (isServing2 && reflectionReady) {
                            try {
                                Object ws = wsSingleton.get(null);
                                if (ws != null) {
                                    int modIdx = ((Number) wsModuleIndex.get(ws)).intValue();
                                    Object ds  = wsDataSource.get(ws);
                                    if (ds != null) {
                                        bwText = readLteBwFresh(ds, modIdx,
                                                adapterSampleKey2, intraRow2);
                                    }
                                }
                            } catch (Exception ex) {
                                Log.w(TAG, "LteBW fast path: ws read failed: " + ex);
                            }
                        }
                        String display = (bwText != null) ? bwText : "-";
                        bwView.setText(display);
                        if (bwText != null) {
                            bwView.setTextColor(0xFFFFFFFF);
                        } else {
                            if (row.getChildCount() > 3 && row.getChildAt(3) instanceof TextView)
                                bwView.setTextColor(((TextView) row.getChildAt(3)).getTextColors());
                        }
                        return result;
                    }

                    // ---- First injection — must have exactly 6 children ----
                    if (row.getChildCount() != 6) return result;

                    boolean isServingSource  = true;
                    int     intraRow         = (int) chain.getArg(0);
                    long    adapterSampleKey = -1;
                    try {
                        Object   adapter = chain.getThisObject();
                        Object[] sources = (Object[]) eField.get(adapter);
                        android.util.Pair<?, ?> pair =
                                (android.util.Pair<?, ?>) hMethod.invoke(adapter, intraRow);
                        if (pair != null && pair.second != null) {
                            intraRow        = (int) pair.second;
                            isServingSource = (pair.first != null && sources != null
                                    && sources.length > 0 && pair.first == sources[0]);
                        }
                        if (f5509cField != null && sources != null && sources.length > 0) {
                            adapterSampleKey = f5509cField.getLong(sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "LteBWHook: h(position) failed: " + e);
                    }

                    // Read BW fresh — no persistent cache.
                    String bwText = null;
                    if (isServingSource && reflectionReady) {
                        try {
                            Object ws = wsSingleton.get(null);
                            if (ws != null) {
                                int modIdx = ((Number) wsModuleIndex.get(ws)).intValue();
                                Object ds  = wsDataSource.get(ws);
                                if (ds != null) {
                                    bwText = readLteBwFresh(ds, modIdx,
                                            adapterSampleKey, intraRow);
                                }
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "LteBW first-inject: ws read failed: " + ex);
                        }
                    }
                    String display = (bwText != null) ? bwText : "-";

                    // Copy style from Band column (child[1]).
                    TextView bandView = (TextView) row.getChildAt(1);
                    TextView bwView   = new TextView(rowView.getContext());
                    bwView.setText(display);
                    bwView.setGravity(android.view.Gravity.CENTER);
                    bwView.setMaxLines(1);
                    bwView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bandView.getTextSize());
                    bwView.setTypeface(bandView.getTypeface());
                    if (bwText != null) {
                        bwView.setTextColor(0xFFFFFFFF);
                    } else {
                        bwView.setTextColor(bandView.getTextColors());
                    }

                    float density = rowView.getContext().getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * density));
                    lp.weight = 0.10f;
                    bwView.setLayoutParams(lp);

                    row.addView(bwView, 2);

                    float[] weights = {0.05f, 0.11f, 0.10f, 0.18f, 0.14f, 0.21f, 0.21f};
                    for (int i = 0; i < row.getChildCount() && i < weights.length; i++) {
                        LinearLayout.LayoutParams clp =
                                (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
                        clp.weight = weights[i];
                        row.getChildAt(i).setLayoutParams(clp);
                    }

                    // Cache view reference only — intraRow resolved fresh on every getView.
                    row.setTag(BW_VIEW_TAG_KEY, bwView);
                    return result;
                }
            });
            Log.i(TAG, "LteBandwidthColumnHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "LteBWHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.f.I — inject "BW" header label
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
                    ListView listView = (ListView) fragmentView.findViewById(android.R.id.list);
                    if (listView == null) return result;
                    listView.post(() -> injectBwHeader(listView));
                    return result;
                }
            });
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
                if (ll.getOrientation() != LinearLayout.HORIZONTAL || ll.getChildCount() != 6) continue;
                if (TAG_BW_INJECTED.equals(ll.getTag(BW_TAG_KEY))) return;

                TextView bwLabel = new TextView(listView.getContext());
                bwLabel.setText("BW");
                bwLabel.setGravity(android.view.Gravity.CENTER);
                bwLabel.setMaxLines(1);
                bwLabel.setTextColor(resolveColor(listView.getContext(), android.R.attr.textColorTertiary));
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

                ll.setTag(BW_TAG_KEY, TAG_BW_INJECTED);
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

    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() == 6 || ll.getChildCount() == 7)) return ll;
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() == 6 || inner.getChildCount() == 7)) return inner;
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
