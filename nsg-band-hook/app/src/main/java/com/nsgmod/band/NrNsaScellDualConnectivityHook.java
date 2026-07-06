package com.nsgmod.band;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks g8.h (NR-NSA Dual Connectivity page) to show the first NR SCell value
 * stacked beneath the existing NR PCell value in the right-hand NR column.
 *
 * When an NR SCell is aggregated (NR_SCell_PCI[0] is valid) the NR data rows are
 * stretched so each logical row occupies two physical row units: PCell on top,
 * SCell below. The left-hand LTE and label cells in the same row are also
 * stretched so the grid row height stays shared.
 *
 * This mirrors the way h8.b (NR-SA CA Matrix DL) stacks PCell + SCell values
 * in 3x NR-CA mode, adapted to the existing single-row-per-metric layout of
 * g8.h.
 */
public class NrNsaScellDualConnectivityHook {

    private static final String TAG = "NSGBandHook";

    private static final String TARGET_FRAGMENT = "g8.h";
    private static final String K2A_CLASS = "k2.a";
    private static final String V6_A_CLASS = "v6.a";
    private static final String V6_B_CLASS = "v6.b";
    private static final String V6_D_CLASS = "v6.d";
    private static final String V6_E_CLASS = "v6.e";
    private static final String V6_F_CLASS = "v6.f";
    private static final String V6_G_CLASS = "v6.g";
    private static final String DATA_SOURCE_CLASS = "com.qtrun.sys.DataSource";
    private static final String PROPERTY_CLASS = "com.qtrun.sys.Property";
    private static final String ITERATOR_CLASS = "com.qtrun.sys.Property$Iterator";
    private static final String ATTR_A_CLASS = "com.qtrun.sys.a";

    private static final String KEY_SCELL_PCI = "NR5G::Serving_Cell::SCell::NR_SCell_PCI";

    private static final String PROGRESS_TEXT_VIEW_CLASS = "com.qtrun.widget.textview.ProgressTextView";

    private static final String V6F_BAR_SIMPLE_FIELD = "i";
    private static final String V6F_BAR_COLOR_FIELD = "j";
    private static final String V6F_BAR_MAX_FIELD = "k";
    private static final String V6F_VALUE_FIELD = "f";

    private static final String FORMATTER_KEY_FIELD = "a";
    private static final String FORMATTER_FORMAT_FIELD = "b";
    private static final String FORMATTER_INDEX_FIELD = "c";

    private static final float NR_COLUMN_X = 65.0f;
    private static final float LTE_COLUMN_X = 30.0f;
    private static final float LABEL_COLUMN_X = 0.0f;
    private static final float COLUMN_TOLERANCE = 4.0f;
    private static final int FIRST_DATA_ROW = 3;
    private static final int LAST_DATA_ROW = 21;
    private static final int SCELL_PCI_INDEX = 0;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean reflectionReady = false;

    private Field k2aCellsField;        // k2.a.f5440d -> ArrayList<v6.a>
    private Field v6aViewField;         // v6.a.f8100a -> View
    private Field v6aRowField;          // v6.a.f8101b -> float
    private Field v6aHeightField;       // v6.a.f8102c -> float
    private Field v6aXField;            // v6.a.f8103d -> float
    private Field v6dCellsField;        // v6.d.f8109a -> ArrayList<v6.a>

    private Field v6gFormattersField;   // v6.g.f8124f -> ArrayList<com.qtrun.sys.b>
    private Field v6fFormatterField;    // v6.f.f8120g -> com.qtrun.sys.b
    private Field v6eTextField;         // v6.e.f8116f -> String

    private Method dsGetPropertyMethod; // DataSource.getProperty(String, int)
    private Method propIteratorMethod;  // Property.b(long)
    private Method iteratorEndMethod;   // Property$Iterator.end()
    private Method iteratorKeyMethod;   // Property$Iterator.key()
    private Method iteratorValueMethod; // Property$Iterator.value()
    private Method attrAExtractor;      // com.qtrun.sys.a.a(Object)
    private Method attrAFormatter;      // com.qtrun.sys.a.c(Object)
    private Field attrAKeyFieldSetter;       // com.qtrun.sys.a.f3870c = int

    private Constructor<?> attrAConstructorKeyOnly;
    private Constructor<?> attrAConstructorIndexed;

    private Class<?> v6fClass;
    private Method v6fAMethod;
    private Method v6fEMethod;
    private Field v6fSimpleField;
    private Field v6fColorField;
    private Field v6fMaxField;
    private Field v6fValueField;

    private Constructor<?> progressTextViewConstructor;

    private final Map<String, String> pcellKeyOverrides = buildPcellKeyOverrides();

    // Bindings for every row that has an NR column cell.
    private final List<RowBinding> rowBindings = new ArrayList<>();
    // Original geometry for every cell we might stretch, so we can restore it.
    private final Map<Object, float[]> originalGeometry = new HashMap<>();
    // Tracks whether the grid is currently in stretched mode.
    private boolean currentlyStretched = false;

    public NrNsaScellDualConnectivityHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> k2aClass = loader.loadClass(K2A_CLASS);
            Class<?> v6aClass = loader.loadClass(V6_A_CLASS);
            Class<?> v6dClass = loader.loadClass(V6_D_CLASS);
            Class<?> v6eClass = loader.loadClass(V6_E_CLASS);
            v6fClass = loader.loadClass(V6_F_CLASS);
            Class<?> v6gClass = loader.loadClass(V6_G_CLASS);
            Class<?> dsClass = loader.loadClass(DATA_SOURCE_CLASS);
            Class<?> propClass = loader.loadClass(PROPERTY_CLASS);
            Class<?> iterClass = loader.loadClass(ITERATOR_CLASS);
            Class<?> attrAClass = loader.loadClass(ATTR_A_CLASS);

            k2aCellsField = k2aClass.getDeclaredField("d");
            k2aCellsField.setAccessible(true);

            v6aViewField = v6aClass.getDeclaredField("a");
            v6aViewField.setAccessible(true);
            v6aRowField = v6aClass.getDeclaredField("b");
            v6aRowField.setAccessible(true);
            v6aHeightField = v6aClass.getDeclaredField("c");
            v6aHeightField.setAccessible(true);
            v6aXField = v6aClass.getDeclaredField("d");
            v6aXField.setAccessible(true);

            v6dCellsField = v6dClass.getDeclaredField("a");
            v6dCellsField.setAccessible(true);

            v6gFormattersField = v6gClass.getDeclaredField("f");
            v6gFormattersField.setAccessible(true);
            v6fFormatterField = v6fClass.getDeclaredField("g");
            v6fFormatterField.setAccessible(true);
            v6eTextField = v6eClass.getDeclaredField("f");
            v6eTextField.setAccessible(true);

            dsGetPropertyMethod = dsClass.getMethod("getProperty", String.class, int.class);
            propIteratorMethod = propClass.getMethod("b", long.class);
            iteratorEndMethod = iterClass.getMethod("end");
            iteratorKeyMethod = iterClass.getMethod("key");
            iteratorValueMethod = iterClass.getMethod("value");

            attrAExtractor = attrAClass.getDeclaredMethod("a", Object.class);
            attrAFormatter = attrAClass.getDeclaredMethod("c", Object.class);
            attrAKeyFieldSetter = attrAClass.getDeclaredField("c");
            attrAKeyFieldSetter.setAccessible(true);

            attrAConstructorKeyOnly = attrAClass.getConstructor(String.class);
            attrAConstructorIndexed = attrAClass.getConstructor(String.class, int.class, String.class);

            v6fAMethod = v6fClass.getDeclaredMethod("a", long.class, dsClass, short.class);
            v6fAMethod.setAccessible(true);
            v6fEMethod = v6fClass.getDeclaredMethod("e");
            v6fEMethod.setAccessible(true);
            v6fSimpleField = v6fClass.getDeclaredField(V6F_BAR_SIMPLE_FIELD);
            v6fSimpleField.setAccessible(true);
            v6fColorField = v6fClass.getDeclaredField(V6F_BAR_COLOR_FIELD);
            v6fColorField.setAccessible(true);
            v6fMaxField = v6fClass.getDeclaredField(V6F_BAR_MAX_FIELD);
            v6fMaxField.setAccessible(true);
            v6fValueField = v6fClass.getDeclaredField(V6F_VALUE_FIELD);
            v6fValueField.setAccessible(true);

            Class<?> ptvClass = loader.loadClass(PROGRESS_TEXT_VIEW_CLASS);
            progressTextViewConstructor = ptvClass.getConstructor(Context.class, AttributeSet.class);
            progressTextViewConstructor.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: reflection init failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: install skipped, reflection not ready");
            return;
        }
        try {
            hookI();
            hookB();
            hookV6eE();
            hookV6fE();
            hookV6gE();
            Log.i(TAG, "NrNsaScellDualConnectivityHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: install failed: " + e);
        }
    }

    private static Map<String, String> buildPcellKeyOverrides() {
        Map<String, String> map = new HashMap<>();
        map.put("NR5G::Downlink_Measurements::NR_MAC_BLER_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_BLER_DL");
        map.put("NR5G::Downlink_Measurements::NR_RB_Num_Average_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_RB_Num_Average_DL");
        map.put("NR5G::Downlink_Measurements::NR_MCS_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_MCS_DL");
        map.put("NR5G::Downlink_Measurements::NR_Modulation_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_Modulation_DL");
        map.put("NR5G::Downlink_Measurements::NR_Physical_Throughput_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_Physical_Throughput_DL");
        map.put("NR5G::Downlink_Measurements::NR_MAC_Throughput_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_Throughput_DL");
        map.put("NR5G::Downlink_Measurements::NR_RLC_Throughput_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_RLC_Throughput_DL");
        map.put("NR5G::Downlink_Measurements::NR_PDCP_Throughput_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_PDCP_Throughput_DL");
        map.put("NR5G::Downlink_Measurements::NR_ModUsage_256QAM_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_256QAM_DL");
        map.put("NR5G::Downlink_Measurements::NR_ModUsage_64QAM_DL",
                "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_64QAM_DL");
        return map;
    }

    // -------------------------------------------------------------------------
    // Hook 1: v6.b.I(...) — rebuild bindings every time the fragment view is
    // created. v6.b.I() is the real onCreateView; g8.h inherits it.
    // On first creation it calls g8.h.l0(); on later recreations (e.g. swipe
    // back in the view pager) l0() is skipped and only I() runs, so hooking
    // l0() alone leaves stale view references in rowBindings.
    // -------------------------------------------------------------------------
    private void hookI() {
        try {
            Class<?> v6bClass = loader.loadClass(V6_B_CLASS);
            Method iMethod = v6bClass.getDeclaredMethod("I",
                    android.view.LayoutInflater.class,
                    android.view.ViewGroup.class,
                    android.os.Bundle.class);
            iMethod.setAccessible(true);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object fragment = chain.getThisObject();
                        if (fragment != null && fragment.getClass().getName().equals(TARGET_FRAGMENT)) {
                            resetAndRebuild(fragment);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NrNsaScellDualConnectivityHook: I hook failed: " + e);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: hookI failed: " + e);
        }
    }

    private void resetAndRebuild(Object fragment) {
        // If the view was recreated while we were stretched, restore normal
        // geometry before we forget what "normal" looks like.
        if (currentlyStretched) {
            for (RowBinding binding : rowBindings) {
                setGeometry(binding.nrCell, binding.originalRow, 1.0f);
                if (binding.lteCell != null) setGeometry(binding.lteCell, binding.originalRow, 1.0f);
                if (binding.labelCell != null) setGeometry(binding.labelCell, binding.originalRow, 1.0f);
            }
        }

        rowBindings.clear();
        originalGeometry.clear();
        currentlyStretched = false;

        buildBindings(fragment);
    }

    @SuppressWarnings("unchecked")
    private void buildBindings(Object fragment) {
        rowBindings.clear();
        originalGeometry.clear();
        currentlyStretched = false;

        Object k2a = getFieldValue(fragment, TARGET_FRAGMENT, "Y");
        if (k2a == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: fragment.Y is null");
            return;
        }

        List<Object> cells;
        try {
            cells = (List<Object>) k2aCellsField.get(k2a);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot read k2a cells: " + e);
            return;
        }
        if (cells == null) {
            return;
        }

        // Group cells by row.
        Map<Integer, RowCells> rows = new HashMap<>();
        for (Object cell : cells) {
            if (cell == null) continue;
            float x = getFloat(cell, v6aXField);
            float row = getFloat(cell, v6aRowField);
            float height = getFloat(cell, v6aHeightField);
            int rowInt = Math.round(row);

            // Save original geometry for any cell we might touch.
            originalGeometry.put(cell, new float[]{row, height});

            if (!isDataRow(rowInt)) continue;

            RowCells rc = rows.get(rowInt);
            if (rc == null) {
                rc = new RowCells();
                rows.put(rowInt, rc);
            }
            if (approxEquals(x, LABEL_COLUMN_X, COLUMN_TOLERANCE)) {
                rc.label = cell;
            } else if (approxEquals(x, LTE_COLUMN_X, COLUMN_TOLERANCE)) {
                rc.lte = cell;
            } else if (approxEquals(x, NR_COLUMN_X, COLUMN_TOLERANCE)) {
                rc.nr = cell;
            }
        }

        for (Map.Entry<Integer, RowCells> entry : rows.entrySet()) {
            int row = entry.getKey();
            RowCells rc = entry.getValue();
            if (rc.nr == null) continue;

            SCellConfig config = createScellConfig(row, rc.nr);
            if (config == null) continue;

            RowBinding binding = new RowBinding(row, rc.label, rc.lte, rc.nr, config);
            if (V6_F_CLASS.equals(rc.nr.getClass().getName())) {
                binding.barHelper = createBarHelper(rc.nr, config);
            }
            patchPcellKeys(rc.nr);
            rowBindings.add(binding);
        }

        if (!rowBindings.isEmpty()) {
            Log.i(TAG, "NrNsaScellDualConnectivityHook: built " + rowBindings.size() + " row bindings");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: wrap/unwrap NR column views on first/last SCell detection.
    // -------------------------------------------------------------------------
    private boolean anyWrapped() {
        for (RowBinding binding : rowBindings) {
            if (binding.wrapped) return true;
        }
        return false;
    }

    private boolean wrapAllRows(Object fragment) {
        Object k2a = getFieldValue(fragment, TARGET_FRAGMENT, "Y");
        if (k2a == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot wrap, fragment.Y is null");
            return false;
        }
        View gridView = (View) getFieldValue(k2a, K2A_CLASS, "c");
        if (gridView == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot wrap, grid is null");
            return false;
        }

        List<Object> gridCells;
        try {
            gridCells = (List<Object>) v6dCellsField.get(gridView);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot read grid cells for wrap: " + e);
            return false;
        }
        if (gridCells == null) {
            return false;
        }

        // Validate that every unwrapped NR cell still maps to a TextView child.
        ViewGroup gridGroup = (ViewGroup) gridView;
        for (RowBinding binding : rowBindings) {
            if (binding.wrapped) continue;
            int index = gridCells.indexOf(binding.nrCell);
            if (index < 0 || index >= gridGroup.getChildCount()) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot find grid child for row " + binding.originalRow);
                return false;
            }
            View originalView = gridGroup.getChildAt(index);
            if (!(originalView instanceof TextView)) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: grid child is not a TextView for row " + binding.originalRow);
                return false;
            }
        }

        for (RowBinding binding : rowBindings) {
            if (binding.wrapped) continue;

            int index = gridCells.indexOf(binding.nrCell);
            View originalView = gridGroup.getChildAt(index);
            TextView pcellView = (TextView) originalView;
            Context ctx = pcellView.getContext();

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);

            TextView scellView = createScellView(pcellView);
            if (scellView == null) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot create SCell view for row " + binding.originalRow);
                continue;
            }
            if (binding.barHelper != null) {
                try {
                    v6aViewField.set(binding.barHelper, scellView);
                } catch (Exception ignored) {
                }
            }

            gridGroup.removeViewAt(index);
            gridGroup.addView(container, index);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            container.addView(pcellView, lp);
            container.addView(scellView, lp);

            setField(binding.nrCell, v6aViewField, container);

            binding.container = container;
            binding.pcellView = pcellView;
            binding.scellView = scellView;
            binding.wrapped = true;
        }
        return true;
    }

    private void unwrapAllRows(Object fragment) {
        Object k2a = getFieldValue(fragment, TARGET_FRAGMENT, "Y");
        if (k2a == null) return;
        View gridView = (View) getFieldValue(k2a, K2A_CLASS, "c");
        if (gridView == null) return;

        List<Object> gridCells;
        try {
            gridCells = (List<Object>) v6dCellsField.get(gridView);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot read grid cells for unwrap: " + e);
            return;
        }
        if (gridCells == null) return;

        ViewGroup gridGroup = (ViewGroup) gridView;
        for (RowBinding binding : rowBindings) {
            if (!binding.wrapped || binding.container == null || binding.pcellView == null) continue;

            int index = gridCells.indexOf(binding.nrCell);
            if (index < 0 || index >= gridGroup.getChildCount()) continue;
            if (gridGroup.getChildAt(index) != binding.container) continue;

            binding.container.removeView(binding.pcellView);
            gridGroup.removeViewAt(index);
            gridGroup.addView(binding.pcellView, index);

            setField(binding.nrCell, v6aViewField, binding.pcellView);

            binding.container = null;
            binding.pcellView = null;
            binding.scellView = null;
            binding.wrapped = false;
        }
    }

    private TextView createScellView(TextView pcellView) {
        Context ctx = pcellView.getContext();
        TextView scellView;
        try {
            if (pcellView.getClass().getName().equals(PROGRESS_TEXT_VIEW_CLASS)) {
                scellView = (TextView) progressTextViewConstructor.newInstance(ctx, null);
            } else {
                scellView = (TextView) pcellView.getClass().getConstructor(Context.class, AttributeSet.class).newInstance(ctx, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "createScellView: could not mirror PCell view type, falling back to TextView: " + e);
            scellView = new TextView(ctx);
        }
        scellView.setGravity(pcellView.getGravity());
        scellView.setMaxLines(1);
        scellView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pcellView.getTextSize());
        scellView.setTypeface(pcellView.getTypeface());
        scellView.setTextColor(pcellView.getTextColors());
        scellView.setText("-");
        return scellView;
    }

    // -------------------------------------------------------------------------
    // Hook 2: g8.h.b(DataSource, long, short, Object) — data updates & stretch.
    // -------------------------------------------------------------------------
    private void hookB() {
        try {
            Class<?> g8hClass = loader.loadClass(TARGET_FRAGMENT);
            Class<?> dsClass = loader.loadClass(DATA_SOURCE_CLASS);
            Method bMethod = g8hClass.getMethod("b", dsClass, long.class, short.class, Object.class);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        onDataUpdate(chain.getThisObject(), chain.getArg(0),
                                (long) chain.getArg(1), (short) chain.getArg(2));
                    } catch (Exception e) {
                        Log.w(TAG, "NrNsaScellDualConnectivityHook: b hook failed: " + e);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: hookB failed: " + e);
        }
    }

    private void onDataUpdate(Object fragment, Object dataSource, long sampleKey, short moduleIndex) {
        boolean scellPresent = isScellPresent(dataSource, sampleKey, moduleIndex);
        boolean wasStretched = currentlyStretched;
        boolean wasWrapped = anyWrapped();

        if (scellPresent && !currentlyStretched) {
            // Defer wrapping until the first data update that actually has an SCell.
            // Wrapping before this point would alter the PCell-only layout.
            if (wrapAllRows(fragment)) {
                stretchGrid(true);
                currentlyStretched = true;
            }
        } else if (!scellPresent && currentlyStretched) {
            // Restore the original single-row geometry before removing the containers,
            // otherwise the unwrapped PCell views would keep the stretched height.
            stretchGrid(false);
            currentlyStretched = false;
            unwrapAllRows(fragment);
        }

        for (RowBinding binding : rowBindings) {
            updateScellView(binding, dataSource, sampleKey, moduleIndex);
        }

        if (wasStretched != currentlyStretched || wasWrapped != anyWrapped()) {
            requestGridLayout(fragment);
        }
    }

    private void stretchGrid(boolean stretch) {
        for (RowBinding binding : rowBindings) {
            int row = binding.originalRow;
            float newRow = stretch ? (2.0f * row - 3.0f) : row;
            float newHeight = stretch ? 2.0f : 1.0f;

            setGeometry(binding.nrCell, newRow, newHeight);
            if (binding.lteCell != null) setGeometry(binding.lteCell, newRow, newHeight);
            if (binding.labelCell != null) setGeometry(binding.labelCell, newRow, newHeight);
        }

        // In case there are any cells above LAST_DATA_ROW (there should not be in g8.h),
        // shift them by the number of stretched rows when entering stretched mode.
        int stretchOffset = LAST_DATA_ROW - FIRST_DATA_ROW + 1; // 19
        for (Map.Entry<Object, float[]> entry : originalGeometry.entrySet()) {
            Object cell = entry.getKey();
            float[] orig = entry.getValue();
            if (orig[0] > LAST_DATA_ROW) {
                float newRow = stretch ? (orig[0] + stretchOffset) : orig[0];
                setGeometry(cell, newRow, orig[1]);
            }
        }
    }

    private void setGeometry(Object cell, float row, float height) {
        try {
            v6aRowField.setFloat(cell, row);
            v6aHeightField.setFloat(cell, height);
        } catch (Exception ignored) {
        }
    }

    private void requestGridLayout(Object fragment) {
        try {
            Object k2a = getFieldValue(fragment, TARGET_FRAGMENT, "Y");
            if (k2a == null) return;
            View gridView = (View) getFieldValue(k2a, K2A_CLASS, "c");
            if (gridView != null) {
                gridView.requestLayout();
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Hook 4: v6.g/f/e.e() — PCell view updates after f8100a was replaced.
    // -------------------------------------------------------------------------
    private void hookV6eE() {
        hookV6EMethod(V6_E_CLASS);
    }

    private void hookV6fE() {
        hookV6EMethod(V6_F_CLASS);
    }

    private void hookV6gE() {
        hookV6EMethod(V6_G_CLASS);
    }

    private void hookV6EMethod(String className) {
        try {
            Class<?> cls = loader.loadClass(className);
            Method eMethod = cls.getDeclaredMethod("e");
            eMethod.setAccessible(true);

            xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object cell = chain.getThisObject();
                    RowBinding binding = findBinding(cell);
                    if (binding == null || binding.pcellView == null) {
                        return chain.proceed();
                    }

                    // Temporarily restore the cell's view reference to the original PCell
                    // view so the stock e() implementation can update it, then swap back.
                    View container = binding.container;
                    View pcellView = binding.pcellView;
                    try {
                        v6aViewField.set(cell, pcellView);
                        Object result = chain.proceed();
                        return result;
                    } finally {
                        v6aViewField.set(cell, container);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: hookV6EMethod(" + className + ") failed: " + e);
        }
    }

    private RowBinding findBinding(Object nrCell) {
        for (RowBinding binding : rowBindings) {
            if (binding.nrCell == nrCell) {
                return binding;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // SCell data reading and view update.
    // -------------------------------------------------------------------------
    private void updateScellView(RowBinding binding, Object dataSource,
                                 long sampleKey, short moduleIndex) {
        if (binding.scellView == null) return;

        if (binding.config.isStatic) {
            binding.scellView.setText(binding.config.staticText != null ? binding.config.staticText : "");
        } else if (binding.barHelper != null) {
            try {
                v6aViewField.set(binding.barHelper, binding.scellView);
                v6fAMethod.invoke(binding.barHelper, sampleKey, dataSource, moduleIndex);
                v6fEMethod.invoke(binding.barHelper);
            } catch (Exception e) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: bar SCell update failed for row "
                        + binding.originalRow + ": " + e);
                binding.scellView.setText("-");
            }
        } else {
            String text = readScellValue(binding.config, dataSource, sampleKey, moduleIndex);
            binding.scellView.setText(text != null ? text : "-");
        }

        binding.scellView.setVisibility(currentlyStretched ? View.VISIBLE : View.GONE);

        // When not stretched, let the PCell view fill the whole container.
        if (!currentlyStretched && binding.container != null && binding.pcellView != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) binding.pcellView.getLayoutParams();
            lp.weight = 1.0f;
            binding.pcellView.setLayoutParams(lp);
        }
    }

    private String readScellValue(SCellConfig config, Object dataSource,
                                  long sampleKey, short moduleIndex) {
        if (config.formatters == null || config.formatters.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ScellFormatter sf : config.formatters) {
            String part = readAndFormat(sf, dataSource, sampleKey, moduleIndex);
            if (part == null) {
                part = "-";
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private String readAndFormat(ScellFormatter sf, Object dataSource,
                                 long sampleKey, short moduleIndex) {
        try {
            Object rawValue = readPropertyRaw(dataSource, sf.key, moduleIndex, sampleKey);
            if (rawValue == null) return null;

            Object extracted = attrAExtractor.invoke(sf.formatter, rawValue);
            if (extracted == null) return null;

            return (String) attrAFormatter.invoke(sf.formatter, extracted);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: readAndFormat failed for " + sf.key + ": " + e);
            return null;
        }
    }

    private Object readPropertyRaw(Object dataSource, String key, int moduleIndex, long sampleKey) {
        try {
            Object property = dsGetPropertyMethod.invoke(dataSource, key, moduleIndex);
            if (property == null) return null;
            Object iterator = propIteratorMethod.invoke(property, sampleKey);
            if (iterator == null || (boolean) iteratorEndMethod.invoke(iterator)) {
                return null;
            }
            long actualKey = (long) iteratorKeyMethod.invoke(iterator);
            Object value = iteratorValueMethod.invoke(iterator);
            if (value == null && actualKey > 0) {
                // Prev-tick fallback for partially written head entries.
                iterator = propIteratorMethod.invoke(property, actualKey - 1);
                if (iterator != null && !(boolean) iteratorEndMethod.invoke(iterator)) {
                    value = iteratorValueMethod.invoke(iterator);
                }
            }
            return value;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: readPropertyRaw failed for " + key + ": " + e);
            return null;
        }
    }

    private boolean isScellPresent(Object dataSource, long sampleKey, short moduleIndex) {
        try {
            Object rawValue = readPropertyRaw(dataSource, KEY_SCELL_PCI, moduleIndex, sampleKey);
            if (rawValue == null) return false;

            Object formatter = attrAConstructorIndexed.newInstance(KEY_SCELL_PCI, SCELL_PCI_INDEX, null);
            Object extracted = attrAExtractor.invoke(formatter, rawValue);
            if (!(extracted instanceof Number)) return false;
            return ((Number) extracted).intValue() >= 0;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: SCell PCI check failed: " + e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // SCell configuration per row.
    // -------------------------------------------------------------------------
    private SCellConfig createScellConfig(int row, Object nrCell) {
        switch (row) {
            case 3:
                return SCellConfig.staticText("");
            case 4:
                return makeConfig(nrCell,
                        "NR5G::Serving_Cell::SCell::NR_SCell_Band",
                        "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL");
            case 5:
                return makeConfig(nrCell,
                        "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB",
                        "NR5G::Serving_Cell::SCell::NR_SCell_PCI");
            case 6:
                return makeConfig(nrCell, 1,
                        "NR5G::Cell_Measurements::NR_Cells_SINR");
            case 7:
                return makeConfig(nrCell, 1,
                        "NR5G::Cell_Measurements::NR_Cells_RSRP");
            case 8:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_BLER_DL");
            case 9:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Antenna_Rx");
            case 10:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_Rank_DL");
            case 11:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_RB_Num_Average_DL");
            case 12:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_MCS_DL");
            case 13:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_CQI_DL");
            case 14:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_Modulation_DL");
            case 15:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Layers_DL");
            case 16:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_Physical_Throughput_DL");
            case 17:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_Throughput_DL");
            case 18:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_RLC_Throughput_DL");
            case 19:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_PDCP_Throughput_DL");
            case 20:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_256QAM_DL");
            case 21:
                return makeConfig(nrCell, 0,
                        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_64QAM_DL");
            default:
                return null;
        }
    }

    private SCellConfig makeConfig(Object nrCell, int index, String key) {
        Object pcellFormatter = getPcellFormatter(nrCell, 0);
        if (pcellFormatter == null) {
            return makeFallbackConfig(index, key);
        }
        Object scellFormatter = cloneFormatter(pcellFormatter, key, index);
        List<ScellFormatter> formatters = new ArrayList<>();
        if (scellFormatter != null) {
            formatters.add(new ScellFormatter(key, scellFormatter));
        }
        return new SCellConfig(false, null, formatters);
    }

    private SCellConfig makeConfig(Object nrCell, String... keys) {
        List<ScellFormatter> formatters = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            Object pcellFormatter = getPcellFormatter(nrCell, i);
            if (pcellFormatter == null) continue;
            Object scellFormatter = cloneFormatter(pcellFormatter, keys[i], 0);
            if (scellFormatter != null) {
                formatters.add(new ScellFormatter(keys[i], scellFormatter));
            }
        }
        return new SCellConfig(false, null, formatters);
    }

    private SCellConfig makeFallbackConfig(int index, String key) {
        List<ScellFormatter> formatters = new ArrayList<>();
        try {
            Object formatter = attrAConstructorKeyOnly.newInstance(key);
            attrAKeyFieldSetter.setInt(formatter, index);
            formatters.add(new ScellFormatter(key, formatter));
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot build fallback formatter for " + key + ": " + e);
        }
        return new SCellConfig(false, null, formatters);
    }

    private Object getPcellFormatter(Object nrCell, int index) {
        try {
            String className = nrCell.getClass().getName();
            if (V6_G_CLASS.equals(className)) {
                List<?> formatters = (List<?>) v6gFormattersField.get(nrCell);
                if (formatters != null && index < formatters.size()) {
                    return formatters.get(index);
                }
            } else if (V6_F_CLASS.equals(className)) {
                return v6fFormatterField.get(nrCell);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Object cloneFormatter(Object pcellFormatter, String key, int index) {
        try {
            Class<?> clazz = pcellFormatter.getClass();
            String format = getFormatString(pcellFormatter);
            Object formatter;
            if (ATTR_A_CLASS.equals(clazz.getName()) || "com.qtrun.sys.b".equals(clazz.getName())) {
                formatter = attrAConstructorIndexed.newInstance(key, index, format);
            } else {
                Constructor<?> ctor = clazz.getConstructor(String.class);
                formatter = ctor.newInstance(key);
            }
            attrAKeyFieldSetter.setInt(formatter, index);
            return formatter;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot clone formatter for " + key + ": " + e);
            try {
                return attrAConstructorIndexed.newInstance(key, index, getFormatString(pcellFormatter));
            } catch (Exception e2) {
                try {
                    return attrAConstructorKeyOnly.newInstance(key);
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private String getFormatString(Object formatter) {
        try {
            Field f = findField(formatter.getClass(), FORMATTER_FORMAT_FIELD);
            if (f != null) {
                return (String) f.get(formatter);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private Object createBarHelper(Object pcellCell, SCellConfig config) {
        if (config.formatters == null || config.formatters.isEmpty()) {
            return null;
        }
        try {
            Object helper = v6fClass.getConstructor().newInstance();
            v6fSimpleField.setBoolean(helper, v6fSimpleField.getBoolean(pcellCell));
            v6fColorField.setInt(helper, v6fColorField.getInt(pcellCell));
            v6fMaxField.setFloat(helper, v6fMaxField.getFloat(pcellCell));
            v6fFormatterField.set(helper, config.formatters.get(0).formatter);
            return helper;
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot create bar helper: " + e);
            return null;
        }
    }

    private void patchPcellKeys(Object nrCell) {
        try {
            String className = nrCell.getClass().getName();
            List<Object> formatters = new ArrayList<>();
            if (V6_G_CLASS.equals(className)) {
                List<?> list = (List<?>) v6gFormattersField.get(nrCell);
                if (list != null) formatters.addAll(list);
            } else if (V6_F_CLASS.equals(className)) {
                Object fmt = v6fFormatterField.get(nrCell);
                if (fmt != null) formatters.add(fmt);
            } else {
                return;
            }
            for (int i = 0; i < formatters.size(); i++) {
                Object fmt = formatters.get(i);
                String key = (String) getFieldDeep(fmt, FORMATTER_KEY_FIELD);
                String override = pcellKeyOverrides.get(key);
                if (override == null) continue;
                int index = (int) getFieldDeep(fmt, FORMATTER_INDEX_FIELD);
                Object newFmt = cloneFormatter(fmt, override, index);
                if (newFmt == null) continue;
                if (V6_G_CLASS.equals(className)) {
                    List<Object> list = (List<Object>) v6gFormattersField.get(nrCell);
                    if (list != null) list.set(i, newFmt);
                } else {
                    v6fFormatterField.set(nrCell, newFmt);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: patchPcellKeys failed: " + e);
        }
    }

    private static Object getFieldDeep(Object obj, String name) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private String getPcellFormat(Object nrCell, int index) {
        try {
            String className = nrCell.getClass().getName();
            if (V6_G_CLASS.equals(className)) {
                List<?> formatters = (List<?>) v6gFormattersField.get(nrCell);
                if (formatters != null && index < formatters.size()) {
                    Object fmt = formatters.get(index);
                    if (fmt != null) {
                        Field fmtStringField = fmt.getClass().getSuperclass().getDeclaredField("b");
                        fmtStringField.setAccessible(true);
                        return (String) fmtStringField.get(fmt);
                    }
                }
            } else if (V6_F_CLASS.equals(className)) {
                Object fmt = v6fFormatterField.get(nrCell);
                if (fmt != null) {
                    Field fmtStringField = fmt.getClass().getSuperclass().getDeclaredField("b");
                    fmtStringField.setAccessible(true);
                    return (String) fmtStringField.get(fmt);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------
    private static boolean isDataRow(int row) {
        return row >= FIRST_DATA_ROW && row <= LAST_DATA_ROW;
    }

    private static boolean approxEquals(float a, float b, float tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static float getFloat(Object obj, Field field) {
        try {
            return field.getFloat(obj);
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    private static void setField(Object obj, Field field, Object value) {
        try {
            field.set(obj, value);
        } catch (Exception ignored) {
        }
    }

    private Object getFieldValue(Object obj, String className, String fieldName) {
        if (className == null) return null;
        try {
            Class<?> clazz = loader.loadClass(className);
            // getField finds public fields including inherited ones.
            Field f = clazz.getField(fieldName);
            return f.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Inner classes.
    // -------------------------------------------------------------------------
    private static class RowCells {
        Object label;
        Object lte;
        Object nr;
    }

    private static class RowBinding {
        final int originalRow;
        final Object labelCell;
        final Object lteCell;
        final Object nrCell;
        final SCellConfig config;

        boolean wrapped;
        LinearLayout container;
        TextView pcellView;
        TextView scellView;
        Object barHelper;

        RowBinding(int originalRow, Object labelCell, Object lteCell, Object nrCell, SCellConfig config) {
            this.originalRow = originalRow;
            this.labelCell = labelCell;
            this.lteCell = lteCell;
            this.nrCell = nrCell;
            this.config = config;
        }
    }

    private static class SCellConfig {
        final boolean isStatic;
        final String staticText;
        final List<ScellFormatter> formatters;

        SCellConfig(boolean isStatic, String staticText, List<ScellFormatter> formatters) {
            this.isStatic = isStatic;
            this.staticText = staticText;
            this.formatters = formatters;
        }

        static SCellConfig staticText(String text) {
            return new SCellConfig(true, text, null);
        }
    }

    private static class ScellFormatter {
        final String key;
        final Object formatter;

        ScellFormatter(String key, Object formatter) {
            this.key = key;
            this.formatter = formatter;
        }
    }
}
