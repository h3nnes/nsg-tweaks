package com.nsgmod.band;

import android.content.Context;
import android.util.AttributeSet;
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
 * Hooks nc.getView for the LTE table (nc.f == 4, fragment y00) to inject BOTH a BW column (between
 * Band and EARFCN) and an SNR (LTE SINR) column (as the last column, after RSRQ)
 * into the LTE cell table — using a single getView hook and a single onCreateView
 * hook to avoid the interference caused by two separate hooks.
 *
 * <h3>Final 8-column layout</h3>
 *   [Serving, Band, BW, EARFCN, PCI, RSRP, RSRQ, SNR]
 *   weights: {0.05, 0.10, 0.09, 0.17, 0.14, 0.15, 0.15, 0.15}
 *
 * <h3>BW keys</h3>
 *   LTE::Serving_Cell::LTE_Bandwidth_PCell_DL          (intraRow == 0)
 *   LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell{N}_DL  (intraRow 1-7)
 *   Enum: 0=1.4, 1=3, 2=5, 3=10, 4=15, 5=20 (MHz)
 *
 * <h3>SINR keys</h3>
 *   LTE::Downlink_Measurements::LTE_SINR_PCell          (intraRow == 0)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell1     (intraRow == 1)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell2     (intraRow == 2)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell3     (intraRow == 3)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell4     (intraRow == 4)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell5     (intraRow == 5)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell6     (intraRow == 6)
 *   LTE::Downlink_Measurements::SCC::LTE_SINR_SCell7     (intraRow == 7)
 *
 * <h3>SINR color coding</h3>
 * The SNR column uses {@code com.qtrun.widget.textview.ProgressTextView} (not a
 * plain TextView) with {@code com.qtrun.legend.LegendManager} color coding, the
 * same mechanism NSG uses for RSRP/RSRQ columns (decompiled
 * {@code nc.j/nc.k}). Each intraRow gets an
 * {@code m8} attribute descriptor (subclass of {@code l8}: key a, format b,
 * index c) created via Unsafe and cached.
 *
 * <p>Data is read fresh on every getView() call using the adapter's own sample
 * key (q00.c) as the queryTime anchor — same Property.b(queryTime) -> iter.value()
 * pattern as NSG's v6/f.java. A prev-tick fallback handles partially-written
 * head entries where iter.value() returns null.
 */
public class LteBandwidthColumnHook {

    private static final String TAG = "NSGBandHook";

    private static final float[] WEIGHTS = {
            0.05f, 0.10f, 0.09f, 0.17f, 0.14f, 0.15f, 0.15f, 0.15f
    };

    // Precomputed LTE BW / SINR property keys (identical strings to the legacy
    // per-row concatenations, computed once at class init).
    private static final String[] LTE_BW_KEYS = {
            "LTE::Serving_Cell::LTE_Bandwidth_PCell_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell1_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell2_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell3_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell4_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell5_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell6_DL",
            "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell7_DL",
    };
    private static final String[] LTE_SINR_KEYS = {
            "LTE::Downlink_Measurements::LTE_SINR_PCell",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell1",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell2",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell3",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell4",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell5",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell6",
            "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell7",
    };

    // Tag keys on the row LinearLayout.
    private static final String TAG_HEADER_INJECTED = "nsg_lte_header_injected";
    private static final int    HEADER_TAG_KEY    = "nsg_lte_header_injected".hashCode();
    private static final int    BW_VIEW_TAG_KEY   = "nsg_ltebw_view".hashCode();
    private static final int    SNR_VIEW_TAG_KEY  = "nsg_ltesinr_view".hashCode();

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // ------------------------------------------------------------------
    // Workspace / Property reflection (for reading BW and SINR data)
    // ------------------------------------------------------------------
    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;
    private Method iterValueMethod;

    private static final int ADAPTER_TYPE_LTE = 4;
    private static final String LTE_FRAGMENT = "y00"; // LTECells fragment
    private static final String ATTR_BASE = "l8";     // attribute base (key/format/index)
    private static final String ATTR_SUB  = "m8";     // attribute subclass used as legend key

    // ------------------------------------------------------------------
    // SINR color-coding reflection
    // ------------------------------------------------------------------
    private Class<?>         ptvClass;          // com.qtrun.widget.textview.ProgressTextView
    private Constructor<?>  ptvCtor;           // (Context, AttributeSet)
    private Field            ptvFieldJ;        // boolean show-bar flag
    private Method           ptvMethodH;       // h(int, float)
    private Method           ptvSetProgress;   // setProgress(float)

    private Class<?>         legendClass;      // com.qtrun.legend.LegendManager
    private Field            legendSingleton;  // static singleton field
    private Method           legendMethodC;    // b(m8, double) -> float
    private Method           legendMethodA;    // a(m8, double) -> Integer
    /** Cached LegendManager singleton instance (stable app object, not modem
     *  data) — avoids a reflective static-field read on every LTE row per frame. */
    private volatile Object  legendSingletonCache;

    private Class<?>         sysBClass;        // m8
    private Field            sysAFieldA;       // l8.a — String key
    private Field            sysAFieldB;       // l8.b — String format
    private Field            sysAFieldC;       // l8.c — int index
    private Method           sysACMethod;      // l8.c(Object) -> String

    private Object           unsafe;
    private Method           unsafeAllocateInstance;
    private Object[]         sinrAttrs;

    private boolean reflectionReady = false;
    private boolean sinrColorReady   = false;

    public LteBandwidthColumnHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> dsClass   = Nsg.load(loader, Nsg.DATA_SOURCE);
            Class<?> propClass = Nsg.load(loader, Nsg.PROPERTY);
            Class<?> iterClass = Nsg.load(loader, Nsg.PROPERTY_ITER);
            if (dsClass == null || propClass == null || iterClass == null) {
                Log.i(TAG, "LteBandwidthColumnHook: essential class missing, skipping");
                return;
            }

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "LteBandwidthColumnHook: initReflection failed: " + e);
            return;
        }

        // --- SINR color-coding reflection (best-effort) ---
        initSinrColorReflection();
    }

    private void initSinrColorReflection() {
        try {
            ptvClass = Nsg.load(loader, "com.qtrun.widget.textview.ProgressTextView");
            if (ptvClass == null) {
                Log.w(TAG, "LteBandwidthColumnHook: ProgressTextView not available, "
                        + "SNR will use plain TextView");
                return;
            }
            ptvCtor = ptvClass.getConstructor(Context.class, AttributeSet.class);
            ptvCtor.setAccessible(true);

            ptvFieldJ = ptvClass.getDeclaredField("j");
            ptvFieldJ.setAccessible(true);

            ptvMethodH = ptvClass.getDeclaredMethod("h", int.class, float.class);
            ptvMethodH.setAccessible(true);

            ptvSetProgress = ptvClass.getMethod("setProgress", float.class);

            legendClass = Nsg.load(loader, "com.qtrun.legend.LegendManager");
            if (legendClass == null) {
                Log.w(TAG, "LteBandwidthColumnHook: LegendManager not available, "
                        + "SNR will show without color coding");
                return;
            }
            legendSingleton = legendClass.getDeclaredField("e"); // enum singleton
            legendSingleton.setAccessible(true);

            sysBClass = Nsg.load(loader, ATTR_SUB);
            Class<?> sysAClass = Nsg.load(loader, ATTR_BASE);
            if (sysBClass == null || sysAClass == null) {
                Log.w(TAG, "LteBandwidthColumnHook: " + ATTR_SUB + "/" + ATTR_BASE + " not available");
                return;
            }
            sysAFieldA = sysAClass.getDeclaredField("a");
            sysAFieldB = sysAClass.getDeclaredField("b");
            sysAFieldC = sysAClass.getDeclaredField("c");
            sysAFieldA.setAccessible(true);
            sysAFieldB.setAccessible(true);
            sysAFieldC.setAccessible(true);
            sysACMethod = sysAClass.getMethod("c", Object.class);

            // LegendManager: b(m8, double) -> float (progress), a(m8, double) -> Integer (color)
            legendMethodC = legendClass.getDeclaredMethod("b", sysBClass, double.class);
            legendMethodC.setAccessible(true);
            legendMethodA = legendClass.getDeclaredMethod("a", sysBClass, double.class);
            legendMethodA.setAccessible(true);

            // Unsafe for allocateInstance (m8 has no usable constructor).
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");   // Android/Dalvik
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe"); // OpenJDK fallback
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            sinrAttrs = new Object[8];
            sinrAttrs[0] = makeSinrAttr("LTE::Downlink_Measurements::LTE_SINR_PCell", -1, "%.1f");
            sinrAttrs[1] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell1", -1, "%.1f");
            sinrAttrs[2] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell2", -1, "%.1f");
            sinrAttrs[3] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell3", -1, "%.1f");
            sinrAttrs[4] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell4", -1, "%.1f");
            sinrAttrs[5] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell5", -1, "%.1f");
            sinrAttrs[6] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell6", -1, "%.1f");
            sinrAttrs[7] = makeSinrAttr("LTE::Downlink_Measurements::SCC::LTE_SINR_SCell7", -1, "%.1f");

            sinrColorReady = true;
            Log.i(TAG, "LteBandwidthColumnHook: SINR color coding ready");
        } catch (Exception e) {
            Log.w(TAG, "LteBandwidthColumnHook: SINR color-coding reflection failed "
                    + "(SNR will show as plain text): " + e);
            sinrColorReady = false;
        }
    }

    /**
     * Allocate an m8 via Unsafe and set its key/format/index fields (l8.a/b/c).
     */
    private Object makeSinrAttr(String key, int index, String format) throws Exception {
        Object prop = unsafeAllocateInstance.invoke(unsafe, sysBClass);
        sysAFieldA.set(prop, key);
        sysAFieldB.set(prop, format);
        sysAFieldC.set(prop, index);
        return prop;
    }

    // -----------------------------------------------------------------------
    // BW reading — fresh on every getView(), no persistent cache.
    // -----------------------------------------------------------------------

    /**
     * Read LTE BW for the given row fresh from the DataSource.
     * @param adsk adapter's current sample key (q00.c); used as queryTime anchor.
     * @param intraRow 0 = PCell, 1..7 = SCell N (matches the SCell number in the key).
     */
    private String readLteBwFresh(Object ds, int modIdx, long adsk, int intraRow) {
        long qt = (adsk > 0) ? adsk : Long.MAX_VALUE;
        String key = (intraRow >= 0 && intraRow < LTE_BW_KEYS.length)
                ? LTE_BW_KEYS[intraRow]
                : "LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell" + intraRow + "_DL";
        return readScalarBw(ds, key, modIdx, qt);
    }

    private String readScalarBw(Object ds, String key, int modIdx, long qt) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, qt);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sk = (long) iterKeyMethod.invoke(iter);
            Object val = iterValueMethod.invoke(iter);
            if (val == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                val = iterValueMethod.invoke(iter);
            }
            if (!(val instanceof Integer)) return null;
            return bwEnumToString((Integer) val);
        } catch (Exception e) {
            Log.w(TAG, "LteBW readScalarBw(" + key + ") failed: " + e);
            return null;
        }
    }

    private static String bwEnumToString(int i) {
        switch (i) {
            case 0: return "1.4";
            case 1: return "3";
            case 2: return "5";
            case 3: return "10";
            case 4: return "15";
            case 5: return "20";
            default:
                float mhz = i / 5.0f;
                if (mhz == (int) mhz) return String.valueOf((int) mhz);
                return String.format("%.1f", mhz);
        }
    }

    // -----------------------------------------------------------------------
    // SINR reading — fresh on every getView(), returns raw Float for color coding.
    // -----------------------------------------------------------------------

    /**
     * Read LTE SINR for the given row fresh from the DataSource.
     * @return raw Float value (for LegendManager color coding), or null.
     */
    private Float readLteSinrRaw(Object ds, int modIdx, long adsk, int intraRow) {
        long qt = (adsk > 0) ? adsk : Long.MAX_VALUE;
        String key = (intraRow >= 0 && intraRow < LTE_SINR_KEYS.length)
                ? LTE_SINR_KEYS[intraRow]
                : "LTE::Downlink_Measurements::SCC::LTE_SINR_SCell" + intraRow;
        return readScalarSinrRaw(ds, key, modIdx, qt);
    }

    private Float readScalarSinrRaw(Object ds, String key, int modIdx, long qt) {
        try {
            Object prop = dsGetProperty.invoke(ds, key, modIdx);
            if (prop == null) return null;
            Object iter = propIterMethod.invoke(prop, qt);
            if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
            long sk = (long) iterKeyMethod.invoke(iter);
            Object val = iterValueMethod.invoke(iter);
            if (val == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iter = propIterMethod.invoke(prop, sk - 1);
                if (iter == null || (boolean) iterEndMethod.invoke(iter)) return null;
                val = iterValueMethod.invoke(iter);
            }
            if (val instanceof Float) return (Float) val;
            if (val instanceof Number) return ((Number) val).floatValue();
            return null;
        } catch (Exception e) {
            Log.w(TAG, "LteSINR readScalarSinrRaw(" + key + ") failed: " + e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        hookGetView();
        hookOnCreateView();
    }

    // -----------------------------------------------------------------------
    // Hook: nc.getView (LTE rows) — registered with the shared CellTableRowDispatcher.
    // Runs AFTER
    // CellRowHeightHook's row processor (reverse registration order), exactly
    // as in the legacy interceptor chain.
    //
    // Injects BOTH the BW column (between Band and EARFCN) and the SNR column
    // (last column, after RSRQ) in a single pass.
    // -----------------------------------------------------------------------
    private void hookGetView() {
        if (!reflectionReady) {
            Log.e(TAG, "LteBWHook.hookGetView skipped — reflection not ready");
            return;
        }
        boolean registered =
                CellTableRowDispatcher.register(xposed, loader, "LteBandwidthColumnHook",
                new CellTableRowDispatcher.RowProcessor() {
                    @Override
                    public void afterProceed(CellTableRowDispatcher.RowContext ctx, Object result) {
                        if (!SettingsToggleHook.cellModsEnabled()) return;
                        if (!ctx.isAdapterType(ADAPTER_TYPE_LTE)) return;
                        View rowView = (View) result;
                        if (rowView == null) return;

                        LinearLayout row = findHorizontalRow(rowView);
                        if (row == null) return;

                        // Resolve (isServing, intraRow, adapterSampleKey) from the
                        // shared row context (resolved once per getView call).
                        int     position         = ctx.position();
                        boolean isServing        = ctx.isSource0();
                        int     intraRow         = ctx.intraRow();
                        long    adapterSampleKey = ctx.sampleKey();

                        // Read BW and SINR fresh — no persistent cache.
                        // DataSource/moduleIndex resolved once per getView call and
                        // shared with other cell-table hooks via RowContext.
                        String bwText  = null;
                        Float  sinrRaw = null;
                        if (isServing && reflectionReady) {
                            Object ds = ctx.dataSource();
                            int modIdx = ctx.moduleIndex();
                            if (ds != null) {
                                try {
                                    bwText  = readLteBwFresh(ds, modIdx,
                                            adapterSampleKey, intraRow);
                                    sinrRaw = readLteSinrRaw(ds, modIdx,
                                            adapterSampleKey, intraRow);
                                } catch (Exception ex) {
                                    Log.w(TAG, "LteBW: read failed: " + ex);
                                }
                            }
                        }

                        // ---- Fast path: both views already injected (tags set) ----
                        Object bwTag  = row.getTag(BW_VIEW_TAG_KEY);
                        Object snrTag = row.getTag(SNR_VIEW_TAG_KEY);
                        if (bwTag instanceof TextView && snrTag instanceof View) {
                            TextView bwView  = (TextView) bwTag;
                            View     snrView = (View) snrTag;
                            String bwDisplay = (bwText != null) ? bwText : "-";
                            bwView.setText(bwDisplay);
                            if (bwText != null) {
                                bwView.setTextColor(0xFFFFFFFF);
                            } else {
                                if (row.getChildCount() > 3
                                        && row.getChildAt(3) instanceof TextView) {
                                    bwView.setTextColor(
                                            ((TextView) row.getChildAt(3)).getTextColors());
                                }
                            }
                            applySnrColor(snrView, sinrRaw, intraRow);
                            return;
                        }

                        // ---- First injection — must have exactly 6 children ----
                        if (row.getChildCount() != 6) return;

                        float density = rowView.getContext().getResources()
                                .getDisplayMetrics().density;

                        // Inject BW (plain TextView) at index 2.
                        TextView bandView = (TextView) row.getChildAt(1);
                        TextView bwView   = new TextView(rowView.getContext());
                        bwView.setText((bwText != null) ? bwText : "-");
                        bwView.setGravity(android.view.Gravity.CENTER);
                        bwView.setMaxLines(1);
                        bwView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                                bandView.getTextSize());
                        bwView.setTypeface(bandView.getTypeface());
                        if (bwText != null) {
                            bwView.setTextColor(0xFFFFFFFF);
                        } else {
                            bwView.setTextColor(bandView.getTextColors());
                        }
                        LinearLayout.LayoutParams bwLp = new LinearLayout.LayoutParams(
                                0, (int) (21 * density));
                        bwLp.weight = WEIGHTS[2];
                        bwView.setLayoutParams(bwLp);
                        row.addView(bwView, 2);

                        // Inject SNR (ProgressTextView) at end.
                        // After BW insertion RSRQ is now at index 6.
                        TextView rsrqView = (row.getChildCount() > 6
                                && row.getChildAt(6) instanceof TextView)
                                ? (TextView) row.getChildAt(6) : null;
                        View snrView = createSnrView(rowView.getContext(), rsrqView, density);
                        LinearLayout.LayoutParams snrLp = new LinearLayout.LayoutParams(
                                0, (int) (21 * density));
                        snrLp.weight = WEIGHTS[7];
                        snrView.setLayoutParams(snrLp);
                        row.addView(snrView);

                        applyWeights(row);

                        // Cache view references — intraRow resolved fresh on every getView.
                        row.setTag(BW_VIEW_TAG_KEY, bwView);
                        row.setTag(SNR_VIEW_TAG_KEY, snrView);

                        applySnrColor(snrView, sinrRaw, intraRow);
                    }
                });
        if (registered) Log.i(TAG, "LteBandwidthColumnHook: installed (merged BW+SNR)");
    }

    // -----------------------------------------------------------------------
    // Hook: y00.E (onCreateView) — inject both "BW" and "SNR" header labels
    // -----------------------------------------------------------------------
    private void hookOnCreateView() {
        try {
            Class<?> fragClass = Nsg.load(loader, LTE_FRAGMENT);
            if (fragClass == null) return;
            Method   iMethod   = fragClass.getDeclaredMethod("E",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result       = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View   fragmentView = (View) result;
                    if (fragmentView == null) return result;
                    ListView listView = (ListView) fragmentView.findViewById(android.R.id.list);
                    if (listView == null) return result;
                    listView.post(() -> injectHeaders(listView));
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "LteBWHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectHeaders(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL) continue;
                if (TAG_HEADER_INJECTED.equals(ll.getTag(HEADER_TAG_KEY))) return;
                if (ll.getChildCount() != 6) continue;

                // Inject "BW" label at index 2.
                TextView bwLabel = new TextView(listView.getContext());
                bwLabel.setText("BW");
                bwLabel.setGravity(android.view.Gravity.CENTER);
                bwLabel.setMaxLines(1);
                bwLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams bwLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                bwLp.weight = WEIGHTS[2];
                bwLabel.setLayoutParams(bwLp);
                ll.addView(bwLabel, 2);

                // Inject "SNR" label at end.
                TextView snrLabel = new TextView(listView.getContext());
                snrLabel.setText("SNR");
                snrLabel.setGravity(android.view.Gravity.CENTER);
                snrLabel.setMaxLines(1);
                snrLabel.setTextColor(resolveColor(listView.getContext(),
                        android.R.attr.textColorTertiary));
                LinearLayout.LayoutParams snrLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT);
                snrLp.weight = WEIGHTS[7];
                snrLabel.setLayoutParams(snrLp);
                ll.addView(snrLabel);

                applyWeights(ll);

                ll.setTag(HEADER_TAG_KEY, TAG_HEADER_INJECTED);
                return;
            }
            Log.w(TAG, "LteBWHook: injectHeaders: header row not found ("
                    + listView.getChildCount() + " children)");
        } catch (Exception e) {
            Log.w(TAG, "LteBWHook: injectHeaders failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // SINR color coding — mirrors NSG's nc.j/nc.k
    // -----------------------------------------------------------------------

    /**
     * Apply LegendManager color coding to the SNR ProgressTextView, or fall
     * back to plain text if color coding is unavailable.
     *
     * @param snrView  the SNR column view (ProgressTextView if available)
     * @param sinrRaw  raw SINR value (Float), or null if no data
     * @param intraRow 0=PCell, 1..7=SCell1..7
     */
    private void applySnrColor(View snrView, Float sinrRaw, int intraRow) {
        if (!(snrView instanceof TextView)) return;
        TextView tv = (TextView) snrView;

        if (sinrRaw == null) {
            // No data: clear the colored bar and show "-".
            if (ptvClass != null && ptvClass.isInstance(snrView) && ptvFieldJ != null) {
                try {
                    ptvFieldJ.setBoolean(snrView, false);
                    snrView.invalidate();
                } catch (Exception ignored) {}
            }
            tv.setText("-");
            return;
        }

        if (sinrColorReady && sinrAttrs != null && intraRow < sinrAttrs.length
                && ptvClass != null && ptvClass.isInstance(snrView)) {
            try {
                Object bVar     = sinrAttrs[intraRow];
                double doubleVal = sinrRaw.doubleValue();

                // Clear show-bar flag and invalidate (from decompiled j method).
                ptvFieldJ.setBoolean(snrView, false);
                snrView.invalidate();

                // Get legend progress and color (singleton cached after first read).
                Object legend = legendSingletonCache;
                if (legend == null) {
                    legend = legendSingleton.get(null);
                    if (legend != null) legendSingletonCache = legend;
                }
                if (legend != null) {
                    float    progress = (float) legendMethodC.invoke(legend, bVar, doubleVal);
                    Integer  color    = (Integer) legendMethodA.invoke(legend, bVar, doubleVal);
                    if (color != null) {
                        ptvMethodH.invoke(snrView, color, progress);
                    } else if (ptvSetProgress != null) {
                        ptvSetProgress.invoke(snrView, progress);
                    }
                }

                // Set formatted text via l8.c(Object).
                tv.setText(formatSinrValue(bVar, sinrRaw));
                return;
            } catch (Exception e) {
                Log.w(TAG, "LteBWHook: applySnrColor failed, using plain text: " + e);
            }
        }

        // Fallback: plain text without color coding.
        tv.setText(String.format("%.1f", sinrRaw));
        tv.setTextColor(0xFFFFFFFF);
    }

    /**
     * Format a SINR value using l8.c(Object), falling back to
     * String.format("%.1f") if the reflective call fails.
     */
    private String formatSinrValue(Object bVar, Float value) {
        try {
            return (String) sysACMethod.invoke(bVar, (Object) value);
        } catch (Exception e) {
            return String.format("%.1f", value);
        }
    }

    /**
     * Create the SNR column view — a ProgressTextView if reflection is
     * available, or a plain TextView as fallback.
     */
    private View createSnrView(Context ctx, TextView styleSource, float density) {
        if (ptvCtor != null) {
            try {
                View ptv = (View) ptvCtor.newInstance(ctx, (AttributeSet) null);
                if (ptv instanceof TextView) {
                    TextView tv = (TextView) ptv;
                    tv.setGravity(android.view.Gravity.CENTER);
                    tv.setMaxLines(1);
                    if (styleSource != null) {
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                                styleSource.getTextSize());
                        tv.setTypeface(styleSource.getTypeface());
                        tv.setTextColor(styleSource.getTextColors());
                    }
                    tv.setText("-");
                }
                return ptv;
            } catch (Exception e) {
                Log.w(TAG, "LteBWHook: createSnrView: ProgressTextView creation failed: " + e);
            }
        }
        // Fallback: plain TextView.
        TextView tv = new TextView(ctx);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setMaxLines(1);
        if (styleSource != null) {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    styleSource.getTextSize());
            tv.setTypeface(styleSource.getTypeface());
            tv.setTextColor(styleSource.getTextColors());
        }
        tv.setText("-");
        return tv;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private LinearLayout findHorizontalRow(View v) {
        if (!(v instanceof LinearLayout)) return null;
        LinearLayout ll = (LinearLayout) v;
        if (ll.getOrientation() == LinearLayout.HORIZONTAL
                && (ll.getChildCount() == 6 || ll.getChildCount() == 8)) return ll;
        if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > 0) {
            View first = ll.getChildAt(0);
            if (first instanceof LinearLayout) {
                LinearLayout inner = (LinearLayout) first;
                if (inner.getOrientation() == LinearLayout.HORIZONTAL
                        && (inner.getChildCount() == 6 || inner.getChildCount() == 8))
                    return inner;
            }
        }
        return null;
    }

    private void applyWeights(LinearLayout row) {
        for (int i = 0; i < row.getChildCount() && i < WEIGHTS.length; i++) {
            LinearLayout.LayoutParams clp =
                    (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            clp.weight = WEIGHTS[i];
            row.getChildAt(i).setLayoutParams(clp);
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
