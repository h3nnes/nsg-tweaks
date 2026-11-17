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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks ta0 (NR-NSA "NRNSA_Dual_Connectivity" page) to show the first NR SCell value
 * stacked beneath the existing NR PCell value in the right-hand NR column.
 *
 * When an NR SCell is aggregated (NR_SCell_PCI[0] is valid) the NR data rows are
 * stretched so each logical row occupies two physical row units: PCell on top,
 * SCell below. The left-hand LTE and label cells in the same row are also
 * stretched so the grid row height stays shared.
 *
 * This mirrors the way db0 (NR-SA CA Matrix DL) stacks PCell + SCell values
 * in 3x NR-CA mode, adapted to the existing single-row-per-metric layout of
 * ta0.
 *
 * NSG v4.8.14 targets:
 *   ta0 extends yg0 (grid page): yg0.E(LayoutInflater, ViewGroup, Bundle) is onCreateView,
 *     yg0.b(DataSource, long, short, Object) the per-tick update, yg0.X the r31 builder.
 *   r31 (grid builder): cells b (ArrayList of xg0), grid view a (ah0).
 *   xg0 (cell base): view a, row b, height c, x d; a(long, DataSource, short) updates the
 *     value, b() resets it.
 *   ah0 (grid FrameLayout): cells a.
 *   bh0 (label): text f; ch0 (bar): value f, formatter g, manual h, color i, max j;
 *   dh0 (multi): formatters f, separator h, color i, alt color j, gravity k.
 *   bh0 / ch0 / dh0 each declare e() that refreshes the cell view.
 *   l8 (attribute): key a, format b, index c, property d.
 */
public class NrNsaScellDualConnectivityHook {

    private static final String TAG = "NSGBandHook:Scell";

    private static final String TARGET_FRAGMENT = Nsg.PAGE_NSA_DUAL_CONN;  // ta0
    private static final String ATTR_CLASS = "l8";
    private static final String DATA_SOURCE_CLASS = Nsg.DATA_SOURCE;
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
    // The NR-SA CA Matrix DL (db0, carrier mode 3) uses two single-height rows
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

    /** ta0. Used for exact-fragment checks. */
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

    private Field builderCellsField;        // r31.b -> ArrayList<xg0>
    private Field elementViewField;         // xg0.a -> View
    private Field elementRowField;          // xg0.b -> float
    private Field elementHeightField;       // xg0.c -> float
    private Field elementXField;            // xg0.d -> float
    private Field gridCellsField;        // ah0.a -> ArrayList<xg0>

    private Field multiFormattersField;   // dh0.f -> ArrayList<m8>
    private Field multiSeparatorField;    // dh0.h -> String
    private Field multiColorField;        // dh0.i -> int
    private Field multiAltColorField;     // dh0.j -> int
    private Field multiGravityField;      // dh0.k -> int

    private Field barValueField;        // ch0.f -> Object
    private Field barFormatterField;    // ch0.g -> m8
    private Field barManualField;       // ch0.h -> boolean
    private Field barColorField;        // ch0.i -> int
    private Field barMaxField;          // ch0.j -> float

    private Field labelTextField;         // bh0.f -> String

    private Method elementUpdateMethod;     // xg0.a(long, DataSource, short)
    private Method elementResetMethod;      // xg0.b()

    // Cached per-tick handles: yg0.X (grid builder) and r31.a (grid view ah0).
    private Field pageBuilderField;            // yg0.X -> r31 grid builder
    private Field builderGridField;         // r31.a -> View (ah0 grid)

    // Cached handles for isScellPresent.
    private Method  scellGetProperty;   // DataSource.getProperty(String, int)
    private Method  scellIteratorB;     // Property.b(long)
    private Method  scellIterEnd;       // Property$Iterator.end()
    private Method  scellIterValue;     // Property$Iterator.value()
    private Method  scellIterKey;       // Property$Iterator.key()
    private Class<?> labelClass;
    private Class<?> barClass;
    private Class<?> multiClass;

    private Method labelRenderMethod;     // bh0.e()
    private Method barRenderMethod;     // ch0.e()
    private Method multiRenderMethod;     // dh0.e()

    private Constructor<?> fontTextViewCtor;
    private Constructor<?> progressTextViewCtor;

    // Bindings for every row that has an NR column cell.
    private final List<RowBinding> rowBindings = new ArrayList<>();
    // Identity-keyed lookup for findBinding (replaces the per-render linear scan).
    private final Map<Object, RowBinding> bindingsByNrCell = new IdentityHashMap<>();
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
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);   // r31
            Class<?> elementClass = Nsg.load(loader, Nsg.GRID_ELEMENT);   // xg0
            Class<?> pageClass = Nsg.load(loader, Nsg.GRID_PAGE);         // yg0
            Class<?> gridClass = Nsg.load(loader, Nsg.GRID_D);            // ah0
            labelClass = Nsg.load(loader, Nsg.GRID_LABEL);                // bh0
            barClass = Nsg.load(loader, Nsg.GRID_VALUE);                  // ch0
            multiClass = Nsg.load(loader, Nsg.GRID_MULTI);                // dh0
            Class<?> attrClass = Nsg.load(loader, ATTR_CLASS);            // l8
            Class<?> dsClass = Nsg.load(loader, DATA_SOURCE_CLASS);
            Class<?> fontTextViewClass = Nsg.load(loader, FONT_TEXT_VIEW_CLASS);
            Class<?> progressTextViewClass = Nsg.load(loader, PROGRESS_TEXT_VIEW_CLASS);
            targetFragmentClass = Nsg.load(loader, TARGET_FRAGMENT);
            if (builderClass == null || elementClass == null || pageClass == null || gridClass == null
                    || labelClass == null || barClass == null || multiClass == null || attrClass == null
                    || dsClass == null || fontTextViewClass == null || progressTextViewClass == null
                    || targetFragmentClass == null) {
                Log.w(TAG, "NrNsaScellDualConnectivityHook: essential class missing, skipping");
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

            attrKeyOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrClass.getDeclaredField("a"));
            attrFormatOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrClass.getDeclaredField("b"));
            attrIndexOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrClass.getDeclaredField("c"));
            attrPropertyOffset = (Long) unsafeObjectFieldOffset.invoke(unsafe, attrClass.getDeclaredField("d"));

            builderCellsField = builderClass.getDeclaredField("b");
            builderGridField = builderClass.getField("a");
            pageBuilderField = pageClass.getField("X");

            elementViewField = elementClass.getDeclaredField("a");
            elementRowField = elementClass.getDeclaredField("b");
            elementHeightField = elementClass.getDeclaredField("c");
            elementXField = elementClass.getDeclaredField("d");
            gridCellsField = gridClass.getDeclaredField("a");

            multiFormattersField = multiClass.getDeclaredField("f");
            multiSeparatorField = multiClass.getDeclaredField("h");
            multiColorField = multiClass.getDeclaredField("i");
            multiAltColorField = multiClass.getDeclaredField("j");
            multiGravityField = multiClass.getDeclaredField("k");

            barValueField = barClass.getDeclaredField("f");
            barFormatterField = barClass.getDeclaredField("g");
            barManualField = barClass.getDeclaredField("h");
            barColorField = barClass.getDeclaredField("i");
            barMaxField = barClass.getDeclaredField("j");

            labelTextField = labelClass.getDeclaredField("f");

            for (Field f : new Field[]{builderCellsField, elementViewField, elementRowField,
                    elementHeightField, elementXField, gridCellsField, multiFormattersField,
                    multiSeparatorField, multiColorField, multiAltColorField, multiGravityField,
                    barValueField, barFormatterField, barManualField, barColorField, barMaxField,
                    labelTextField}) {
                f.setAccessible(true);
            }

            elementUpdateMethod = elementClass.getMethod("a", long.class, dsClass, short.class);
            elementResetMethod = elementClass.getMethod("b");
            labelRenderMethod = labelClass.getDeclaredMethod("e");
            barRenderMethod = barClass.getDeclaredMethod("e");
            multiRenderMethod = multiClass.getDeclaredMethod("e");

            fontTextViewCtor = fontTextViewClass.getConstructor(Context.class, AttributeSet.class);
            fontTextViewCtor.setAccessible(true);
            progressTextViewCtor = progressTextViewClass.getConstructor(Context.class, AttributeSet.class);
            progressTextViewCtor.setAccessible(true);

            Class<?> propCls = Nsg.load(loader, Nsg.PROPERTY);
            Class<?> iterCls = Nsg.load(loader, Nsg.PROPERTY_ITER);
            scellGetProperty = dsClass.getMethod("getProperty", String.class, int.class);
            scellIteratorB = propCls.getMethod("b", long.class);
            scellIterEnd = iterCls.getMethod("end");
            scellIterValue = iterCls.getMethod("value");
            scellIterKey = iterCls.getMethod("key");

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
        try {
            hookI();
            hookB();
            hookLabelRender();
            hookBarRender();
            hookMultiRender();
            Log.i(TAG, "NrNsaScellDualConnectivityHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: install failed: " + e);
        }
    }

    // -------------------------------------------------------------------------
    // Hook 1: yg0.E(...) — rebuild bindings every time the fragment view is
    // created. yg0.E() is the real onCreateView; ta0 inherits it.
    // On first creation it calls ta0.h0(Context); on later recreations (e.g. swipe
    // back in the view pager) h0() is skipped and only E() runs, so hooking
    // h0() alone leaves stale view references in rowBindings.
    // -------------------------------------------------------------------------
    private void hookI() {
        try {
            Method iMethod = Nsg.load(loader, Nsg.GRID_PAGE).getDeclaredMethod("E",
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
        bindingsByNrCell.clear();
        originalGeometry.clear();
        currentlyStretched = false;

        Object builder = getFieldValue(fragment, pageBuilderField);
        if (builder == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: fragment.Y is null");
            return;
        }

        List<Object> cells;
        try {
            cells = (List<Object>) builderCellsField.get(builder);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot read builder cells: " + e);
            return;
        }
        if (cells == null) {
return;
        }
// Group cells by row.
        Map<Integer, RowCells> rows = new HashMap<>();
        for (Object cell : cells) {
            if (cell == null) continue;
            float x = getFloat(cell, elementXField);
            float row = getFloat(cell, elementRowField);
            float height = getFloat(cell, elementHeightField);
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
            bindingsByNrCell.put(rc.nr, binding);
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
Object builder = getFieldValue(fragment, pageBuilderField);
        if (builder == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot wrap, fragment.Y is null");
            return false;
        }
        View gridView = (View) getFieldValue(builder, builderGridField);
        if (gridView == null) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: cannot wrap, grid is null");
            return false;
        }
List<Object> gridCells;
        try {
            gridCells = (List<Object>) gridCellsField.get(gridView);
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
            if (binding.nrCell.getClass() == multiClass) {
                scellView.setTextColor(TURQUOISE);
}

            gridGroup.removeViewAt(index);
            gridGroup.addView(container, index);
// Stack PCell and SCell sub-views with a small transparent gap
            // between them, matching the 2 dp row distance used by the grid
            // (ah0.g). This mirrors the NR-SA CA Matrix DL 3x NR-CA layout
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

            setField(binding.nrCell, elementViewField, container);

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
        Object builder = getFieldValue(fragment, pageBuilderField);
        if (builder == null) return;
        View gridView = (View) getFieldValue(builder, builderGridField);
        if (gridView == null) return;

        List<Object> gridCells;
        try {
            gridCells = (List<Object>) gridCellsField.get(gridView);
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

            setField(binding.nrCell, elementViewField, binding.pcellView);

            // SCell is gone: revert the PCell cell to its original aggregated
            // formatter(s) and refresh so PCell-only data is displayed again.
            applyPcellFormatters(binding, false);
            setField(binding.nrCell, elementViewField, binding.pcellView);

            binding.container = null;
            binding.pcellView = null;
            binding.scellView = null;
            binding.scellCell = null;
            binding.wrapped = false;
        }
    }

    // -------------------------------------------------------------------------
    // Hook 2: yg0.b(DataSource, long, short, Object) — data updates & stretch.
    // -------------------------------------------------------------------------
    private void hookB() {
        try {
            Class<?> dsClass = Nsg.load(loader, DATA_SOURCE_CLASS);
            // yg0.b(DataSource, long, short, Object): inherited by ta0
            Method bMethod = Nsg.load(loader, Nsg.GRID_PAGE).getDeclaredMethod("b",
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
        // yg0.b is the inherited per-tick update of every grid page (ta0 does not
        // override it), so filter for the Dual Connectivity page here.
        if (fragment == null || fragment.getClass() != targetFragmentClass) return;
        Object builder = getFieldValue(fragment, pageBuilderField);
        if (builder == null) return;
        View gridView = (View) getFieldValue(builder, builderGridField);
        if (gridView == null || !gridView.isAttachedToWindow()) return;

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
            // Re-apply stretched geometry only on transitions. NSG's per-tick
            // update path (yg0.b -> r31.j -> xg0.a()/e()) only touches cell
            // VALUES, never the row/height geometry fields, and view rebuilds reset currentlyStretched via
            // resetAndRebuild(), so the stretched geometry persists between
            // ticks and re-applying it every tick was redundant work.
            if (!currentlyStretched) {
                stretchGrid(true);
                currentlyStretched = true;
                layoutNeeded = true;
            }
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
                // lower, matching db0's geometry in 3x NR-CA mode.
                if (binding.lteCell != null) {
                    if (binding.lteCell.getClass() == barClass) {
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

        // In case there are any cells below LAST_DATA_ROW (there should not be in ta0),
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
            elementRowField.setFloat(cell, row);
            elementHeightField.setFloat(cell, height);
        } catch (Exception ignored) {
        }
    }

    private void requestGridLayout(Object fragment) {
        try {
            Object builder = getFieldValue(fragment, pageBuilderField);
            if (builder == null) return;
            View gridView = (View) getFieldValue(builder, builderGridField);
            if (gridView != null) {
                gridView.requestLayout();
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Hook 3: bh0/ch0/dh0.e() — PCell view updates after xg0.a was replaced.
    // -------------------------------------------------------------------------
    private void hookLabelRender() {
        hookRenderMethod(labelRenderMethod);
    }

    private void hookBarRender() {
        hookRenderMethod(barRenderMethod);
    }

    private void hookMultiRender() {
        hookRenderMethod(multiRenderMethod);
    }

    private void hookRenderMethod(final Method eMethod) {
        try {
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
                        elementViewField.set(cell, pcellView);
                        return chain.proceed();
                    } finally {
                        elementViewField.set(cell, container);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrNsaScellDualConnectivityHook: hookRenderMethod(" + eMethod.getDeclaringClass().getName() + ") failed: " + e);
        }
    }

    private RowBinding findBinding(Object nrCell) {
        // Identity-keyed map — same result as the legacy linear scan over
        // rowBindings comparing binding.nrCell == nrCell.
        return bindingsByNrCell.get(nrCell);
    }

    // -------------------------------------------------------------------------
    // SCell helper cell creation and updates.
    // -------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private ScellCell createScellCell(RowBinding binding, TextView scellView) {
        try {
            Object nrCell = binding.nrCell;
            Class<?> nrClass = nrCell.getClass();
if (nrClass == labelClass) {
                // Label/technology row: keep the SCell sub-view empty.
                return new ScellCell(true, null, null);
            }

            if (nrClass == multiClass) {
                Object helper = unsafeAllocateInstance.invoke(unsafe, nrClass);

                List<Object> pcellFormatters = (List<Object>) multiFormattersField.get(nrCell);
                KeyIndex[] scellKeys = getScellKeyIndices(binding.originalRow, pcellFormatters.size());

                List<Object> scellFormatters = new ArrayList<>();
                for (int i = 0; i < scellKeys.length && i < pcellFormatters.size(); i++) {
                    scellFormatters.add(cloneFormatter(
                            pcellFormatters.get(i), scellKeys[i].key, scellKeys[i].index));
                }

                multiFormattersField.set(helper, scellFormatters);
                multiSeparatorField.set(helper, multiSeparatorField.get(nrCell));
                multiColorField.set(helper, TURQUOISE);
                multiAltColorField.set(helper, multiAltColorField.get(nrCell));
                multiGravityField.set(helper, multiGravityField.get(nrCell));
                elementViewField.set(helper, scellView);

                return new ScellCell(false, helper, multiRenderMethod);
            }

            if (nrClass == barClass) {
                Object pcellFormatter = barFormatterField.get(nrCell);
                KeyIndex[] scellKeys = getScellKeyIndices(binding.originalRow, 1);
                Object scellFormatter = cloneFormatter(
                        pcellFormatter, scellKeys[0].key, scellKeys[0].index);

                Object helper = unsafeAllocateInstance.invoke(unsafe, nrClass);
                barValueField.set(helper, null);
                barFormatterField.set(helper, scellFormatter);
                barManualField.set(helper, barManualField.get(nrCell));
                barColorField.set(helper, barColorField.get(nrCell));
                barMaxField.set(helper, barMaxField.get(nrCell));
                elementViewField.set(helper, scellView);

                return new ScellCell(false, helper, barRenderMethod);
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
                elementUpdateMethod.invoke(binding.scellCell.helper, sampleKey, null, moduleIndex);
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
if (nrClass == multiClass) {
                List<Object> formatters = (List<Object>) multiFormattersField.get(nrCell);
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
                binding.originalMultiFormatters = originals;
                binding.pcellMultiFormatters = pcells;
            } else if (nrClass == barClass && pcellKeys.length > 0) {
                Object formatter = barFormatterField.get(nrCell);
                if (formatter == null) return;
                Object pcell = cloneFormatter(formatter, pcellKeys[0],
                        (Integer) unsafeGetInt.invoke(unsafe, formatter, attrIndexOffset));
                binding.originalBarFormatter = formatter;
                binding.pcellBarFormatter = pcell;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: preparePcellFormatters failed for row " + row + ": " + e);
        }
    }

    private void applyPcellFormatters(RowBinding binding, boolean usePcell) {
        if (binding.usingPcellFormatters == usePcell) return;
        try {
            Class<?> nrClass = binding.nrCell.getClass();
if (nrClass == multiClass && binding.originalMultiFormatters != null) {
                List<Object> target = usePcell ? binding.pcellMultiFormatters : binding.originalMultiFormatters;
                multiFormattersField.set(binding.nrCell, target);
            } else if (nrClass == barClass && binding.originalBarFormatter != null) {
                Object target = usePcell ? binding.pcellBarFormatter : binding.originalBarFormatter;
                barFormatterField.set(binding.nrCell, target);
            }
            binding.usingPcellFormatters = usePcell;
            refreshPcellCell(binding.nrCell, binding.originalRow);
        } catch (Exception e) {
            Log.w(TAG, "NrNsaScellDualConnectivityHook: applyPcellFormatters failed for row " + binding.originalRow + ": " + e);
        }
    }

    private void refreshPcellCell(Object nrCell, int row) {
        try {
            elementResetMethod.invoke(nrCell);
            Object changed = elementUpdateMethod.invoke(nrCell, lastSampleKey, lastDataSource, lastModuleIndex);
if (Boolean.TRUE.equals(changed)) {
                Class<?> nrClass = nrCell.getClass();
                Method render = null;
                if (nrClass == labelClass) {
                    render = labelRenderMethod;
                } else if (nrClass == barClass) {
                    render = barRenderMethod;
                } else if (nrClass == multiClass) {
                    render = multiRenderMethod;
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
            Object ds = dataSource;

            Object property = scellGetProperty.invoke(ds, KEY_SCELL_PCI, (int) moduleIndex);
            if (property == null) {
return false;
            }
            Object it = scellIteratorB.invoke(property, sampleKey);
            if (it == null || (boolean) scellIterEnd.invoke(it)) {
return false;
            }
            Object val = scellIterValue.invoke(it);
            if (val == null) {
                long actual = (long) scellIterKey.invoke(it);
                if (actual > 0) {
                    it = scellIteratorB.invoke(property, actual - 1);
                    if (it == null || (boolean) scellIterEnd.invoke(it)) return false;
                    val = scellIterValue.invoke(it);
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

    private static Object getFieldValue(Object obj, Field field) {
        try {
            return field.get(obj);
        } catch (Exception ignored) {
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
        Object originalBarFormatter;
        Object pcellBarFormatter;
        List<Object> originalMultiFormatters;
        List<Object> pcellMultiFormatters;
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
