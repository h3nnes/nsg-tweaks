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
 * Final column order: [Serving, Band, BW, PCI, Beam, RSRP, RSRQ, SINR]
 * Final weights:      {0.03f,  0.12f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f}
 *
 * Performance design:
 *  - BW values are cached per data-tick (bwCache[]), invalidated by hooking
 *    a8.b$b.g(long, DataSource, short) which fires once per data tick for both
 *    NR-NSA and LTE adapters.
 *  - On every getView: h(position) is called to resolve (isSource0, intraRow)
 *    so stale-tag recycling is avoided — no wrong BW shown for neighbour rows.
 *  - The BW TextView ref is cached as a tag for O(1) setText on recycle.
 *  - No convertView null trick; views recycle normally.
 *
 * Property keys:
 *   NR5G::Serving_Cell::NR_Bandwidth_DL          — scalar, PCell BW (intraRow 0)
 *   NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL — array[0..3], SCell 1..4 BW
 */
public class NrNsaBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String KEY_PCELL_BW = "NR5G::Serving_Cell::NR_Bandwidth_DL";
    private static final String KEY_SCELL_BW = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
    private static final int MAX_SCELLS = 4;

    // Tag keys on the main-row LinearLayout.
    private static final int BW_TAG_KEY      = "nsg_nsa_bw_injected".hashCode();
    private static final int BW_VIEW_TAG_KEY = "nsg_nsa_bw_view".hashCode();
    private static final String TAG_BW_INJECTED = "nsg_nsa_bw_injected";

    // Final 8-column weights: [Serving, Band, BW, PCI, Beam, RSRP, RSRQ, SINR]
    private static final float[] FINAL_WEIGHTS =
            {0.03f, 0.12f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f};

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace / DataSource reflection — resolved once.
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;
    private Field  wsCurrentTime;
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method propGetMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;

    // Adapter reflection — h(position) and sources field.
    private Field  eField;   // a8.b$b.e  — Object[] sources array
    private Method hMethod;  // a8.b$b.h(int) — returns Pair<source, intraRow>

    private boolean reflectionReady = false;

    /** BW cache: index 0 = PCell, 1..4 = SCell 1..4. Null = not available → show "-". */
    private final String[] bwCache = new String[MAX_SCELLS + 1];
    private volatile boolean bwCacheValid = false;
    /** Data sample key (sources[0].f5509c) used when bwCache was last filled; -1 = never filled. */
    private long bwCacheSampleKey = -1;
    /** k8.c field "c" (f5509c) — the data sample key the adapter used for its current data. */
    private Field f5509cField;

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

            eField  = bbClass.getField("e");
            hMethod = bbClass.getMethod("h", int.class);
            f5509cField = loader.loadClass("k8.c").getField("c"); // data sample key (f5509c)

            reflectionReady = true;
            Log.i(TAG, "NrNsaBandwidthColumnHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // BW cache — filled once per data-tick via g() hook, reused per frame.
    // -----------------------------------------------------------------------

    void invalidateBwCache() {
        bwCacheValid = false;
    }

    private void fillBwCache(long queryTime) {
        for (int i = 0; i <= MAX_SCELLS; i++) bwCache[i] = null;
        bwCacheSampleKey = queryTime;
        bwCacheValid = true;
        if (!reflectionReady) return;
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) return;
            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) return;

            // PCell BW — scalar property
            bwCache[0] = readScalarBw(dataSource, KEY_PCELL_BW, moduleIndex, queryTime);

            // SCell BW — array property: arr[0]=SCell1, arr[1]=SCell2, ...
            Object scelProp = dsGetProperty.invoke(dataSource, KEY_SCELL_BW, moduleIndex);
            if (scelProp != null) {
                Object iter = propIterMethod.invoke(scelProp, queryTime);
                if (iter != null && !(boolean) iterEndMethod.invoke(iter)) {
                    long key = (long) iterKeyMethod.invoke(iter);
                    Object val = propGetMethod.invoke(scelProp, key);
                    if (val != null && val.getClass().isArray()) {
                        Object[] arr = (Object[]) val;
                        for (int si = 0; si < arr.length && si < MAX_SCELLS; si++) {
                            if (arr[si] instanceof Number)
                                bwCache[si + 1] = bwEnumToMhz(((Number) arr[si]).intValue());
                        }
                    } else if (val instanceof Number) {
                        bwCache[1] = bwEnumToMhz(((Number) val).intValue());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBandwidthColumnHook: fillBwCache failed: " + e);
        }
    }

    private String readScalarBw(Object ds, String key, int modIdx, long queryTime) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, queryTime);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sampleKey = (long) iterKeyMethod.invoke(iter);
            Object val = propGetMethod.invoke(prop, sampleKey);
            return (val instanceof Number) ? bwEnumToMhz(((Number) val).intValue()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns cached BW for the given intraRow (0=PCell, 1..4=SCell N).
     * @param adapterSampleKey sources[0].f5509c — the sample key the adapter used for its data.
     *                         Pass -1 as fallback if unavailable.
     */
    private String cachedBw(int intraRow, long adapterSampleKey) {
        // Invalidate when the adapter's data sample changed (covers scrub+unfreeze mismatch).
        if (adapterSampleKey > 0 && adapterSampleKey != bwCacheSampleKey) {
            bwCacheValid = false;
        }
        if (!bwCacheValid) {
            long qt = adapterSampleKey > 0 ? adapterSampleKey : getWorkspaceQueryTime();
            fillBwCache(qt);
        }
        if (intraRow < 0 || intraRow > MAX_SCELLS) return null;
        return bwCache[intraRow];
    }

    private long getWorkspaceQueryTime() {
        try {
            Object ws = wsSingleton.get(null);
            if (ws != null) {
                long ct = wsCurrentTime.getLong(ws);
                return (ct >= 0) ? ct : Long.MAX_VALUE;
            }
        } catch (Exception ignored) {}
        return Long.MAX_VALUE;
    }

    private static String bwEnumToMhz(int i) {
        // NR BW is reported directly in MHz (e.g. 5, 10, 15, 20, 25, 40, 50, 60, 80, 100 MHz).
        // No enum mapping needed — value IS the MHz figure.
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
            Class<?> adapterClass  = loader.loadClass("a8.h$a");
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

                    // Re-resolve (isSource0, intraRow) on every getView — prevents stale-tag
                    // bug where a recycled row from source-1 would show source-0 BW data.
                    int     position  = (int) chain.getArg(0);
                    boolean isSource0 = false;
                    int     intraRow  = position;
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

                    String bwText = isSource0 ? cachedBw(intraRow, adapterSampleKey) : null;

                    // ---- Already-injected fast path ----
                    Object bvTag = row.getTag(BW_VIEW_TAG_KEY);
                    if (bvTag instanceof TextView) {
                        TextView bwView = (TextView) bvTag;
                        bwView.setText(bwText != null ? bwText : "-");
                        if (bwText != null) {
                            bwView.setTextColor(0xFFFFFFFF);
                        } else {
                            if (childCount > 3 && row.getChildAt(3) instanceof TextView)
                                bwView.setTextColor(
                                        ((TextView) row.getChildAt(3)).getTextColors());
                        }
                        return result;
                    }

                    // ---- First injection path ----
                    // BandColumnHook (outer/registered second) already ran → 7 children.
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

                    row.addView(bwView, 2);

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
            Log.i(TAG, "NrNsaBandwidthColumnHook: hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaBandwidthColumnHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h.I — inject "BW" header label
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

                ll.addView(bwLabel, 2);

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

    /**
     * Install cache-invalidation hook on a8.b$b.g(long, DataSource, short).
     * This method fires once per data tick for both NR-NSA and LTE adapters,
     * right before notifyDataSetChanged() — so BW cache is stale when getView runs.
     * Called from MainHook after construction.
     */
    public void installCacheInvalidationHook() {
        if (!reflectionReady) return;
        try {
            Class<?> bbClass  = loader.loadClass("a8.b$b");
            Class<?> dsClass  = loader.loadClass("com.qtrun.sys.DataSource");
            Method   gMethod  = bbClass.getMethod("g", long.class, dsClass, short.class);
            xposed.hook(gMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    invalidateBwCache();
                    return chain.proceed();
                }
            });
            Log.i(TAG, "NrNsaBandwidthColumnHook: cache-invalidation hook on a8.b$b.g() installed");
        } catch (Exception e) {
            Log.w(TAG, "NrNsaBandwidthColumnHook: cache-invalidation hook failed: " + e);
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
