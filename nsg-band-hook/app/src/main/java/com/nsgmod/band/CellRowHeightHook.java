package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

/**
 * Collapses the CSV info sub-row of LTE cell-table rows when no cell name /
 * cell ID text is available.
 *
 * Registered with the shared CellTableRowDispatcher (single interceptor per
 * runtime getView method). This feature is the LAST getView feature registered
 * in MainHook, so in the legacy interceptor chain it was the innermost hook:
 * its pre-proceed checks ran right before the original getView and its
 * adjustRowHeight ran first after the original returned. The dispatcher
 * reproduces this exactly (beforeProceed in registration order, afterProceed
 * in reverse registration order).
 */
public class CellRowHeightHook {
    private static final String TAG = "NSGBandHook";
    private static final int ADAPTER_TYPE_LTE = 4;

    // Tag slots on the recycled row view for the NSG sub-row TextViews — avoids
    // a full findViewById traversal on every getView (row recycling reuses the
    // same view instance, so the cached references stay valid).
    private static final int CELL_NAME_TAG_KEY = "nsg_crh_cellname".hashCode();
    private static final int CELL_ID_TAG_KEY   = "nsg_crh_cellid".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public CellRowHeightHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installLteHook();
    }

    private void installLteHook() {
        // a8.f$a extends b.AbstractC0008b
        boolean registered =
                CellTableRowDispatcher.register(xposed, loader, "a8.f$a", "CellRowHeightHook",
                new CellTableRowDispatcher.RowProcessor() {
                    /** Set by beforeProceed; consumed by afterProceed for the same call. */
                    private boolean active;

                    @Override
                    public void beforeProceed(CellTableRowDispatcher.RowContext ctx) {
                        // Adapter-type check first (cheap volatile/field read),
                        // then the toggle.
                        active = ctx.isAdapterType(ADAPTER_TYPE_LTE)
                                && SettingsToggleHook.cellRowHeightEnabled();
                    }

                    @Override
                    public void afterProceed(CellTableRowDispatcher.RowContext ctx, Object result) {
                        if (!active) return;
                        View resultView = (View) result;
                        if (resultView == null) return;
                        adjustRowHeight(resultView, "LTE");
                    }
                });
        if (registered) Log.i(TAG, "CellRowHeightHook: installed (LTE)");
    }

    private void adjustRowHeight(View rowView, String rat) {
        try {
            int[] ids = CellTableRowDispatcher.cellRowIds(rowView.getResources());
            int cellNameId = ids[CellTableRowDispatcher.ROW_ID_CELL_NAME];
            int cellIdId = ids[CellTableRowDispatcher.ROW_ID_CELL_ID];

            if (cellNameId == 0 || cellIdId == 0) {
                // Not a double-height row layout
                return;
            }

            TextView cellNameView = (TextView) rowView.getTag(CELL_NAME_TAG_KEY);
            if (cellNameView == null) {
                cellNameView = rowView.findViewById(cellNameId);
                if (cellNameView != null) rowView.setTag(CELL_NAME_TAG_KEY, cellNameView);
            }
            TextView cellIdView = (TextView) rowView.getTag(CELL_ID_TAG_KEY);
            if (cellIdView == null) {
                cellIdView = rowView.findViewById(cellIdId);
                if (cellIdView != null) rowView.setTag(CELL_ID_TAG_KEY, cellIdView);
            }

            if (cellNameView == null || cellIdView == null) {
                return;
            }

            // Get the parent LinearLayout that contains both cell name and cell ID
            // This is the second row of the double-height layout
            ViewGroup secondRow = (ViewGroup) cellNameView.getParent();
            if (secondRow == null) {
                return;
            }

            CharSequence cellNameText = cellNameView.getText();
            CharSequence cellIdText = cellIdView.getText();

            boolean hasCellName = cellNameText != null && cellNameText.length() > 0;
            boolean hasCellId = cellIdText != null && cellIdText.length() > 0;

            if (!hasCellName && !hasCellId) {
                // No CSV data for this cell — collapse the entire second row
                secondRow.setVisibility(View.GONE);
            } else {
                // Has CSV data — ensure the second row is visible
                secondRow.setVisibility(View.VISIBLE);
                cellNameView.setVisibility(View.VISIBLE);
                cellIdView.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            Log.w(TAG, "adjustRowHeight failed: " + e);
        }
    }
}
