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
 * Performance design:
 *  - NO convertView null trick: we let NSG recycle views normally.
 *  - First-injection path (6-child main row): inflates once, removes strip, injects column.
 *  - Already-injected path (7-child main row, tag set): only updates text + color — zero
 *    View construction, zero LayoutParams changes, zero strip removal.
 *  - The info strip is present on the original inflated view and absent on recycled ones
 *    (because we already removed it); the already-injected check at child[1] is an int
 *    tag compare — near-zero cost.
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

    private static final String TAG = "NSGBandHook";

    // Integer tag key used on the main-row LinearLayout to mark injection state.
    // Value stored: TAG_ARFCN_VIEW_KEY points to the injected ARFCN TextView directly.
    private static final int BAND_TAG_KEY      = "nsg_band_injected".hashCode();
    private static final String TAG_BAND_INJECTED = "nsg_band_injected";

    // Second tag slot: store the injected TextView reference for O(1) update on recycle.
    private static final int ARFCN_VIEW_TAG_KEY = "nsg_band_arfcn_view".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    public BandColumnHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
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
                    // Let NSG handle convertView recycling normally — no null trick.
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

                    // ---- Already-injected fast path (recycled view) ----
                    // The ARFCN TextView is stored as a tag on the main row for O(1) update.
                    Object arfcnTag = mainRow.getTag(ARFCN_VIEW_TAG_KEY);
                    if (arfcnTag instanceof TextView) {
                        // NSG has already updated everything except our injected column.
                        // Read ARFCN from the info strip if it is still present (happens when
                        // NSG inflated a fresh view before we recycled it, which can occur on
                        // the first pass of a new list population).
                        String arfcn = null;
                        if (outerVert.getChildCount() >= 2) {
                            arfcn = readArfcnFromSubRow(outerVert);
                            hideInfoStrip(outerVert); // hide but keep for NSG's findViewById
                        }
                        TextView arfcnView = (TextView) arfcnTag;
                        if (arfcn != null) {
                            arfcnView.setText(arfcn);
                        }
                        // Re-read color from PCI each time — NSG updates PCI color per row.
                        // PCI is child[2] after injection (was child[1] before).
                        android.content.res.ColorStateList color = pciColor(mainRow);
                        if (color != null) arfcnView.setTextColor(color);
                        return result;
                    }

                    // ---- First-injection path (fresh inflated view, 6-child main row) ----
                    if (mainRow.getChildCount() != 6) return result;

                    String arfcn = readArfcnFromSubRow(outerVert);
                    if (arfcn == null) arfcn = "-";

                    hideInfoStrip(outerVert);

                    View refRaw = mainRow.getChildAt(1); // PCI column (child[1] in fresh row)
                    if (!(refRaw instanceof TextView)) return result;
                    TextView refView = (TextView) refRaw;

                    TextView arfcnView = new TextView(rowView.getContext());
                    arfcnView.setText(arfcn);
                    arfcnView.setGravity(android.view.Gravity.CENTER);
                    arfcnView.setMaxLines(1);
                    arfcnView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, refView.getTextSize());
                    arfcnView.setTypeface(refView.getTypeface());

                    // Color from PCI (child[1] in fresh 6-child row = refView itself).
                    android.content.res.ColorStateList arfcnColor = refView.getTextColors();
                    arfcnView.setTextColor(arfcnColor);

                    float density = rowView.getContext().getResources().getDisplayMetrics().density;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, (int) (21 * density));
                    lp.weight = 0.12f;
                    arfcnView.setLayoutParams(lp);

                    mainRow.addView(arfcnView, 1);

                    // Redistribute 7 columns. NrNsaBandwidthColumnHook will redistribute to 8.
                    float[] weights = {0.03f, 0.12f, 0.10f, 0.09f, 0.22f, 0.22f, 0.22f};
                    for (int i = 0; i < 7; i++) {
                        View child = mainRow.getChildAt(i);
                        ViewGroup.LayoutParams rawLp = child.getLayoutParams();
                        if (!(rawLp instanceof LinearLayout.LayoutParams)) continue;
                        ((LinearLayout.LayoutParams) rawLp).weight = weights[i];
                        child.setLayoutParams(rawLp);
                    }

                    // Store injected view reference for fast update on recycle.
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

    /** Returns PCI column's ColorStateList from an already-injected row (child[2] = PCI). */
    private android.content.res.ColorStateList pciColor(LinearLayout mainRow) {
        // After injection: [Serving(0), ARFCN(1), PCI(2), Beam(3), RSRP(4), RSRQ(5), SINR(6)]
        if (mainRow.getChildCount() <= 2) return null;
        View v = mainRow.getChildAt(2);
        if (!(v instanceof TextView)) return null;
        return ((TextView) v).getTextColors();
    }

    private String readArfcnFromSubRow(LinearLayout outerVert) {
        if (outerVert.getChildCount() < 2) return null;
        View infoStripView = outerVert.getChildAt(1);
        if (!(infoStripView instanceof LinearLayout)) return null;
        LinearLayout infoStrip = (LinearLayout) infoStripView;
        String arfcn = textOf(infoStrip, 1); // tvRowCellName — common case
        if (arfcn != null) return arfcn;
        return textOf(infoStrip, 0);           // tvRowRFID — tower-DB match fallback
    }

    private static String textOf(LinearLayout ll, int idx) {
        if (ll.getChildCount() <= idx) return null;
        View v = ll.getChildAt(idx);
        if (!(v instanceof TextView)) return null;
        String t = ((TextView) v).getText().toString().trim();
        return t.isEmpty() ? null : t;
    }

    private void hideInfoStrip(LinearLayout outerVert) {
        if (outerVert.getChildCount() >= 2)
            outerVert.getChildAt(1).setVisibility(View.GONE);
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }
}
