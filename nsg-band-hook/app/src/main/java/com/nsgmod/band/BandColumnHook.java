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
 * Hooks a8.h (NRNSACellsFragment) to inject an ARFCN column (labelled "Band") at index 1
 * of the main NR cell row, and removes the NSG-added info sub-row that previously showed
 * the ARFCN below each cell entry.
 *
 * In NSG v4.8.6 the row layout changed to nr_cell_list_row_double_nsa — a vertical
 * LinearLayout with:
 *   child 0: horizontal main row  [Serving, PCI, Beam, RSRP, RSRQ, SINR]  (6 cols)
 *   child 1: horizontal info strip [tvRowRFID=ARFCN, tvRowCellName, tvRowCellID]
 *
 * We read the ARFCN string that NSG already wrote into tvRowRFID (sub-row child 1, column 0),
 * inject it into the main row at index 1, and remove the info sub-row so only the
 * main row remains.  This works for all row types including detected/neighbour cells
 * because NSG populates tvRowRFID for those too.
 *
 * Final main-row column order after both hooks:
 *   [Serving, ARFCN("Band"), BW, PCI, Beam, RSRP, RSRQ, SINR]
 * Weights: {0.03f, 0.12f, 0.10f, 0.10f, 0.09f, 0.17f, 0.17f, 0.16f}  (set by NrNsaBandwidthColumnHook)
 * After BandColumnHook only:
 *   {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f}  (7 cols)
 */
public class BandColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final String TAG_BAND_INJECTED = "nsg_band_injected";
    private static final int    BAND_TAG_KEY       = "nsg_band_injected".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private boolean reflectionReady = false;

    // Adapter reflection — still needed to resolve (source, intraRow) for logging/future use,
    // but ARFCN is now read directly from the View rather than from workspace.
    private Field  eField;  // a8.b$b.e → sources array
    private Method hMethod; // a8.b$b.h(int) → Pair<a8.b.a, Integer>

    public BandColumnHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> bbClass = loader.loadClass("a8.b$b");
            eField  = bbClass.getField("e");
            hMethod = bbClass.getMethod("h", int.class);
            reflectionReady = true;
            Log.i(TAG, "BandColumnHook: reflection ready");
        } catch (Exception e) {
            // Field/method may not be needed if we fall back to position-based intraRow;
            // mark ready anyway so the hook still fires.
            reflectionReady = true;
            Log.w(TAG, "BandColumnHook: initReflection partial (adapter reflection unavailable): " + e);
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
        try {
            Class<?> adapterClass  = loader.loadClass("a8.h$a");
            Method   getViewMethod = adapterClass.getMethod("getView", int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // If convertView is already our modified view, null it out so NSG
                    // always inflates a fresh row. Otherwise NSG's fixed getChildAt()
                    // indices break on the already-modified 8-child row and NPEs.
                    View convertView = (View) chain.getArg(1);
                    boolean needsNullConvert = false;
                    if (convertView instanceof LinearLayout) {
                        LinearLayout cvOuter = (LinearLayout) convertView;
                        LinearLayout cvMain  = (cvOuter.getOrientation() == LinearLayout.VERTICAL
                                && cvOuter.getChildCount() >= 1
                                && cvOuter.getChildAt(0) instanceof LinearLayout)
                                ? (LinearLayout) cvOuter.getChildAt(0) : null;
                        if (cvMain != null && TAG_BAND_INJECTED.equals(cvMain.getTag(BAND_TAG_KEY))) {
                            needsNullConvert = true;
                        }
                    }

                    Object result = needsNullConvert
                            ? chain.proceed(new Object[]{ chain.getArg(0), null, chain.getArg(2) })
                            : chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    int  position = (int) chain.getArg(0);
                    View rowView  = (View) result;
                    if (rowView == null) return result;

                    // rowView is the outer vertical LinearLayout (nr_cell_list_row_double_nsa).
                    LinearLayout outerVert = getOuterVertical(rowView);
                    if (outerVert == null) return result;

                    LinearLayout mainRow = getMainRow(outerVert);
                    if (mainRow == null) return result;

                    // --- Already injected path (recycled view) ---
                    if (TAG_BAND_INJECTED.equals(mainRow.getTag(BAND_TAG_KEY))) {
                        // convertView was nulled before chain.proceed(), so NSG inflated a
                        // fresh row with the info strip present. Re-read ARFCN from it.
                        String arfcn = readArfcnFromSubRow(outerVert);
                        if (arfcn == null) arfcn = ((TextView) mainRow.getChildAt(1)).getText().toString();
                        removeInfoStrip(outerVert);

                        // Borrow color from PCI (child[1] before injection = child[1] here
                        // since this is the fresh-inflated row with 6 children).
                        android.content.res.ColorStateList arfcnColor =
                                colorFromPciAtFreshRow(mainRow);
                        TextView existing = (TextView) mainRow.getChildAt(1);
                        existing.setText(arfcn);
                        if (arfcnColor != null) existing.setTextColor(arfcnColor);
                        return result;
                    }

                    // --- First injection path ---
                    // Main row must have exactly 6 children.
                    if (mainRow.getChildCount() != 6) {
                        Log.w(TAG, "BandColumnHook: pos=" + position
                                + " unexpected mainRow.childCount=" + mainRow.getChildCount()
                                + " (expected 6 for first injection) — skipping");
                        return result;
                    }

                    // Read ARFCN from info strip.
                    String arfcn = readArfcnFromSubRow(outerVert);
                    if (arfcn == null) arfcn = "-";

                    // Remove the info strip — we've captured what we need.
                    removeInfoStrip(outerVert);

                    // Inject ARFCN column at index 1 of the main row.
                    View refRaw = mainRow.getChildAt(1);
                    if (!(refRaw instanceof TextView)) {
                        Log.e(TAG, "BandColumnHook: child[1] is not a TextView — aborting injection");
                        return result;
                    }
                    TextView refView   = (TextView) refRaw;
                    TextView arfcnView = new TextView(rowView.getContext());
                    arfcnView.setText(arfcn);
                    arfcnView.setGravity(android.view.Gravity.CENTER);
                    arfcnView.setMaxLines(1);
                    arfcnView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, refView.getTextSize());
                    arfcnView.setTypeface(refView.getTypeface());
                    // Borrow color from PCI column (child[1] in the fresh 6-child row).
                    // PCI is always k0-colored by NSG, encoding serving (bright) vs neighbour (grey).
                    android.content.res.ColorStateList arfcnColor = colorFromPciAtFreshRow(mainRow);
                    if (arfcnColor != null) {
                        arfcnView.setTextColor(arfcnColor);
                    } else {
                        arfcnView.setTextColor(refView.getTextColors());
                    }

                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * rowView.getContext().getResources().getDisplayMetrics().density));
                    lp.weight = 0.12f;
                    arfcnView.setLayoutParams(lp);

                    mainRow.addView(arfcnView, 1);

                    // Redistribute 7 columns (NrNsaBandwidthColumnHook will redistribute to 8 later).
                    float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                    for (int i = 0; i < mainRow.getChildCount() && i < weights.length; i++) {
                        View child = mainRow.getChildAt(i);
                        android.view.ViewGroup.LayoutParams rawLp = child.getLayoutParams();
                        if (!(rawLp instanceof LinearLayout.LayoutParams)) continue;
                        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) rawLp;
                        clp.weight = weights[i];
                        child.setLayoutParams(clp);
                    }

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
                    if (listId == 0) { Log.w(TAG, "BandColumnHook: list_1 id not found"); return result; }

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
                // Header row is a flat horizontal LinearLayout with 6 children (no double-row wrapper).
                if (ll.getOrientation() != LinearLayout.HORIZONTAL || ll.getChildCount() != 6) continue;
                if (TAG_BAND_INJECTED.equals(ll.getTag(BAND_TAG_KEY))) return;

                TextView bandLabel = new TextView(listView.getContext());
                bandLabel.setText("Band");
                bandLabel.setGravity(android.view.Gravity.CENTER);
                bandLabel.setMaxLines(1);
                bandLabel.setTextColor(resolveColor(listView.getContext(), android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.weight = 0.12f;
                bandLabel.setLayoutParams(lp);

                ll.addView(bandLabel, 1);

                float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
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

    /**
     * Returns the outer vertical LinearLayout if rowView is one, otherwise null.
     * In v4.8.6 all cell rows use nr_cell_list_row_double_nsa which is a vertical LL.
     */
    private LinearLayout getOuterVertical(View rowView) {
        if (!(rowView instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) rowView;
        if (ll.getOrientation() == LinearLayout.VERTICAL) return ll;
        return null;
    }

    /**
     * Returns the main horizontal row (child 0) of the outer vertical LL.
     */
    private LinearLayout getMainRow(LinearLayout outerVert) {
        if (outerVert.getChildCount() < 1) return null;
        View first = outerVert.getChildAt(0);
        if (!(first instanceof LinearLayout)) return null;
        LinearLayout row = (LinearLayout) first;
        if (row.getOrientation() != LinearLayout.HORIZONTAL) return null;
        return row;
    }

    /**
     * Returns the ColorStateList from the PCI column of a fresh (6-child) main row.
     * NSG calls k0(pciView, isServing) unconditionally, so this always encodes
     * textColorPrimary (aggregated/serving) or textColorSecondary (neighbour/grey).
     * Child[1] is PCI in the fresh pre-injection 6-child row.
     * Returns null if the child is absent or not a TextView.
     */
    private android.content.res.ColorStateList colorFromPciAtFreshRow(LinearLayout mainRow) {
        if (mainRow.getChildCount() <= 1) return null;
        View v = mainRow.getChildAt(1);
        if (!(v instanceof TextView)) return null;
        return ((TextView) v).getTextColors();
    }

    /**
     * Reads the ARFCN string from the info strip (outer vertical LL child 1).
     *
     * NSG v4.8.6 writes ARFCN into tvRowCellName (child 1) when no GPS/tower-DB
     * match is available (the normal case), and into tvRowRFID (child 0) only when
     * a tower-DB hit is found.  So try child[1] first; fall back to child[0].
     *
     * Returns null if the info strip is absent or already removed.
     */
    private String readArfcnFromSubRow(LinearLayout outerVert) {
        if (outerVert.getChildCount() < 2) return null;
        View infoStripView = outerVert.getChildAt(1);
        if (!(infoStripView instanceof LinearLayout)) return null;
        LinearLayout infoStrip = (LinearLayout) infoStripView;

        // child[1] = tvRowCellName — has ARFCN when no tower-DB match (common case)
        String arfcn = textOf(infoStrip, 1);
        if (arfcn != null) return arfcn;

        // child[0] = tvRowRFID — has ARFCN when tower-DB match is available
        return textOf(infoStrip, 0);
    }

    /** Returns trimmed non-empty text of child[idx] if it is a TextView, else null. */
    private static String textOf(LinearLayout ll, int idx) {
        if (ll.getChildCount() <= idx) return null;
        View v = ll.getChildAt(idx);
        if (!(v instanceof TextView)) return null;
        String t = ((TextView) v).getText().toString().trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Removes the info strip (child 1) from the outer vertical LL if present.
     */
    private void removeInfoStrip(LinearLayout outerVert) {
        if (outerVert.getChildCount() >= 2) {
            outerVert.removeViewAt(1);
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
