package com.nsgmod.band;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
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

    private static final String TAG = "NSGBandHook:Scell";

    private static final String TARGET_FRAGMENT = "g8.h";
    private static final String K2A_CLASS = "k2.a";
    private static final String V6_A_CLASS = "v6.a";
    private static final String V6_B_CLASS = "v6.b";
    private static final String V6_D_CLASS = "v6.d";
    private static final String V6_E_CLASS = "v6.e";
    private static final String V6_F_CLASS = "v6.f";
    private static final String V6_G_CLASS = "v6.g";
    private static final String DATA_SOURCE_CLASS = "com.qtrun.sys.DataSource";
    private static final String ATTR_A_CLASS = "com.qtrun.sys.a";
    private static final String FONT_TEXT_VIEW_CLASS = "com.qtrun.widget.textview.FontTextView";
    private static final String PROGRESS_TEXT_VIEW_CLASS = "com.qtrun.widget.textview.ProgressTextView";

    private static final String KEY_SCELL_PCI = "NR5G::Serving_Cell::SCell::NR_SCell_PCI";

    private static final float NR_COLUMN_X = 65.0f;
    private static final float LTE_COLUMN_X = 30.0f;
    private static final float LABEL_COLUMN_X = 0.0f;
    private static final float COLUMN_TOLERANCE = 4.0f;
    private static final int FIRST_DATA_ROW = 3;
    private static final int LAST_DATA_ROW = 21;
    private static final int TECHNOLOGY_ROW = FIRST_DATA_ROW;
    private static final int TURQUOISE = 0xFF00BCD4;

    // How many logical row units a stacked PCell+SCell row occupies.
    // The NR-SA CA Matrix DL (h8.b, f5066a0 == 3) uses two single-height rows
    // (total 2.0) for the same stack, with the PCell/left column spanning the
    // full 2.0 rows and the SCell/right column split into two 1.0-row cells.
    private static final float STRETCH_FACTOR = 2.0f;
    // Offset for the first row that actually stretches. Row 3 (Technology) is
    // intentionally single-height, so the first stretched row is row 4. Using
    // FIRST_DATA_ROW + 1 maps row 4 -> y=4, row 21 -> y=38, etc., with no gap
    // below the Technology row.
    private static final float STRETCH_OFFSET = (STRETCH_FACTOR - 1.0f) * (FIRST_DATA_ROW + 1);

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean reflectionReady = false;

    /** Runtime class for g8.h (qtrun) / p6.h (gplay). Used for exact-fragment checks. */
    private Class<?> targetFragmentClass;

    private Object unsafe;
    private Method unsafeObjectFieldOffset;
    private Method unsafeAllocateInstance;
    private Method unsafeGetObject;
    private Method unsafeGetInt;
    private Method unsafeGetFloat;
    private Method unsafeGetBoolean;
    private Method unsafeGetLong;
    private Method unsafeGetDouble;
    private Method unsafeGetShort;
    private Method unsafeGetByte;
    private Method unsafeGetChar;
    private Method unsafePutObject;
    private Method unsafePutInt;
    private Method unsafePutFloat;
    private Method unsafePutBoolean;
    private Method unsafePutLong;
    private Method unsafePutDouble;
    private Method unsafePutShort;
    private Method unsafePutByte;
    private Method unsafePutChar;
    private long attrKeyOffset;
    private long attrFormatOffset;
    private long attrIndexOffset;
    private long attrPropertyOffset;

    private Field k2aCellsField;        // k2.a.d -> ArrayList<v6.a>
    private Field v6aViewField;         // v6.a.a -> View
    private Field v6aRowField;          // v6.a.b -> float
    private Field v6aHeightField;       // v6.a.c -> float
    private Field v6aXField;            // v6.a.d -> float
    private Field v6dCellsField;        // v6.d.a -> ArrayList<v6.a>

    private Field v6gFormattersField;   // v6.g.f -> ArrayList<com.qtrun.sys.b>
    private Field v6gSeparatorField;    // v6.g.h -> String
    private Field v6gColorField;        // v6.g.i -> int
    private Field v6gAltColorField;     // v6.g.j -> int
    private Field v6gGravityField;      // v6.g.k -> int

    private Field v6fValueField;        // v6.f.f -> Object
    private Field v6fFormatterField;    // v6.f.g -> com.qtrun.sys.b
    private Field v6fManualField;       // v6.f.i -> boolean
    private Field v6fColorField;        // v6.f.j -> int
    private Field v6fMaxField;          // v6.f.k -> float

    private Field v6eTextField;         // v6.e.f -> String

    private Method v6aUpdateMethod;     // v6.a.a(long, DataSource, short)
    private Method v6aResetMethod;      // v6.a.b()
    private Class<?> v6eClass;
    private Class<?> v6fClass;
    private Class<?> v6gClass;

    private Method v6eRenderMethod;     // v6.e.e()
    private Method v6fRenderMethod;     // v6.f.e()
    private Method v6gRenderMethod;     // v6.g.e()

    private Constructor<?> fontTextViewCtor;
    private Constructor<?> progressTextViewCtor;

    // Bindings for every row that has an NR column cell.
    private final List<RowBinding> rowBindings = new ArrayList<>();
    // Original geometry for every cell we might stretch, so we can restore it.
    private final Map<Object, float[]> originalGeometry = new HashMap<>();
    // Tracks whether the grid is currently in stretched mode.
    private boolean currentlyStretched = false;

    // Most recent data-update parameters, kept so wrap/unwrap can re-render the
    // PCell cell after dynamically swapping its formatters.
    private long lastSampleKey;
    private short lastModuleIndex;
    private Object lastDataSource;

    public NrNsaScellDualConnectivityHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
Class<?> k2aClass = ClassMapping.loadClass(K2A_CLASS, loader);
            Class<?> v6aClass = ClassMapping.loadClass(V6_A_CLASS, loader);
            Class<?> v6dClass = ClassMapping.loadClass(V6_D_CLASS, loader);
            v6eClass = ClassMapping.loadClass(V6_E_CLASS, loader);
            v6fClass = ClassMapping.loadClass(V6_F_CLASS, loader);
            v6gClass = ClassMapping.loadClass(V6_G_CLASS, loader);
            Class<?> attrAClass = ClassMapping.loadClass(ATTR_A_CLASS, loader);
            Class<?> dsClass = ClassMapping.loadClass(DATA_SOURCE_CLASS, loader);
            Class<?> fontTextViewClass = ClassMapping.loadClass(FONT_TEXT_VIEW_CLASS, loader);
            Class<?> progressTextViewClass = ClassMapping.loadClass(PROGRESS_TEXT_VIEW_CLASS, loader);
if (k2aClass == null || v6aClass == null || v6dClass == null || v6eClass == null
                    || v6fClass == null || v6gClass == null || attrAClass == null || dsClass == null
                    || fontTextViewClass == null || progressTextViewClass == null) {
                Log.i(TAG, "NrNsaScellDualConnectivityHook: essential class missing, skipping");
                return;
            }

            targetFragmentClass = ClassMapping.loadClass(TARGET_FRAGMENT, loader);
if (targetFragmentClass == null) {
                Log.i(TAG, "NrNsaScellDualConnectivityHook: target fragment class missing, skipping");
                return;
            }

            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);

            Class<?> fieldClass = Field.class;
            Class<?> objectClass = Object.class;
            Class<?> intClass = int.class;
            Class<?> longClass = long.class;
            Class<?> floatClass = float.class;
            Class<?> booleanClass = boolean.class;
            Class<?> doubleClass = double.class;
            Class<?> shortClass = short.class;
            Class<?> byteClass = byte.class;
            Class<?> charClass = char.class;
            Class<?> classClass = Class.class;

            unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", fieldClass);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", classClass);
            unsafeGetObject = unsafeClass.getMethod("getObject", objectClass, longClass);
            unsafeGetInt = unsafeClass.getMethod("getInt", objectClass, longClass);
            unsafeGetFloat = unsafeClass.getMethod("getFloat", objectClass, longClass);
            unsafeGetBoolean = unsafeClass.getMethod("getBoolean", objectClass, longClass);
            unsafeGetLong = unsafeClass.getMethod("getLong", objectClass, longClass);
            unsafeGetDouble = unsafeClass.getMethod("getDouble", objectClass, longClass);
            unsafeGetShort = unsafeClass.getMethod("getShort", objectClass, longClass);
            unsafeGetByte = unsafeClass.getMethod("getByte", objectClass, longClass);
            unsafeGetChar = unsafeClass.getMethod("getChar", objectClass, longClass);
            unsafePutObject = unsafeClass.getMethod("putObject", objectClass, longClass, objectClass);
            unsafePutInt = unsafeClass.getMethod("putInt", objectClass, longClass, intClass);
            unsafePutFloat = unsafeClass.getMethod("putFloat", objectClass, longClass, floatClass);
            unsafePutBoolean = unsafeClass.getMethod("putBoolean", objectClass, longClass, booleanClass);
            unsafePutLong = unsafeClass.getMethod("putLong", objectClass, longClass, longClass);
            unsafePutDouble = unsafeClass.getMethod("putDouble", objectClass, longClass, doubleClass);
            unsafePutShort = unsafeClass.getMethod("putShort", objectClass, longClass, shortClass);
            unsafePutByte = unsafeClass.getMethod("putByte", objectClass, longClass, byteClass);
            unsafePutChar = unsafeClass.getMethod("putChar", objectClass, longClass, charClass);

            String attrKeyName = ClassMapping.runtimeFieldName("com.qtrun.sys.a", "a", loader);
            String attrFormatName = ClassMapping.runtimeFieldName("com.qtrun.sys.a", "b", loader);
            String attrIndexName = ClassMapping.runtimeFieldName("com.qtrun.sys.a", "c", loader);
            String attrPropertyName = ClassMapping.runtimeFieldName("com.qtrun.sys.a", "d", loader);
attrKeyOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrAClass.getDeclaredField(attrKeyName));
            attrFormatOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrAClass.getDeclaredField(attrFormatName));
            attrIndexOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrAClass.getDeclaredField(attrIndexName));
            attrPropertyOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrAClass.getDeclaredField(attrPropertyName));

            String k2aCellsName = ClassMapping.runtimeFieldName(K2A_CLASS, "d", loader);
            k2aCellsField = k2aClass.getDeclaredField(k2aCellsName);
            k2aCellsField.setAccessible(true);
String v6aViewName = ClassMapping.runtimeFieldName(V6_A_CLASS, "a", loader);
            String v6aRowName = ClassMapping.runtimeFieldName(V6_A_CLASS, "b", loader);
            String v6aHeightName = ClassMapping.runtimeFieldName(V6_A_CLASS, "c", loader);
            String v6aXName = ClassMapping.runtimeFieldName(V6_A_CLASS, "d", loader);
            v6aViewField = v6aClass.getDeclaredField(v6aViewName);
            v6aViewField.setAccessible(true);
            v6aRowField = v6aClass.getDeclaredField(v6aRowName);
            v6aRowField.setAccessible(true);
            v6aHeightField = v6aClass.getDeclaredField(v6aHeightName);
            v6aHeightField.setAccessible(true);
            v6aXField = v6aClass.getDeclaredField(v6aXName);
            v6aXField.setAccessible(true);
String v6dCellsFieldName = ClassMapping.runtimeFieldName(V6_D_CLASS, "a", loader);
            v6dCellsField = v6dClass.getDeclaredField(v6dCellsFieldName);
            v6dCellsField.setAccessible(true);
String v6gFormattersName = ClassMapping.runtimeFieldName(V6_G_CLASS, "f", loader);
            String v6gSeparatorName = ClassMapping.runtimeFieldName(V6_G_CLASS, "h", loader);
            String v6gColorName = ClassMapping.runtimeFieldName(V6_G_CLASS, "i", loader);
            String v6gAltColorName = ClassMapping.runtimeFieldName(V6_G_CLASS, "j", loader);
            String v6gGravityName = ClassMapping.runtimeFieldName(V6_G_CLASS, "k", loader);
            v6gFormattersField = v6gClass.getDeclaredField(v6gFormattersName);
            v6gFormattersField.setAccessible(true);
            v6gSeparatorField = v6gClass.getDeclaredField(v6gSeparatorName);
            v6gSeparatorField.setAccessible(true);
            v6gColorField = v6gClass.getDeclaredField(v6gColorName);
            v6gColorField.setAccessible(true);
            v6gAltColorField = v6gClass.getDeclaredField(v6gAltColorName);
            v6gAltColorField.setAccessible(true);
            v6gGravityField = v6gClass.getDeclaredField(v6gGravityName);
            v6gGravityField.setAccessible(true);
String v6fValueName = ClassMapping.runtimeFieldName(V6_F_CLASS, "f", loader);
            String v6fFormatterName = ClassMapping.runtimeFieldName(V6_F_CLASS, "g", loader);
            String v6fManualName = ClassMapping.runtimeFieldName(V6_F_CLASS, "i", loader);
            String v6fColorName = ClassMapping.runtimeFieldName(V6_F_CLASS, "j", loader);
            String v6fMaxName = ClassMapping.runtimeFieldName(V6_F_CLASS, "k", loader);
            v6fValueField = v6fClass.getDeclaredField(v6fValueName);
            v6fValueField.setAccessible(true);
            v6fFormatterField = v6fClass.getDeclaredField(v6fFormatterName);
            v6fFormatterField.setAccessible(true);
            v6fManualField = v6fClass.getDeclaredField(v6fManualName);
            v6fManualField.setAccessible(true);
            v6fColorField = v6fClass.getDeclaredField(v6fColorName);
            v6fColorField.setAccessible(true);
            v6fMaxField = v6fClass.getDeclaredField(v6fMaxName);
            v6fMaxField.setAccessible(true);
String v6eTextName = ClassMapping.runtimeFieldName(V6_E_CLASS, "f", loader);
            v6eTextField = v6eClass.getDeclaredField(v6eTextName);
            v6eTextField.setAccessible(true);
v6aUpdateMethod = ClassMapping.getMethod(v6aClass, V6_A_CLASS, "a", loader,
                    long.class, dsClass, short.class);
            v6aResetMethod = ClassMapping.getMethod(v6aClass, V6_A_CLASS, "b", loader);
            v6eRenderMethod = ClassMapping.getMethod(v6eClass, V6_E_CLASS, "e", loader);
            v6fRenderMethod = ClassMapping.getMethod(v6fClass, V6_F_CLASS, "e", loader);
            v6gRenderMethod = ClassMapping.getMethod(v6gClass, V6_G_CLASS, "e", loader);

            fontTextViewCtor = fontTextViewClass.getConstructor(Context.class, AttributeSet.class);
            fontTextViewCtor.setAccessible(true);
            progressTextViewCtor = progressTextViewClass.getConstructor(Context.class, AttributeSet.class);
            progressTextViewCtor.setAccessible(true);

            reflectionReady = true;
            Log.i(TAG, "NrNsaScellDualConnectivityHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: reflection init failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: install skipped, reflection not ready");
            return;
        }
        String targetName = ClassMapping.runtimeName(TARGET_FRAGMENT, loader);
        if (FlavorDetector.isGplay(loader)) {
            Log.i(TAG, "NrNsaScellDualConnectivityHook: enabling gplay path, target=" + targetName);
        } else {
            Log.i(TAG, "NrNsaScellDualConnectivityHook: enabling qtrun path, target=" + targetName);
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

    // -------------------------------------------------------------------------
    // Hook 1: v6.b.I(...) — rebuild bindings every time the fragment view is
    // created. v6.b.I() is the real onCreateView; g8.h inherits it.
    // On first creation it calls g8.h.l0(); on later recreations (e.g. swipe
    // back in the view pager) l0() is skipped and only I() runs, so hooking
    // l0() alone leaves stale view references in rowBindings.
    // -------------------------------------------------------------------------
    private void hookI() {
        try {
            Class<?> v6bClass = ClassMapping.loadClass(V6_B_CLASS, loader);
            Method iMethod = ClassMapping.getDeclaredMethod(v6bClass, V6_B_CLASS, "I", loader,
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
                        boolean match = fragment != null && fragment.getClass() == targetFragmentClass;
if (match) {
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

            RowBinding binding = new RowBinding(row, rc.label, rc.lte, rc.nr);
            preparePcellFormatters(binding, rc.nr, row);
            rowBindings.add(binding);
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

    @SuppressWarnings("unchecked")
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
            Log.w(TAG, "wrapAllRows: gridCells null");
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
            if (binding.originalRow == TECHNOLOGY_ROW) continue;

            int index = gridCells.indexOf(binding.nrCell);
            View originalView = gridGroup.getChildAt(index);
            TextView pcellView = (TextView) originalView;
            Context ctx = pcellView.getContext();

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.VERTICAL);

            TextView scellView = createScellView(ctx, pcellView);
            scellView.setGravity(android.view.Gravity.CENTER);
            scellView.setMaxLines(1);
            scellView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pcellView.getTextSize());
            scellView.setTypeface(pcellView.getTypeface());
            scellView.setTextColor(pcellView.getTextColors());
            scellView.setText("-");
            if (binding.nrCell.getClass() == v6gClass) {
                scellView.setTextColor(TURQUOISE);
}

            gridGroup.removeViewAt(index);
            gridGroup.addView(container, index);
// Stack PCell and SCell sub-views with a small transparent gap
            // between them, matching the 2 dp row distance used by the grid
            // (v6.d.f8115g). This mirrors the NR-SA CA Matrix DL 3x NR-CA layout
            // where PCell and SCell values are separated by an empty gap, not a
            // visible divider line.
            LinearLayout.LayoutParams pcellLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            LinearLayout.LayoutParams scellLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);

            int gapPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.0f, ctx.getResources().getDisplayMetrics());
            View gap = new View(ctx);
            LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gapPx);

            container.addView(pcellView, pcellLp);
            container.addView(gap, gapLp);
            container.addView(scellView, scellLp);

            setField(binding.nrCell, v6aViewField, container);

            binding.container = container;
            binding.pcellView = pcellView;
            binding.scellView = scellView;
            binding.scellCell = createScellCell(binding, scellView);
            binding.wrapped = true;
// Now that the SCell is present, make the PCell cell use the
            // PCell-specific formatter keys so PCell and SCell values are fetched
            // independently. Refresh the cell with the current sample so the
            // display updates immediately.
            applyPcellFormatters(binding, true);
        }
return true;
    }

    private TextView createScellView(Context ctx, TextView pcellView) {
        try {
            Class<?> pcellClass = pcellView.getClass();
            if (pcellClass == progressTextViewCtor.getDeclaringClass()) {
return (TextView) progressTextViewCtor.newInstance(ctx, (AttributeSet) null);
            }
            if (pcellClass == fontTextViewCtor.getDeclaringClass()) {
return (TextView) fontTextViewCtor.newInstance(ctx, (AttributeSet) null);
            }
} catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: createScellView failed, using fallback: " + e);
        }
        return new TextView(ctx);
    }

    @SuppressWarnings("unchecked")
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

            // SCell is gone: revert the PCell cell to its original aggregated
            // formatter(s) and refresh so PCell-only data is displayed again.
            applyPcellFormatters(binding, false);
            setField(binding.nrCell, v6aViewField, binding.pcellView);

            binding.container = null;
            binding.pcellView = null;
            binding.scellView = null;
            binding.scellCell = null;
            binding.wrapped = false;
        }
    }

    // -------------------------------------------------------------------------
    // Hook 2: g8.h.b(DataSource, long, short, Object) — data updates & stretch.
    // -------------------------------------------------------------------------
    private void hookB() {
        try {
            Class<?> g8hClass = ClassMapping.loadClass(TARGET_FRAGMENT, loader);
            Class<?> dsClass = ClassMapping.loadClass(DATA_SOURCE_CLASS, loader);
            if (g8hClass == null || dsClass == null) {
                Log.i(TAG, "NrNsaScellDualConnectivityHook: target fragment or DataSource missing, skipping hookB");
                return;
            }
            Method bMethod = ClassMapping.getMethod(g8hClass, TARGET_FRAGMENT, "b", loader,
                    dsClass, long.class, short.class, Object.class);
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
        lastDataSource = dataSource;
        lastSampleKey = sampleKey;
        lastModuleIndex = moduleIndex;

        boolean scellPresent = isScellPresent(dataSource, sampleKey, moduleIndex);
boolean layoutNeeded = false;

        if (scellPresent) {
            if (!anyWrapped()) {
                if (!wrapAllRows(fragment)) return;
                layoutNeeded = true;
            }
            // Always re-apply stretched geometry while an SCell is present.
            stretchGrid(true);
            currentlyStretched = true;
            layoutNeeded = true;
        } else {
            if (anyWrapped() || currentlyStretched) {
                stretchGrid(false);
                unwrapAllRows(fragment);
                currentlyStretched = false;
                layoutNeeded = true;
            }
        }

        for (RowBinding binding : rowBindings) {
            updateScellCell(binding, sampleKey, moduleIndex);
        }

        if (layoutNeeded) {
            requestGridLayout(fragment);
        }
    }

    private void stretchGrid(boolean stretch) {
for (RowBinding binding : rowBindings) {
            int row = binding.originalRow;

            if (row == TECHNOLOGY_ROW) {
                // Technology row stays a single-height row in every column.
                setGeometry(binding.nrCell, row, 1.0f);
                if (binding.lteCell != null) setGeometry(binding.lteCell, row, 1.0f);
                if (binding.labelCell != null) setGeometry(binding.labelCell, row, 1.0f);
                continue;
            }

            float newRow = stretch ? (STRETCH_FACTOR * row - STRETCH_OFFSET) : row;

            if (stretch) {
                // NR column container spans the full 2.0-row logical row.
                setGeometry(binding.nrCell, newRow, 2.0f);
                // Label column mirrors the NR column container height.
                if (binding.labelCell != null) {
                    setGeometry(binding.labelCell, newRow, 2.0f);
                }
                // LTE/PCell column: text cells span the full 2.0 rows; progress-bar
                // cells (MCS, CQI, 256Q Util., 64Q Util.) are shorter and anchored
                // lower, matching h8.b's o0() geometry in 3x NR-CA mode.
                if (binding.lteCell != null) {
                    if (binding.lteCell.getClass() == v6fClass) {
setGeometry(binding.lteCell, newRow + 0.3f, 1.4f);
                    } else {
                        setGeometry(binding.lteCell, newRow, 2.0f);
                    }
                }
            } else {
                // Restore every bound cell to its original single-row geometry.
                setGeometry(binding.nrCell, row, 1.0f);
                if (binding.lteCell != null) setGeometry(binding.lteCell, row, 1.0f);
                if (binding.labelCell != null) setGeometry(binding.labelCell, row, 1.0f);
            }
        }

        // In case there are any cells below LAST_DATA_ROW (there should not be in g8.h),
        // shift them by the additional height each stretched row consumes. The
        // Technology row is single-height, so the remaining 18 data rows are stretched:
        // 1*1 + 18*2 = 37 grid units vs the original 19, for a total shift of 18.
        int dataRowCount = LAST_DATA_ROW - FIRST_DATA_ROW + 1;
        float stretchOffset = (STRETCH_FACTOR - 1.0f) * (dataRowCount - 1);
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
    // Hook 3: v6.g/f/e.e() — PCell view updates after f8100a was replaced.
    // -------------------------------------------------------------------------
    private void hookV6eE() {
        hookV6EMethod(V6_E_CLASS, v6eRenderMethod);
    }

    private void hookV6fE() {
        hookV6EMethod(V6_F_CLASS, v6fRenderMethod);
    }

    private void hookV6gE() {
        hookV6EMethod(V6_G_CLASS, v6gRenderMethod);
    }

    private void hookV6EMethod(String logicalClassName, final Method cachedRenderMethod) {
        try {
            Class<?> cls = ClassMapping.loadClass(logicalClassName, loader);
            Method eMethod = ClassMapping.getDeclaredMethod(cls, logicalClassName, "e", loader);
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
                        return chain.proceed();
                    } finally {
                        v6aViewField.set(cell, container);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: hookV6EMethod(" + logicalClassName + ") failed: " + e);
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
    // SCell helper cell creation and updates.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private ScellCell createScellCell(RowBinding binding, TextView scellView) {
        try {
            Object nrCell = binding.nrCell;
            Class<?> nrClass = nrCell.getClass();
if (nrClass == v6eClass) {
                // Label/technology row: keep the SCell sub-view empty.
                return new ScellCell(true, null, null);
            }

            if (nrClass == v6gClass) {
                Object helper = nrCell.getClass().getDeclaredConstructor().newInstance();

                List<Object> pcellFormatters = (List<Object>) v6gFormattersField.get(nrCell);
                KeyIndex[] scellKeys = getScellKeyIndices(binding.originalRow, pcellFormatters.size());

                List<Object> scellFormatters = new ArrayList<>();
                for (int i = 0; i < scellKeys.length && i < pcellFormatters.size(); i++) {
                    scellFormatters.add(cloneFormatter(
                            pcellFormatters.get(i), scellKeys[i].key, scellKeys[i].index));
                }

                v6gFormattersField.set(helper, scellFormatters);
                v6gSeparatorField.set(helper, v6gSeparatorField.get(nrCell));
                v6gColorField.set(helper, TURQUOISE);
                v6gAltColorField.set(helper, v6gAltColorField.get(nrCell));
                v6gGravityField.set(helper, v6gGravityField.get(nrCell));
                v6aViewField.set(helper, scellView);

                return new ScellCell(false, helper, v6gRenderMethod);
            }

            if (nrClass == v6fClass) {
                Object pcellFormatter = v6fFormatterField.get(nrCell);
                KeyIndex[] scellKeys = getScellKeyIndices(binding.originalRow, 1);
                Object scellFormatter = cloneFormatter(
                        pcellFormatter, scellKeys[0].key, scellKeys[0].index);

                Object helper = nrCell.getClass().getDeclaredConstructor().newInstance();
                v6fValueField.set(helper, null);
                v6fFormatterField.set(helper, scellFormatter);
                v6fManualField.set(helper, v6fManualField.get(nrCell));
                v6fColorField.set(helper, v6fColorField.get(nrCell));
                v6fMaxField.set(helper, v6fMaxField.get(nrCell));
                v6aViewField.set(helper, scellView);

                return new ScellCell(false, helper, v6fRenderMethod);
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: createScellCell failed for row " + binding.originalRow + ": " + e);
        }
        return null;
    }

    private void updateScellCell(RowBinding binding, long sampleKey, short moduleIndex) {
        if (!binding.wrapped || binding.scellView == null) return;

        if (binding.scellCell == null) {
            binding.scellView.setText("-");
            binding.scellView.setVisibility(currentlyStretched ? View.VISIBLE : View.GONE);
            return;
        }

        if (binding.scellCell.isStatic) {
            // Label rows show nothing in the SCell sub-row.
            binding.scellView.setText("");
        } else {
            try {
                v6aUpdateMethod.invoke(binding.scellCell.helper, sampleKey, null, moduleIndex);
                binding.scellCell.renderMethod.invoke(binding.scellCell.helper);
            } catch (Exception e) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: updateScellCell failed for row " + binding.originalRow + ": " + e);
            }
        }

        binding.scellView.setVisibility(currentlyStretched ? View.VISIBLE : View.GONE);

        if (!currentlyStretched && binding.container != null && binding.pcellView != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) binding.pcellView.getLayoutParams();
            lp.weight = 1.0f;
            binding.pcellView.setLayoutParams(lp);
        }
    }

    // -------------------------------------------------------------------------
    // Formatter cloning via Unsafe (preserves subclass fields).
    // -------------------------------------------------------------------------
    private Object cloneFormatter(Object original, String newKey, int newIndex) throws Exception {
        Class<?> clazz = original.getClass();
        Object clone = unsafeAllocateInstance.invoke(unsafe, clazz);
        copyFields(original, clone, clazz);
        unsafePutObject.invoke(unsafe, clone, attrKeyOffset, newKey);
        unsafePutInt.invoke(unsafe, clone, attrIndexOffset, newIndex);
        unsafePutObject.invoke(unsafe, clone, attrPropertyOffset, null);
        return clone;
    }

    private void copyFields(Object src, Object dst, Class<?> clazz) {
        if (clazz == null || clazz == Object.class) return;
        copyFields(src, dst, clazz.getSuperclass());
        for (Field f : clazz.getDeclaredFields()) {
            long offset;
            try {
                offset = (Long) unsafeObjectFieldOffset.invoke(unsafe, f);
            } catch (Exception e) {
                continue;
            }
            Class<?> type = f.getType();
            try {
                if (type == int.class) {
                    unsafePutInt.invoke(unsafe, dst, offset, unsafeGetInt.invoke(unsafe, src, offset));
                } else if (type == float.class) {
                    unsafePutFloat.invoke(unsafe, dst, offset, unsafeGetFloat.invoke(unsafe, src, offset));
                } else if (type == boolean.class) {
                    unsafePutBoolean.invoke(unsafe, dst, offset, unsafeGetBoolean.invoke(unsafe, src, offset));
                } else if (type == long.class) {
                    unsafePutLong.invoke(unsafe, dst, offset, unsafeGetLong.invoke(unsafe, src, offset));
                } else if (type == double.class) {
                    unsafePutDouble.invoke(unsafe, dst, offset, unsafeGetDouble.invoke(unsafe, src, offset));
                } else if (type == short.class) {
                    unsafePutShort.invoke(unsafe, dst, offset, unsafeGetShort.invoke(unsafe, src, offset));
                } else if (type == byte.class) {
                    unsafePutByte.invoke(unsafe, dst, offset, unsafeGetByte.invoke(unsafe, src, offset));
                } else if (type == char.class) {
                    unsafePutChar.invoke(unsafe, dst, offset, unsafeGetChar.invoke(unsafe, src, offset));
                } else {
                    unsafePutObject.invoke(unsafe, dst, offset, unsafeGetObject.invoke(unsafe, src, offset));
                }
            } catch (Exception ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // PCell key override: keep both the original aggregated formatter(s) and
    // the PCell-specific clone(s) in the RowBinding, then swap dynamically when
    // the SCell appears or disappears. Swapping at build time permanently made
    // the PCell cell point to PCell::* keys, which are not populated (or have
    // stale Properties) after SCell de-aggregation, causing '-' for some rows.
    // RLC/PDCP throughput (rows 18/19) keep the stock NR-NSA aggregated
    // formatter because the PCell::* throughput keys are unpopulated in NR-NSA.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void preparePcellFormatters(RowBinding binding, Object nrCell, int row) {
        // RLC and PDCP throughput rows keep the stock aggregated NR-NSA
        // formatter; the PCell-specific keys are not populated in NR-NSA mode.
        if (row == 18 || row == 19) return;

        try {
            Class<?> nrClass = nrCell.getClass();
            String[] pcellKeys = getPcellKeys(row);
            if (pcellKeys == null || pcellKeys.length == 0) {
return;
            }
if (nrClass == v6gClass) {
                List<Object> formatters = (List<Object>) v6gFormattersField.get(nrCell);
                if (formatters == null) return;
                List<Object> originals = new ArrayList<>(formatters);
                List<Object> pcells = new ArrayList<>();
                for (int i = 0; i < formatters.size(); i++) {
                    String key = (i < pcellKeys.length) ? pcellKeys[i] : null;
                    if (key == null) {
                        pcells.add(formatters.get(i));
                    } else {
                        pcells.add(cloneFormatter(formatters.get(i), key,
                                (Integer) unsafeGetInt.invoke(unsafe, formatters.get(i), attrIndexOffset)));
                    }
                }
                binding.originalV6gFormatters = originals;
                binding.pcellV6gFormatters = pcells;
            } else if (nrClass == v6fClass && pcellKeys.length > 0) {
                Object formatter = v6fFormatterField.get(nrCell);
                if (formatter == null) return;
                Object pcell = cloneFormatter(formatter, pcellKeys[0],
                        (Integer) unsafeGetInt.invoke(unsafe, formatter, attrIndexOffset));
                binding.originalV6fFormatter = formatter;
                binding.pcellV6fFormatter = pcell;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: preparePcellFormatters failed for row " + row + ": " + e);
        }
    }

    private void applyPcellFormatters(RowBinding binding, boolean usePcell) {
        if (binding.usingPcellFormatters == usePcell) return;
        try {
            Class<?> nrClass = binding.nrCell.getClass();
if (nrClass == v6gClass && binding.originalV6gFormatters != null) {
                List<Object> target = usePcell ? binding.pcellV6gFormatters : binding.originalV6gFormatters;
                v6gFormattersField.set(binding.nrCell, target);
            } else if (nrClass == v6fClass && binding.originalV6fFormatter != null) {
                Object target = usePcell ? binding.pcellV6fFormatter : binding.originalV6fFormatter;
                v6fFormatterField.set(binding.nrCell, target);
            }
            binding.usingPcellFormatters = usePcell;
            refreshPcellCell(binding.nrCell, binding.originalRow);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: applyPcellFormatters failed for row " + binding.originalRow + ": " + e);
        }
    }

    private void refreshPcellCell(Object nrCell, int row) {
        try {
            v6aResetMethod.invoke(nrCell);
            Object changed = v6aUpdateMethod.invoke(nrCell, lastSampleKey, lastDataSource, lastModuleIndex);
if (Boolean.TRUE.equals(changed)) {
                Class<?> nrClass = nrCell.getClass();
                Method render = null;
                if (nrClass == v6eClass) {
                    render = v6eRenderMethod;
                } else if (nrClass == v6fClass) {
                    render = v6fRenderMethod;
                } else if (nrClass == v6gClass) {
                    render = v6gRenderMethod;
                }
                if (render != null) {
                    render.invoke(nrCell);
}
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: refreshPcellCell failed for row " + row + ": " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Key/index mappings.
    // -------------------------------------------------------------------------
    private KeyIndex[] getScellKeyIndices(int row, int formatterCount) {
        switch (row) {
            case 3: // Technology
                return new KeyIndex[0];
            case 4: // B/W
                if (formatterCount >= 2) {
                    return new KeyIndex[]{
                            new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_Band", 0),
                            new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL", 0)
                    };
                }
                return new KeyIndex[]{new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_Band", 0)};
            case 5: // Cell
                if (formatterCount >= 2) {
                    return new KeyIndex[]{
                            new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB", 0),
                            new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_PCI", 0)
                    };
                }
                return new KeyIndex[]{new KeyIndex("NR5G::Serving_Cell::SCell::NR_SCell_PCI", 0)};
            case 6: // SINR
                return new KeyIndex[]{new KeyIndex("NR5G::Cell_Measurements::NR_Cells_SINR", 1)};
            case 7: // RSRP
                return new KeyIndex[]{new KeyIndex("NR5G::Cell_Measurements::NR_Cells_RSRP", 1)};
            case 8: // BLER
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_BLER_DL", 0)};
            case 9: // MIMO
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Antenna_Rx", 0)};
            case 10: // Rank
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_Rank_DL", 0)};
            case 11: // RBs
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_RB_Num_Average_DL", 0)};
            case 12: // MCS
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_MCS_DL", 0)};
            case 13: // CQI
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_CQI_DL", 0)};
            case 14: // Modulation
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_Modulation_DL", 0)};
            case 15: // Layers
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Layers_DL", 0)};
            case 16: // Phy. Thput
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_Physical_Throughput_DL", 0)};
            case 17: // MAC Thput
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_Throughput_DL", 0)};
            case 18: // RLC Thput
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_RLC_Throughput_DL", 0)};
            case 19: // PDCP Thput
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_PDCP_Throughput_DL", 0)};
            case 20: // 256Q Util.
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_256QAM_DL", 0)};
            case 21: // 64Q Util.
                return new KeyIndex[]{new KeyIndex("NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_64QAM_DL", 0)};
            default:
                return new KeyIndex[0];
        }
    }

    private String[] getPcellKeys(int row) {
        switch (row) {
            case 4: // B/W
                return new String[]{"NR5G::Serving_Cell::NR_Band", "NR5G::Serving_Cell::NR_Bandwidth_DL"};
            case 5: // Cell
                return new String[]{"NR5G::Serving_Cell::NR_ARFCN_SSB", "NR5G::Serving_Cell::NR_PCI"};
            case 6: // SINR
                return new String[]{"NR5G::Downlink_Measurements::NR_SSB_Beam_SINR"};
            case 7: // RSRP
                return new String[]{"NR5G::Downlink_Measurements::NR_CSI_RSRP"};
            case 8: // BLER
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_BLER_DL"};
            case 9: // MIMO
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_Num_Antenna_Rx"};
            case 10: // Rank
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_Rank_DL"};
            case 11: // RBs
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_RB_Num_Average_DL"};
            case 12: // MCS
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_MCS_DL"};
            case 13: // CQI
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_CQI_DL"};
            case 14: // Modulation
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_Modulation_DL"};
            case 15: // Layers
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_Num_Layers_DL"};
            case 16: // Phy. Thput
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_Physical_Throughput_DL"};
            case 17: // MAC Thput
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_Throughput_DL"};
            case 18: // RLC Thput
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_RLC_Throughput_DL"};
            case 19: // PDCP Thput
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_PDCP_Throughput_DL"};
            case 20: // 256Q Util.
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_256QAM_DL"};
            case 21: // 64Q Util.
                return new String[]{"NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_64QAM_DL"};
            default:
                return new String[0];
        }
    }

    // -------------------------------------------------------------------------
    // SCell detection.
    // -------------------------------------------------------------------------
    private boolean isScellPresent(Object dataSource, long sampleKey, short moduleIndex) {
        try {
            Class<?> dsClass = ClassMapping.loadClass(DATA_SOURCE_CLASS, loader);
            Class<?> propClass = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterClass = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);
            if (dsClass == null || propClass == null || iterClass == null) {
                Log.w(TAG, "isScellPresent: missing Property/Iterator classes");
                return false;
            }
            Object ds = dataSource;
            Method getProperty = ClassMapping.getMethod(dsClass, DATA_SOURCE_CLASS, "getProperty", loader,
                    String.class, int.class);
            Method iteratorB = ClassMapping.getMethod(propClass, "com.qtrun.sys.Property", "b", loader, long.class);
            Method end = ClassMapping.getMethod(iterClass, "com.qtrun.sys.Property$Iterator", "end", loader);
            Method value = ClassMapping.getMethod(iterClass, "com.qtrun.sys.Property$Iterator", "value", loader);
            Method key = ClassMapping.getMethod(iterClass, "com.qtrun.sys.Property$Iterator", "key", loader);

            Object property = getProperty.invoke(ds, KEY_SCELL_PCI, (int) moduleIndex);
            if (property == null) {
return false;
            }
            Object it = iteratorB.invoke(property, sampleKey);
            if (it == null || (boolean) end.invoke(it)) {
return false;
            }
            Object val = value.invoke(it);
            if (val == null) {
                long actual = (long) key.invoke(it);
                if (actual > 0) {
                    it = iteratorB.invoke(property, actual - 1);
                    if (it == null || (boolean) end.invoke(it)) return false;
                    val = value.invoke(it);
                }
            }
            if (!(val instanceof Object[])) {
return false;
            }
            Object[] arr = (Object[]) val;
            if (arr.length == 0) return false;
            Object first = arr[0];
            if (first instanceof Number) {
                int pci = ((Number) first).intValue();
return pci >= 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: SCell PCI check failed: " + e);
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Generic helpers.
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
            Class<?> clazz = ClassMapping.loadClass(className, loader);
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

        boolean wrapped;
        LinearLayout container;
        TextView pcellView;
        TextView scellView;
        ScellCell scellCell;

        // Dynamic PCell formatter swapping: original (aggregated) and
        // PCell-specific clones. Only non-null for rows where a PCell-specific
        // key is used (BLER, RBs, MCS, CQI, Modulation, ModUsage, Phy/MAC
        // throughput). RLC/PDCP throughput (rows 18/19) keep the stock formatter.
        Object originalV6fFormatter;
        Object pcellV6fFormatter;
        List<Object> originalV6gFormatters;
        List<Object> pcellV6gFormatters;
        boolean usingPcellFormatters;

        RowBinding(int originalRow, Object labelCell, Object lteCell, Object nrCell) {
            this.originalRow = originalRow;
            this.labelCell = labelCell;
            this.lteCell = lteCell;
            this.nrCell = nrCell;
        }
    }

    private static class ScellCell {
        final boolean isStatic;
        final Object helper;
        final Method renderMethod;

        ScellCell(boolean isStatic, Object helper, Method renderMethod) {
            this.isStatic = isStatic;
            this.helper = helper;
            this.renderMethod = renderMethod;
        }
    }

    private static class KeyIndex {
        final String key;
        final int index;

        KeyIndex(String key, int index) {
            this.key = key;
            this.index = index;
        }
    }
}
