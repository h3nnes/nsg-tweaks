package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.libxposed.api.XposedInterface;

public class CellRowHeightHook {
    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public CellRowHeightHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installHook();
    }

    private void installHook() {
        boolean lte = CellTableRowDispatcher.register(xposed, loader, "a8.f$a",
                "CellRowHeightHook-LTE", newProcessor());
        boolean nrnsa = CellTableRowDispatcher.register(xposed, loader, "a8.h$a",
                "CellRowHeightHook-NRNSA", newProcessor());
        if (lte || nrnsa)
            Log.i(TAG, "CellRowHeightHook: installed (LTE=" + lte + ", NRNSA=" + nrnsa + ")");
    }

    private CellTableRowDispatcher.RowProcessor newProcessor() {
        return new CellTableRowDispatcher.RowProcessor() {
            private boolean active;

            @Override
            public void beforeProceed(CellTableRowDispatcher.RowContext ctx) {
                active = SettingsToggleHook.cellRowHeightEnabled();
            }

            @Override
            public void afterProceed(CellTableRowDispatcher.RowContext ctx, Object result) {
                if (!active) return;
                View resultView = (View) result;
                if (resultView == null) return;
                adjustRowHeight(resultView);
            }
        };
    }

    private void adjustRowHeight(View rowView) {
        try {
            if (!(rowView instanceof LinearLayout)) return;
            LinearLayout outerVert = (LinearLayout) rowView;
            if (outerVert.getOrientation() != LinearLayout.VERTICAL) return;
            if (outerVert.getChildCount() < 2) return;

            View subRow = outerVert.getChildAt(1);
            if (!(subRow instanceof ViewGroup)) {
                subRow.setVisibility(View.GONE);
                return;
            }
            ViewGroup subRowVg = (ViewGroup) subRow;

            boolean hasData = false;
            int[] ids = CellTableRowDispatcher.cellRowIds(subRowVg.getResources());
            if (BandColumnHook.isLteCellDbLoaded() || BandColumnHook.isNrCellDbLoaded()) {
                int cellNameId = ids[CellTableRowDispatcher.ROW_ID_CELL_NAME];
                int cellIdId   = ids[CellTableRowDispatcher.ROW_ID_CELL_ID];
                if (cellNameId != 0 && cellIdId != 0) {
                    View cn = subRowVg.findViewById(cellNameId);
                    View ci = subRowVg.findViewById(cellIdId);
                    if (cn instanceof TextView && ci instanceof TextView) {
                        CharSequence cnText = ((TextView) cn).getText();
                        CharSequence ciText = ((TextView) ci).getText();
                        hasData = (cnText != null && cnText.length() > 0)
                                || (ciText != null && ciText.length() > 0);
                    }
                }
            }
            if (hasData) {
                subRow.setVisibility(View.VISIBLE);
            } else {
                subRow.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.w(TAG, "adjustRowHeight failed: " + e);
        }
    }
}
