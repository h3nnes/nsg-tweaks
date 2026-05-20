package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks a8.h (NRNSACellsFragment) to inject a Band column into the NSA NR cell table.
 *
 * Data path (verified against dex 4.8.4):
 *   Workspace.j            → Workspace singleton (enum)
 *   Workspace.a            → short moduleIndex
 *   Workspace.c            → DataSource
 *   Workspace.g            → long replay cursor (-1 when live)
 *   DataSource.getProperty(key, moduleIndex) → Property
 *   Property.b(cursorTime) → Property$Iterator positioned at cursor
 *   Property$Iterator.end()   → boolean
 *   Property$Iterator.key()   → long sample key
 *   Property.get(key)         → Object (Integer for band; Object[] for SCell band)
 *
 * Keys:
 *   NR5G::Serving_Cell::NR_Band          — PCell (intraRow=0); value is scalar Integer
 *   NR5G::Serving_Cell::SCell::NR_SCell_Band — SCells (intraRow>=1); value is Object[]
 *     Property is fetched with workspace moduleIndex (Workspace.a); array is indexed by
 *     (intraRow-1) to select the correct SCell's band number.
 *
 * We do NOT touch k8.c.f5504a — creating Property objects directly bypasses the
 * all-or-nothing z8 guard entirely.
 */
public class BandColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String KEY_PCELL_BAND = "NR5G::Serving_Cell::NR_Band";
    private static final String KEY_SCELL_BAND = "NR5G::Serving_Cell::SCell::NR_SCell_Band";
    private static final int MAX_SCELLS = 4;

    private static final String TAG_BAND_INJECTED = "nsg_band_injected";
    // Keyed tag slot — distinct from NrNsaBandwidthColumnHook's slot so the two hooks
    // can independently track injection state on the same View without overwriting each other.
    private static final int BAND_TAG_KEY = "nsg_band_injected".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Workspace reflection
    private Field wsSingleton;   // Workspace.j  → Workspace (static)
    private Field wsModuleIndex; // Workspace.a  → short
    private Field wsDataSource;  // Workspace.c  → DataSource
    private Field wsCurrentTime; // Workspace.g  → long replay cursor (-1 = live)

    // Property reflection
    private Method dsGetProperty;       // DataSource.getProperty(String, int) → Property
    private Method propIterMethod;      // Property.b(long) → Property$Iterator
    private Method propGetMethod;       // Property.get(long key) → Object
    private Method iterEndMethod;       // Property$Iterator.end() → boolean
    private Method iterKeyMethod;       // Property$Iterator.key() → long
    private Method iterValueMethod;     // Property$Iterator.value() → Object (kept, unused)

    // Adapter reflection
    private Field  eField;  // a8.b$b.e → per-source array
    private Method hMethod; // a8.b$b.h(int) → Pair<k8.c, Integer>

    private boolean reflectionReady = false;

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
            wsCurrentTime  = wsClass.getField("g");  // long replay cursor

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            propGetMethod  = propClass.getMethod("get", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod= iterClass.getMethod("value");

            eField  = bbClass.getField("e");
            hMethod = bbClass.getMethod("h", int.class);

            reflectionReady = true;
            Log.i(TAG, "reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e);
        }
    }

    private Integer readBand(boolean isSource0, int intraRow) {
        if (!reflectionReady) return null;
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) return null;

            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) return null;

            if (!isSource0) return null; // Detected cells — no serving band

            String key;
            int    sCellIndex; // -1 for PCell (scalar value), >=0 for SCell (array index)
            if (intraRow == 0) {
                key = KEY_PCELL_BAND;
                sCellIndex = -1;
            } else {
                int si = intraRow - 1;
                if (si >= MAX_SCELLS) return null;
                key = KEY_SCELL_BAND;
                sCellIndex = si;
            }

            // Property is keyed by workspace moduleIndex (short from Workspace.a)
            Object property = dsGetProperty.invoke(dataSource, key, moduleIndex);
            if (property == null) {
                Log.w(TAG, "readBand: no property for key=" + key + " moduleIndex=" + moduleIndex);
                return null;
            }

            // Use replay cursor time so values track playback position.
            // Workspace.g is -1 when live; fall back to Long.MAX_VALUE in that case.
            long cursorTime = wsCurrentTime.getLong(ws);
            long queryTime  = (cursorTime >= 0) ? cursorTime : Long.MAX_VALUE;

            Object iterator = propIterMethod.invoke(property, queryTime);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;

            // Use property.get(iterator.key()) — matches NSG's own k8.b.f() read pattern.
            long   sampleKey = (long) iterKeyMethod.invoke(iterator);
            Object value     = propGetMethod.invoke(property, sampleKey);
            if (value == null) return null;

            // SCell band property returns Object[] (one entry per SCell); PCell returns scalar.
            if (sCellIndex >= 0) {
                if (value.getClass().isArray()) {
                    Object[] arr = (Object[]) value;
                    if (sCellIndex >= arr.length) return null;
                    value = arr[sCellIndex];
                } else {
                    // Unexpected: scalar for SCell key — only valid if single SCell
                    if (sCellIndex != 0) return null;
                }
            }

            return (value instanceof Number) ? ((Number) value).intValue() : null;
        } catch (Exception e) {
            Log.w(TAG, "readBand failed: " + e);
            return null;
        }
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
            Log.e(TAG, "hookGetView skipped — reflection not ready");
            return;
        }
        try {
            Class<?> adapterClass = loader.loadClass("a8.h$a");
            Method   getViewMethod = adapterClass.getMethod("getView", int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result  = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    int    position = (int) chain.getArg(0);
                    View   rowView  = (View) result;
                    if (rowView == null) return result;

                    LinearLayout row = findHorizontalRow(rowView);
                    if (row == null) return result;

                    // Resolve (isSource0, intraRow) via adapter.h(position)
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
                                    && pair.first == sources[0]);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "h(position) failed: " + e);
                    }

                    // "-" for non-serving rows (matches NSG convention); number for serving rows.
                    String  bandText = "-";
                    Integer band     = readBand(isSource0, intraRow);
                    if (band != null) bandText = String.valueOf(band);

                    // Already injected — just refresh text in child[1]
                    if (TAG_BAND_INJECTED.equals(row.getTag(BAND_TAG_KEY))) {
                        TextView existing = (TextView) row.getChildAt(1);
                        existing.setText(bandText);
                        // Re-apply colour: white for real band numbers, PCI colour for "-".
                        // child[2] is PCI after injection.
                        if (band != null) {
                            existing.setTextColor(0xFFFFFFFF);
                        } else {
                            existing.setTextColor(((TextView) row.getChildAt(2)).getTextColors());
                        }
                        return result;
                    }

                    // First injection — must have exactly 6 children
                    if (row.getChildCount() != 6) return result;

                    // Copy font size/typeface from PCI (child[1] before insertion).
                    // Do NOT copy the ColorStateList — on the first getView call NSG has not yet
                    // applied its active-white colour, so we'd snapshot a stale grey.
                    // Instead: white for rows that have a real band number, PCI colour for "-" rows.
                    TextView pciView = (TextView) row.getChildAt(1);
                    TextView bandView = new TextView(rowView.getContext());
                    bandView.setText(bandText);
                    bandView.setGravity(android.view.Gravity.CENTER);
                    bandView.setMaxLines(1);
                    bandView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pciView.getTextSize());
                    bandView.setTypeface(pciView.getTypeface());
                    if (band != null) {
                        bandView.setTextColor(0xFFFFFFFF); // always white for active band numbers
                    } else {
                        bandView.setTextColor(pciView.getTextColors()); // inherit for "-" rows
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * rowView.getContext().getResources().getDisplayMetrics().density));
                    lp.weight = 0.10f;
                    bandView.setLayoutParams(lp);

                    row.addView(bandView, 1);

                    float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                    for (int i = 0; i < row.getChildCount() && i < weights.length; i++) {
                        LinearLayout.LayoutParams clp =
                                (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
                        clp.weight = weights[i];
                        row.getChildAt(i).setLayoutParams(clp);
                    }

                    row.setTag(BAND_TAG_KEY, TAG_BAND_INJECTED);
                    Log.d(TAG, "injected Band col pos=" + position + " band=" + bandText);
                    return result;
                }
            });
            Log.i(TAG, "hookGetView installed");
        } catch (Exception e) {
            Log.e(TAG, "hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: a8.h.I — inject "Band" header label after layout is posted
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
                    if (listId == 0) { Log.w(TAG, "list_1 id not found"); return result; }

                    ListView listView = (ListView) fragmentView.findViewById(listId);
                    if (listView == null) return result;

                    listView.post(() -> injectBandHeader(listView));
                    return result;
                }
            });
            Log.i(TAG, "hookOnCreateView installed");
        } catch (Exception e) {
            Log.e(TAG, "hookOnCreateView failed: " + e);
        }
    }

    private void injectBandHeader(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL || ll.getChildCount() != 6) continue;
                if (TAG_BAND_INJECTED.equals(ll.getTag(BAND_TAG_KEY))) return;

                TextView bandLabel = new TextView(listView.getContext());
                bandLabel.setText("Band");
                bandLabel.setGravity(android.view.Gravity.CENTER);
                bandLabel.setMaxLines(1);
                bandLabel.setTextColor(resolveColor(listView.getContext(), android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = 0.10f;
                bandLabel.setLayoutParams(lp);

                ll.addView(bandLabel, 1);

                float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    clp.weight = weights[j];
                    ll.getChildAt(j).setLayoutParams(clp);
                }

                ll.setTag(BAND_TAG_KEY, TAG_BAND_INJECTED);
                Log.i(TAG, "injectBandHeader: done at child " + i);
                return;
            }
            Log.w(TAG, "injectBandHeader: header not found (" + listView.getChildCount() + " children)");
        } catch (Exception e) {
            Log.w(TAG, "injectBandHeader failed: " + e);
        }
    }

    /**
     * Finds the horizontal 6-child (or 7-child after injection) LinearLayout.
     * Handles both flat (nr_cell_list_row_nsa) and wrapped (nr_cell_list_row_double_nsa).
     */
    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        // 8 children: NrNsaBandwidthColumnHook has already injected BW — the row carries
        // TAG_BAND_INJECTED, so getView will refresh the text and return early without
        // re-applying weights. We still need to return the row so that tag check works.
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() == 6 || ll.getChildCount() == 7 || ll.getChildCount() == 8)) {
            return ll;
        }
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                // 8 children: same as above — row was already processed; early-return path
                // in getView handles it via TAG_BAND_INJECTED without reaching the weights loop.
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() == 6 || inner.getChildCount() == 7 || inner.getChildCount() == 8)) {
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
