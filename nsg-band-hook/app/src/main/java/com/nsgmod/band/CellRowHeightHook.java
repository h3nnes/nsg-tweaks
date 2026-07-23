package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

public class CellRowHeightHook {
    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public CellRowHeightHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installLteHook();
        installNrNsaHook();
    }

    private void installLteHook() {
        try {
            // a8.f$a extends b.AbstractC0008b
            Class<?> adapterClass = ClassMapping.loadClass("a8.f$a", loader);
            if (adapterClass == null) {
                Log.i(TAG, "CellRowHeightHook: a8.f$a not available, skipping LTE hook");
                return;
            }
            Method getViewMethod = adapterClass.getDeclaredMethod(
                    "getView", int.class, View.class, ViewGroup.class);
            getViewMethod.setAccessible(true);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Check toggle first
                    if (!SettingsToggleHook.cellRowHeightEnabled()) {
                        return chain.proceed();
                    }
                    View resultView = (View) chain.proceed();
                    if (resultView == null) return null;
                    adjustRowHeight(resultView, "LTE");
                    return resultView;
                }
            });
            Log.i(TAG, "CellRowHeightHook: installed (LTE)");
        } catch (Exception e) {
            Log.e(TAG, "LTE hook failed: " + e);
        }
    }

    private void installNrNsaHook() {
        try {
            Class<?> adapterClass = ClassMapping.loadClass("a8.h$a", loader);
            if (adapterClass == null) {
                Log.i(TAG, "CellRowHeightHook: a8.h$a not available, skipping NR-NSA hook");
                return;
            }
            Method getViewMethod = adapterClass.getDeclaredMethod(
                    "getView", int.class, View.class, ViewGroup.class);
            getViewMethod.setAccessible(true);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    // Check toggle first
                    if (!SettingsToggleHook.cellRowHeightEnabled()) {
                        return chain.proceed();
                    }
                    View resultView = (View) chain.proceed();
                    if (resultView == null) return null;
                    adjustRowHeight(resultView, "NR-NSA");
                    return resultView;
                }
            });
            Log.i(TAG, "CellRowHeightHook: installed (NR-NSA)");
        } catch (Exception e) {
            Log.e(TAG, "NR-NSA hook failed: " + e);
        }
    }

    private void adjustRowHeight(View rowView, String rat) {
        try {
            String pkg = "com.qtrun.QuickTest";
            int cellNameId = rowView.getResources().getIdentifier("tvRowCellName", "id", pkg);
            int cellIdId = rowView.getResources().getIdentifier("tvRowCellID", "id", pkg);

            if (cellNameId == 0 || cellIdId == 0) {
                // Not a double-height row layout
                return;
            }

            TextView cellNameView = rowView.findViewById(cellNameId);
            TextView cellIdView = rowView.findViewById(cellIdId);

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
