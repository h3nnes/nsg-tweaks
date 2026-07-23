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
 * Hooks a8.h$a (NRNSACellsFragment adapter) to inject a BW column after the Band column
 * already injected by BandColumnHook.
 *
 * Final column order: [Serving, ARFCN, PCI, BW, Beam, RSRP, RSRQ, SINR]
 * Final weights:      {0.03f,  0.18f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f}
 *
 * Data is read fresh on every getView() call using the adapter's own sample key (f5509c)
 * as the queryTime anchor. This mirrors NSG's v6/f.java (QtGridValueBar.a()) pattern:
 *   Property.b(queryTime) → iter.value()
 * No persistent cache — avoids any stale-data risk.
 *
 * A prev-tick fallback handles partially-written head entries: when iter.value() returns
 * null (value not yet committed) or an array element is null, we retry with
 * Property.b(currentSampleKey - 1) to get the previous complete sample.
 *
 * Property keys:
 *   NR5G::Serving_Cell::NR_Bandwidth_DL                 — scalar Integer, PCell BW in MHz
 *   NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL    — Object[], one Integer per SCell
 */
public class NrNsaBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String KEY_PCELL_BW = "NR5G::Serving_Cell::NR_Bandwidth_DL";
    private static final String KEY_SCELL_BW = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
    private static final int MAX_SCELLS = 4;

    // Tag keys on the main-row LinearLayout.
    private static final int    BW_TAG_KEY      = "nsg_nsa_bw_injected".hashCode();
    private static final int    BW_VIEW_TAG_KEY = "nsg_nsa_bw_view".hashCode();
    private static final String TAG_BW_INJECTED = "nsg_nsa_bw_injected";

    // Final 8-column weights: [Serving, ARFCN, PCI, BW, Beam, RSRP, RSRQ, SINR]
    private static final float[] FINAL_WEIGHTS =
            {0.03f, 0.18f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f};

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace / DataSource / Property reflection — resolved once.
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;
    private Method iterValueMethod;

    // Adapter reflection — h(position) and sources field.
    private Field  eField;       // a8.b$b.e — Object[] sources array
    private Method hMethod;      // a8.b$b.h(int) — returns Pair<source, intraRow>
    private Field  f5509cField;  // k8.c.c — adapter's current data sample key

    private boolean reflectionReady = false;

    public NrNsaBandwidthColumnHook(XposedInterface xposed, ClassLoader loader) {
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
                Log.i(TAG, "NrNsaBandwidthColumnHook: essential class missing, skipping");
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
            Log.e(TAG, "NrNsaBandwidthColumnHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // BW reading — fresh on every getView(), no persistent cache.
    // Pattern: NSG v6/f.java — Property.b(queryTime) → iter.value().
    // Prev-tick fallback for partially-written head entries.
    // -----------------------------------------------------------------------

    /**
     * Read NR-NSA BW for the given row fresh from the DataSource.
     * @param adsk adapter's current sample key (f5509c); used as queryTime anchor.
     * @param intraRow 0 = PCell, 1..4 = SCell index (1-based).
     */
    private String readNrNsaBwFresh(Object ds, int modIdx, long adsk, int intraRow) {
        long qt = (adsk > 0) ? adsk : Long.MAX_VALUE;
        if (intraRow == 0) {
            return readScalarBw(ds, KEY_PCELL_BW, modIdx, qt);
        } else {
            return readArrayBw(ds, KEY_SCELL_BW, modIdx, qt, intraRow - 1);
        }
    }

    /** Read a scalar BW property (NR PCell BW: direct MHz Integer). */
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
            return (val instanceof Number) ? bwEnumToMhz(((Number) val).intValue()) : null;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBW readScalarBw(" + key + ") failed: " + e);
            return null;
        }
    }

    /** Read an indexed element from an array BW property (NR SCell BW: Object[] of Integer). */
    private String readArrayBw(Object ds, String key, int modIdx, long qt, int cellIdx) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, qt);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sk = (long) iterKeyMethod.invoke(iter);
            Object val = iterValueMethod.invoke(iter);
            if (val == null) {
                // Prev-tick fallback: value null — partially-written head entry.
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                sk = (long) iterKeyMethod.invoke(iter);
                val = iterValueMethod.invoke(iter);
                if (val == null) return null;
            }
            if (!(val instanceof Object[])) {
                // Scalar edge case (single SCell reported as plain Integer).
                return (cellIdx == 0 && val instanceof Number)
                        ? bwEnumToMhz(((Number) val).intValue()) : null;
            }
            Object[] arr = (Object[]) val;
            if (cellIdx >= arr.length) return null;
            Object elem = arr[cellIdx];
            if (elem == null) {
                // Prev-tick fallback: array exists but this element is null.
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                val = iterValueMethod.invoke(iter);
                if (!(val instanceof Object[])) return null;
                arr = (Object[]) val;
                if (cellIdx >= arr.length) return null;
                elem = arr[cellIdx];
            }
            return (elem instanceof Number) ? bwEnumToMhz(((Number) elem).intValue()) : null;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBW readArrayBw(" + key + ") failed: " + e);
            return null;
        }
    }

    private static String bwEnumToMhz(int i) {
        // NR BW is reported directly in MHz (5, 10, 15, 20, 25, 40, 50, 60, 80, 100 …).
        return String.valueOf(i);
    }

    public void install() {
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h$a.getView
    // -----------------------------------------------------------------------
    private void hookGetView() {
        if (!reflectionReady) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookGetView skipped — reflection not ready");
            return;
        }
        try {
            Class<?> adapterClass  = ClassMapping.loadClass("a8.h$a", loader);
            if (adapterClass == null) {
                Log.i(TAG, "NrNsaBandwidthColumnHook: a8.h$a not available, skipping getView hook");
                return;
            }
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

                    int childCount = row.getChildCount();

                    // Resolve (isSource0, intraRow, adapterSampleKey) on every getView.
                    int     position         = (int) chain.getArg(0);
                    boolean isSource0        = false;
                    int     intraRow         = position;
                    long    adapterSampleKey = -1;
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
                        if (f5509cField != null && sources != null && sources.length > 0) {
                            adapterSampleKey = f5509cField.getLong(sources[0]);
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "NrNsaBandwidthColumnHook: h(position) failed: " + ex);
                    }

                    // Read BW fresh from DataSource — mirrors v6/f.java pattern.
                    String bwText = null;
                    if (isSource0 && reflectionReady) {
                        try {
                            Object ws = wsSingleton.get(null);
                            if (ws != null) {
                                int modIdx = ((Number) wsModuleIndex.get(ws)).intValue();
                                Object ds  = wsDataSource.get(ws);
                                if (ds != null) {
                                    bwText = readNrNsaBwFresh(ds, modIdx,
                                            adapterSampleKey, intraRow);
                                }
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "NrNsaBW getView: ws read failed: " + ex);
                        }
                    }

                    // ---- Already-injected fast path ----
                    Object bvTag = row.getTag(BW_VIEW_TAG_KEY);
                    if (bvTag instanceof TextView) {
                        TextView bwView = (TextView) bvTag;
                        bwView.setText(bwText != null ? bwText : "-");
                        if (bwText != null) {
                            bwView.setTextColor(0xFFFFFFFF);
                        } else {
                        if (childCount > 2 && row.getChildAt(2) instanceof TextView)
                            bwView.setTextColor(
                                    ((TextView) row.getChildAt(2)).getTextColors());
                        }
                        return result;
                    }

                    // ---- First injection path ----
                    // BandColumnHook already ran → 7 children.
                    if (childCount != 7) return result;

                    String bwDisplay = (bwText != null) ? bwText : "-";

                    // Style from PCI column (child[3] after Band injection).
                    View refRaw = row.getChildAt(3);
                    if (!(refRaw instanceof TextView)) return result;
                    TextView refView = (TextView) refRaw;

                    TextView bwView = new TextView(rowView.getContext());
                    bwView.setText(bwDisplay);
                    bwView.setGravity(android.view.Gravity.CENTER);
                    bwView.setMaxLines(1);
                    bwView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            refView.getTextSize());
                    bwView.setTypeface(refView.getTypeface());
                    if (bwText != null) {
                        bwView.setTextColor(0xFFFFFFFF);
                    } else {
                        bwView.setTextColor(refView.getTextColors());
                    }

                    float density = rowView.getContext()
                            .getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * density));
                    lp.weight = FINAL_WEIGHTS[2];
                    bwView.setLayoutParams(lp);

                    row.addView(bwView, 3);

                    for (int i = 0; i < 8 && i < row.getChildCount(); i++) {
                        View child = row.getChildAt(i);
                        ViewGroup.LayoutParams rawLp = child.getLayoutParams();
                        if (!(rawLp instanceof LinearLayout.LayoutParams)) continue;
                        ((LinearLayout.LayoutParams) rawLp).weight = FINAL_WEIGHTS[i];
                        child.setLayoutParams(rawLp);
                    }

                    row.setTag(BW_VIEW_TAG_KEY, bwView);
                    return result;
                }
            });
            Log.i(TAG, "NrNsaBandwidthColumnHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h.I — inject "BW" header label
    // -----------------------------------------------------------------------
    private void hookOnCreateView() {
        try {
            Class<?> fragClass = ClassMapping.loadClass("a8.h", loader);
            if (fragClass == null) {
                Log.i(TAG, "NrNsaBandwidthColumnHook: a8.h not available, skipping onCreateView hook");
                return;
            }
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

                    listView.post(() -> injectBwHeader(listView));
                    return result;
                }
            });
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
                if (ll.getOrientation() != LinearLayout.HORIZONTAL
                        || ll.getChildCount() != 7) continue;
                if (TAG_BW_INJECTED.equals(ll.getTag(BW_TAG_KEY))) return;

                TextView bwLabel = new TextView(listView.getContext());
                bwLabel.setText("BW");
                bwLabel.setGravity(android.view.Gravity.CENTER);
                bwLabel.setMaxLines(1);
                bwLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = FINAL_WEIGHTS[2];
                bwLabel.setLayoutParams(lp);

                ll.addView(bwLabel, 3);

                for (int j = 0; j < ll.getChildCount() && j < FINAL_WEIGHTS.length; j++) {
                    LinearLayout.LayoutParams clp =
                            (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() >= 1) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL) return inner;
            }
        }
        if (ll.getOrientation() == LinearLayout.HORIZONTAL) return ll;
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
