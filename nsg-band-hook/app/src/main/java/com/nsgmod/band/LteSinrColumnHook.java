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
 * Hooks a8.f$a (LTECellsFragment adapter) to inject an SNR (LTE SINR) column
 * as the last column of the LTE cell table, after RSRQ.
 *
 * Keys:
 *   LTE::Downlink_Measurements::LTE_SINR_PCell          (intraRow == 0)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell1     (intraRow == 1)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell2     (intraRow == 2)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell3     (intraRow == 3)
 *   intraRow > 3: "-" (no SCell4+ SINR field)
 *
 * SINR is a Float; formatted as "%.1f" (no "dB" suffix, matching RSRP/RSRQ).
 *
 * Data is read fresh on every getView() call using the adapter's own sample key
 * as the queryTime anchor — same Property.b(queryTime) → iter.value() pattern
 * as LteBandwidthColumnHook. A prev-tick fallback handles partially-written
 * head entries where iter.value() returns null.
 *
 * Chaining: this hook is installed AFTER LteBandwidthColumnHook in MainHook,
 * so libxposed-api makes it the outermost hook. chain.proceed() runs the BW
 * hook (inner) then the original getView. Because LteBandwidthColumnHook's
 * findHorizontalRow only accepts rows with 6–7 children, the SNR view is
 * temporarily removed from the recycled convertView before chain.proceed()
 * and re-added afterwards. This lets the BW hook fast-path correctly on
 * every recycled row.
 *
 * Final 8-column layout:
 *   [Serving, Band, BW, EARFCN, PCI, RSRP, RSRQ, SNR]
 *   weights: {0.05, 0.10, 0.09, 0.17, 0.14, 0.15, 0.15, 0.15}
 */
public class LteSinrColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String TAG_SNR_INJECTED  = "nsg_ltesinr_injected";
    private static final int    SNR_TAG_KEY        = "nsg_ltesinr_injected".hashCode();
    private static final int    SNR_VIEW_TAG_KEY   = "nsg_ltesinr_view".hashCode();

    private static final float[] WEIGHTS = {
            0.05f, 0.10f, 0.09f, 0.17f, 0.14f, 0.15f, 0.15f, 0.15f
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;
    private Method iterValueMethod;

    private Field  eField;
    private Method hMethod;
    private Field  f5509cField;

    private boolean reflectionReady = false;

    public LteSinrColumnHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> wsClass   = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            Class<?> dsClass   = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Class<?> propClass = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterClass = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);
            Class<?> bbClass   = ClassMapping.loadClass("a8.b$b", loader);
            Class<?> k8cClass  = ClassMapping.loadClass("k8.c", loader);
            if (wsClass == null || dsClass == null || propClass == null || iterClass == null
                    || bbClass == null || k8cClass == null) {
                Log.i(TAG, "LteSinrColumnHook: essential class missing, skipping");
                return;
            }

            String wsJName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader);
            String wsAName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "a", loader);
            String wsCName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "c", loader);
            wsSingleton    = wsClass.getField(wsJName);
            wsModuleIndex  = wsClass.getField(wsAName);
            wsDataSource   = wsClass.getField(wsCName);

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            String eFieldName = ClassMapping.runtimeFieldName("a8.b$b", "e", loader);
            eField      = bbClass.getField(eFieldName);
            hMethod     = bbClass.getMethod("h", int.class);
            String sampleKeyFieldName = ClassMapping.runtimeFieldName("k8.c", "c", loader);
            f5509cField = k8cClass.getField(sampleKeyFieldName);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "LteSinrColumnHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // SINR reading — fresh on every getView(), no persistent cache.
    // -----------------------------------------------------------------------

    private String readLteSinrFresh(Object ds, int modIdx, long adsk, int intraRow) {
        if (intraRow > 3) return null;
        long qt = (adsk > 0) ? adsk : Long.MAX_VALUE;
        String key = (intraRow == 0)
                ? "LTE::Downlink_Measurements::LTE_SINR_PCell"
                : "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell" + intraRow;
        return readScalarSinr(ds, key, modIdx, qt);
    }

    private String readScalarSinr(Object ds, String key, int modIdx, long qt) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, qt);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sk = (long) iterKeyMethod.invoke(iter);
            Object val = iterValueMethod.invoke(iter);
            if (val == null) {
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                val = iterValueMethod.invoke(iter);
            }
            if (val instanceof Float) {
                return String.format("%.1f", (Float) val);
            }
            if (val instanceof Number) {
                return String.format("%.1f", ((Number) val).floatValue());
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "LteSINR readScalarSinr(" + key + ") failed: " + e);
            return null;
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
            Log.e(TAG, "LteSinrHook.hookGetView skipped — reflection not ready");
            return;
        }
        try {
            Class<?> adapterClass = ClassMapping.loadClass("a8.f$a", loader);
            if (adapterClass == null) {
                Log.i(TAG, "LteSinrColumnHook: a8.f$a not available, skipping getView hook");
                return;
            }
            Method getViewMethod = adapterClass.getMethod("getView",
                    int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Pre-proceed: if the recycled convertView already has SNR
                    // injected (8 children), temporarily remove the SNR view so
                    // the inner LteBandwidthColumnHook sees 7 children and its
                    // findHorizontalRow (which accepts 6–7) can fast-path.
                    View convertView = (View) chain.getArg(1);
                    TextView removedSnr = null;
                    LinearLayout preRow = null;
                    if (convertView != null) {
                        preRow = findHorizontalRow(convertView);
                        if (preRow != null && preRow.getChildCount() == 8) {
                            Object snrTag = preRow.getTag(SNR_VIEW_TAG_KEY);
                            if (snrTag instanceof TextView) {
                                removedSnr = (TextView) snrTag;
                                preRow.removeView(removedSnr);
                            }
                        }
                    }

                    Object result = chain.proceed();

                    if (!SettingsToggleHook.cellModsEnabled()) {
                        if (removedSnr != null && preRow != null) {
                            preRow.addView(removedSnr);
                        }
                        return result;
                    }

                    View rowView = (View) result;
                    if (rowView == null) {
                        if (removedSnr != null && preRow != null) {
                            preRow.addView(removedSnr);
                        }
                        return result;
                    }

                    LinearLayout row = findHorizontalRow(rowView);
                    if (row == null) {
                        if (removedSnr != null && preRow != null) {
                            preRow.addView(removedSnr);
                        }
                        return result;
                    }

                    int     position         = (int) chain.getArg(0);
                    boolean isServing        = false;
                    int     intraRow         = position;
                    long    adapterSampleKey = -1;
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
                            adapterSampleKey = f5509cField.getLong(sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "LteSinr: h(position) failed: " + e);
                    }

                    String sinrText = null;
                    if (isServing && reflectionReady) {
                        try {
                            Object ws = wsSingleton.get(null);
                            if (ws != null) {
                                int modIdx = ((Number) wsModuleIndex.get(ws)).intValue();
                                Object ds  = wsDataSource.get(ws);
                                if (ds != null) {
                                    sinrText = readLteSinrFresh(ds, modIdx,
                                            adapterSampleKey, intraRow);
                                }
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "LteSinr: ws read failed: " + ex);
                        }
                    }
                    String display = (sinrText != null) ? sinrText : "-";

                    // ---- Fast path: SNR view was removed, re-add and re-read ----
                    if (removedSnr != null) {
                        row.addView(removedSnr);
                        row.setTag(SNR_VIEW_TAG_KEY, removedSnr);
                        applyWeights(row);
                        removedSnr.setText(display);
                        if (sinrText != null) {
                            removedSnr.setTextColor(0xFFFFFFFF);
                        } else {
                            if (row.getChildCount() > 6
                                    && row.getChildAt(6) instanceof TextView) {
                                removedSnr.setTextColor(
                                        ((TextView) row.getChildAt(6)).getTextColors());
                            }
                        }
                        return result;
                    }

                    // ---- Already-injected (no removal happened) ----
                    Object snrTag = row.getTag(SNR_VIEW_TAG_KEY);
                    if (snrTag instanceof TextView) {
                        TextView snrView = (TextView) snrTag;
                        snrView.setText(display);
                        if (sinrText != null) {
                            snrView.setTextColor(0xFFFFFFFF);
                        } else {
                            if (row.getChildCount() > 6
                                    && row.getChildAt(6) instanceof TextView) {
                                snrView.setTextColor(
                                        ((TextView) row.getChildAt(6)).getTextColors());
                            }
                        }
                        return result;
                    }

                    // ---- First injection — must have exactly 7 children ----
                    if (row.getChildCount() != 7) return result;

                    TextView rsrqView = (row.getChildCount() > 6
                            && row.getChildAt(6) instanceof TextView)
                            ? (TextView) row.getChildAt(6) : null;
                    TextView snrView = new TextView(rowView.getContext());
                    snrView.setText(display);
                    snrView.setGravity(android.view.Gravity.CENTER);
                    snrView.setMaxLines(1);
                    if (rsrqView != null) {
                        snrView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                                rsrqView.getTextSize());
                        snrView.setTypeface(rsrqView.getTypeface());
                    }
                    if (sinrText != null) {
                        snrView.setTextColor(0xFFFFFFFF);
                    } else if (rsrqView != null) {
                        snrView.setTextColor(rsrqView.getTextColors());
                    }

                    float density = rowView.getContext().getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * density));
                    lp.weight = WEIGHTS[7];
                    snrView.setLayoutParams(lp);

                    row.addView(snrView);

                    applyWeights(row);

                    row.setTag(SNR_VIEW_TAG_KEY, snrView);
                    return result;
                }
            });
            Log.i(TAG, "LteSinrColumnHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "LteSinrHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.f.I — inject "SNR" header label
    // -----------------------------------------------------------------------
    private void hookOnCreateView() {
        try {
            Class<?> fragClass = ClassMapping.loadClass("a8.f", loader);
            if (fragClass == null) {
                Log.i(TAG, "LteSinrColumnHook: a8.f not available, skipping onCreateView hook");
                return;
            }
            Method iMethod = fragClass.getMethod("I",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View fragmentView = (View) result;
                    if (fragmentView == null) return result;
                    ListView listView = (ListView) fragmentView.findViewById(android.R.id.list);
                    if (listView == null) return result;
                    listView.post(() -> injectSnrHeader(listView));
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteSinrHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectSnrHeader(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL) continue;
                if (TAG_SNR_INJECTED.equals(ll.getTag(SNR_TAG_KEY))) return;
                if (ll.getChildCount() != 7) continue;

                TextView snrLabel = new TextView(listView.getContext());
                snrLabel.setText("SNR");
                snrLabel.setGravity(android.view.Gravity.CENTER);
                snrLabel.setMaxLines(1);
                snrLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = WEIGHTS[7];
                snrLabel.setLayoutParams(lp);

                ll.addView(snrLabel);

                applyWeights(ll);

                ll.setTag(SNR_TAG_KEY, TAG_SNR_INJECTED);
                return;
            }
            Log.w(TAG, "LteSinr: injectSnrHeader: header row not found ("
                    + listView.getChildCount() + " children)");
        } catch (Exception e) {
            Log.w(TAG, "LteSinr: injectSnrHeader failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() == 7 || ll.getChildCount() == 8)) return ll;
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() == 7 || inner.getChildCount() == 8)) return inner;
            }
        }
        return null;
    }

    private void applyWeights(LinearLayout row) {
        for (int i = 0; i < row.getChildCount() && i < WEIGHTS.length; i++) {
            LinearLayout.LayoutParams clp =
                    (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            clp.weight = WEIGHTS[i];
            row.getChildAt(i).setLayoutParams(clp);
        }
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }
}
