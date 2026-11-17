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
 * Hooks gb0 (NRSACellsFragment) to add two columns to the NR-SA cell table:
 *   - ARFCN  — inserted at column index 1 (before PCI), per-row value
 *   - BW     — inserted at column index 3 (after PCI, before Beam), serving rows only
 *
 * Also conditionally hides the sub-row (child[1] of the outer vertical LinearLayout
 * per row): kept visible when the cell-DB CSV has data for the cell (tvRowCellName/
 * tvRowCellID non-empty), hidden when empty — reclaiming 22dp per row when no data.
 *
 * Final top-row column order (8 columns):
 *   [0] Serving  [1] ARFCN  [2] PCI  [3] BW  [4] Beam  [5] RSRP  [6] RSRQ  [7] SINR
 *
 * Data path (same as LteBandwidthColumnHook):
 *   Workspace.j → singleton; .a → moduleIndex; .c → DataSource
 *   DataSource.getProperty(key, moduleIndex) → Property
 *   Property.b(sampleKey) → Iterator; .key() → long; .value() → Object
 *
 * v4.8.14 targets:
 *   gb0  NRSACellsFragment (E = onCreateView), extends cq
 *   cq   abstract cell-fragment base; b(DataSource,long,short,Object) = data callback
 *   a10  cell adapter (R8-merged, switch field f); getView, d = bq[] sources,
 *        f(int position) → Pair<bq bucket, Integer intraRow>
 *   bq   adapter bucket; d(int column,int row) (inherited from dq) → formatted cell text
 *   sd   cell-DB manager; e(String tech) → loaded DB or null
 *   db0  NR-SA CA Matrix DL page; M = page shown, k0 = grid rebuild
 *
 * Adapter row→source mapping:
 *   pair.first == sources[0]  → serving bucket (PCell + SCells)
 *   pair.second               → intraRow: 0=PCell, 1=SCell1, 2=SCell2 …
 *
 * Keys:
 *   ARFCN serving    : NR5G::Cell_Measurements::NR_Cells_ARFCN           (Integer[], index=intraRow, covers ALL cells incl. non-metrics SCells)
 *   ARFCN detected   : NR5G::Detected_Cells::NR_DetectedCells_ARFCN      (Integer[], index=intraRow)
 *   BW PCell         : NR5G::Serving_Cell::NR_Bandwidth_DL               (direct MHz int)
 *   BW SCell N       : NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL  (index N-1)
 *
 * NOTE: Do NOT use NR5G::Serving_Cell::NR_ARFCN_SSB / NR_SCell_ARFCN_SSB for ARFCN.
 * Those are the metrics-page (NRNSANRCC) keys which only cover SCells visible
 * on the NR-SA metrics tab. Aggregated SCells not on that page will show "-".
 *
 * Additionally (own toggle, default OFF) the same getView hook colours the PCI of
 * detected/neighbour rows turquoise when that row's PCI and ARFCN both match the
 * serving primary cell. In multi-beam deployments one sector cell appears as
 * several neighbour rows sharing PCI+ARFCN, so this makes same-site beams easy to
 * tell apart from other sectors. The colour equals SCELL_COLORS[1] (SCell 2) on the
 * NR-SA CA Matrix DL page. Works in any RRC state. Controlled by the
 * "NSGMod: Highlight same-site NR neighbours" toggle.
 */
public class NrSaCellColumnsHook {

    private static final String TAG              = "NSGBandHook";
    private static final String TAG_COLS_INJECTED = "nsg_nrsa_cols_injected";

    private static final String CELL_ADAPTER  = "a10"; // cell adapter (getView)
    private static final String CELL_BUCKET   = "bq";  // adapter bucket
    private static final String CELL_BASE     = "cq";  // cell fragment base (data callback)
    private static final String CELL_FRAGMENT = "gb0"; // NRSACellsFragment
    private static final String CELL_DB       = "sd";  // cell-DB manager (name unchanged)

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // Workspace / DataSource reflection (shared pattern with LTE hook)
    private Field  wsSingleton;
    private Field  wsModuleIndex;
    private Field  wsDataSource;

    private Method dsGetProperty;
    private Method propIterMethod;
    private Method iterEndMethod;
    private Method iterKeyMethod;   // Property$Iterator.key() → long
    private Method iterValueMethod;

    // Adapter reflection — a10
    private Field  sourcesField; // a10.d: bq[] sources
    private Method gMethod;      // a10.f(int) → Pair<bq, Integer>
    private Method bucketTextMethod; // bq.d(int,int) (declared in dq) → formatted cell text

    private boolean ready = false;
    private static volatile boolean nrCellDbLoaded = false;
    private static boolean cellDbHookInstalled = false;
    /** Sample key captured from the cq.b(DataSource, long, …) data callback before getView() fires. */
    private volatile long lastNrSaSampleKey = 0L;

    public NrSaCellColumnsHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> wsClass   = Nsg.load(loader, Nsg.WORKSPACE);
            Class<?> dsClass   = Nsg.load(loader, Nsg.DATA_SOURCE);
            Class<?> propClass = Nsg.load(loader, Nsg.PROPERTY);
            Class<?> iterClass = Nsg.load(loader, Nsg.PROPERTY_ITER);
            Class<?> adapterClass = Nsg.load(loader, CELL_ADAPTER);
            Class<?> bucketClass  = Nsg.load(loader, CELL_BUCKET);
            if (wsClass == null || dsClass == null || propClass == null || iterClass == null
                    || adapterClass == null || bucketClass == null) {
                Log.i(TAG, "NrSaCellColumnsHook: essential class missing, skipping");
                return;
            }

            wsSingleton    = wsClass.getField("j");   // Workspace.j: singleton instance
            wsModuleIndex  = wsClass.getField("a");   // Workspace.a: module index (short)
            wsDataSource   = wsClass.getField("c");   // Workspace.c: DataSource

            dsGetProperty  = dsClass.getMethod("getProperty", String.class, int.class);
            propIterMethod = propClass.getMethod("b", long.class);
            iterEndMethod  = iterClass.getMethod("end");
            iterKeyMethod  = iterClass.getMethod("key");
            iterValueMethod = iterClass.getMethod("value");

            sourcesField = adapterClass.getField("d");             // a10.d: bq[] sources
            gMethod      = adapterClass.getMethod("f", int.class); // a10.f: position → Pair<bq, intraRow>
            bucketTextMethod = bucketClass.getMethod("d", int.class, int.class); // dq.d: formatted cell text

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCellColumnsHook: skipping install — reflection not ready");
            return;
        }
        hookCellDbStatus();
        hookDataCallback();
        hookGetView();
        hookOnCreateView();
        hookCaMatrixShown();
        Log.i(TAG, "NrSaCellColumnsHook: installed");
    }

    private void hookCellDbStatus() {
        if (cellDbHookInstalled) return;
        try {
            Class<?> cls = Nsg.load(loader, CELL_DB);
            if (cls == null) return;
            Method dMethod = cls.getMethod("e", String.class); // sd.e: load cell DB for tech
            xposed.hook(dMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    String tech = (String) chain.getArg(0);
                    if ("NR5G".equals(tech)) {
                        nrCellDbLoaded = (result != null);
                    }
                    return result;
                }
            });
            cellDbHookInstalled = true;
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook: hookCellDbStatus failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Data-callback hook — captures the sample key used to fill the buffer
    // -----------------------------------------------------------------------

    /**
     * Hook cq.b(DataSource, long, short, Object) — the Workspace callback
     * invoked when new data arrives.  Captures the sample key (arg index 1) into lastNrSaSampleKey.
     *
     * This runs BEFORE notifyDataSetChanged() / getView(), so by the time getView()
     * fires the field already holds the correct sample key for this render cycle.
     *
     * All cq subclasses share this callback; capturing from any of them is fine
     * because they all receive the same monotonically-increasing sample key stream.
     */
    private void hookDataCallback() {
        try {
            Class<?> dClass  = Nsg.load(loader, CELL_BASE);
            Class<?> dsClass = Nsg.load(loader, Nsg.DATA_SOURCE);
            if (dClass == null || dsClass == null) {
                Log.i(TAG, "NrSaCellColumnsHook: cq or DataSource missing, skipping data callback hook");
                return;
            }
            Method   bMethod = dClass.getDeclaredMethod("b",
                    dsClass, long.class, short.class, Object.class);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    long sk = (long) chain.getArg(1);
                    if (sk > 0) lastNrSaSampleKey = sk;
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookDataCallback failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Data reading
    // -----------------------------------------------------------------------

    /** Read ARFCN for a row.
     *
     * Uses the same Cell_Measurements keys that gb0 (NRSACellsFragment) itself registers:
     *   serving  → NR5G::Cell_Measurements::NR_Cells_ARFCN       (Integer[], index = intraRow)
     *   detected → NR5G::Detected_Cells::NR_DetectedCells_ARFCN  (Integer[], index = intraRow)
     *
     * Do NOT use NR5G::Serving_Cell::NR_ARFCN_SSB or NR_SCell_ARFCN_SSB here.
     * Those are the metrics-page (Serving_Cell namespace) keys used by NRNSANRCC.
     * SCells that are aggregated but not shown on the metrics page have no entry there,
     * so they would show "-". The Cell_Measurements array covers all cells in the table.
     *
     * @param isServing true = serving bucket (PCell/SCells); false = detected/neighbour bucket
     * @param intraRow  bucket-relative row index (0-based; 0=PCell, 1=SCell1, 2=SCell2 …)
     */
    private String readArfcn(boolean isServing, int intraRow, long queryTime) {
        if (!isServing) {
            return readPropertyWithIndex(
                    "NR5G::Detected_Cells::NR_DetectedCells_ARFCN", intraRow, queryTime);
        }
        // Both PCell (intraRow=0) and SCells (intraRow≥1) are in the same flat array.
        return readPropertyWithIndex(
                "NR5G::Cell_Measurements::NR_Cells_ARFCN", intraRow, queryTime);
    }

    /**
     * Read DL bandwidth for a serving row.
     * @param intraRow 0=PCell, ≥1=SCell (1-based index: SCell1 = intraRow 1)
     */
    private String readBandwidth(int intraRow, long queryTime) {
        String key;
        if (intraRow == 0) {
            key = "NR5G::Serving_Cell::NR_Bandwidth_DL";
        } else {
            key = "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL";
        }
        return readPropertyWithIndex(key, intraRow > 0 ? intraRow - 1 : -1, queryTime);
    }

    private String readPropertyWithIndex(String key, int scellIndex, long queryTime) {
        try {
            Object ws = wsSingleton.get(null);
            if (ws == null) { Log.w(TAG, "readProp: Workspace null"); return null; }
            int    moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
            Object dataSource  = wsDataSource.get(ws);
            if (dataSource == null) { Log.w(TAG, "readProp: DataSource null"); return null; }

            Object property = dsGetProperty.invoke(dataSource, key, moduleIndex);
            if (property == null) return null;

            Object iterator = propIterMethod.invoke(property, queryTime);
            if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) {
                return null;
            }

            // Use iter.value() — matches NSG's own read pattern (dh0.a).
            // property.get(sk) returns null for array-type properties (e.g. SCell BW
            // Object[]) even though the iterator IS positioned at a valid sample key.
            long   sampleKey = (long) iterKeyMethod.invoke(iterator);
            Object value     = iterValueMethod.invoke(iterator);
            if (value == null) {
                // Prev-tick fallback: head entry not yet fully committed.
                iterator = propIterMethod.invoke(property, sampleKey - 1);
                if (iterator == null || (boolean) iterEndMethod.invoke(iterator)) return null;
                sampleKey = (long) iterKeyMethod.invoke(iterator);
                value = iterValueMethod.invoke(iterator);
                if (value == null) {
                    return null;
                }
            }

            String extracted = extractArrayValue(value, scellIndex);
            if (extracted == null && value != null) {
                // Array element is null — prev-tick fallback.
                Object prevIter = propIterMethod.invoke(property, sampleKey - 1);
                if (prevIter != null && !(boolean) iterEndMethod.invoke(prevIter)) {
                    Object prevVal = iterValueMethod.invoke(prevIter);
                    if (prevVal != null) {
                        extracted = extractArrayValue(prevVal, scellIndex);
                    }
                }
            }
            return extracted;
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook.readProperty(" + key + ") failed: " + e);
            return null;
        }
    }

    /** Extract a value from scalar or array Property values. */
    private String extractArrayValue(Object value, int idx) {
            // ARFCN property returns Integer[] (one value per cell); index by scellIndex
            if (value instanceof Integer[]) {
                Integer[] arr = (Integer[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length && arr[i] != null) return String.valueOf(arr[i]);
                return null;
            }
            if (value instanceof int[]) {
                int[] arr = (int[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length) return String.valueOf(arr[i]);
                return null;
            }
            // Object[] containing boxed Numbers (seen on some devices)
            if (value instanceof Object[]) {
                Object[] arr = (Object[]) value;
                int i = idx >= 0 ? idx : 0;
                if (i < arr.length && arr[i] instanceof Number)
                    return String.valueOf(((Number) arr[i]).intValue());
                return null;
            }
            if (value instanceof Integer) return String.valueOf((Integer) value);
            if (value instanceof Long)    return String.valueOf((Long) value);
            if (value instanceof Float)   return String.valueOf(((Float) value).intValue());
            if (value instanceof Double)  return String.valueOf(((Double) value).intValue());
            return value != null ? value.toString() : null;
    }

    // -----------------------------------------------------------------------
    // Same-site neighbour PCI highlight (NR-SA cell table)
    // -----------------------------------------------------------------------

    /**
     * Turquoise — identical to the colour NrSaCellColorHook assigns to the
     * second SCell on the SA CA Matrix DL page (SCELL_COLORS[1]).
     */
    private static final int SAME_SITE_PCI_COLOR = 0xFF00BCD4;

    /** Column indices inside a gb0 bucket (bq), see gb0's registration order. */
    private static final int COL_SERVING_TYPE = 0;
    private static final int COL_ARFCN        = 1;
    private static final int COL_PCI          = 2;

    /** Cached tvRowPCI_NR resource id per Resources instance. */
    private static final java.util.Map<android.content.res.Resources, Integer> PCI_ID_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static int pciViewId(android.content.res.Resources res) {
        Integer cached = PCI_ID_CACHE.get(res);
        if (cached != null) return cached;
        int id = res.getIdentifier("tvRowPCI_NR", "id", "com.qtrun.QuickTest");
        PCI_ID_CACHE.put(res, id);
        return id;
    }

    /**
     * True when the detected (neighbour) row at {@code intraRow} belongs to the
     * serving primary cell's site, i.e. its PCI and ARFCN both match entry 0 of
     * the serving bucket. With multiple SSB beams a single sector shows up as
     * several neighbour rows with the same PCI/ARFCN; highlighting them makes the
     * same-site beams distinguishable from other sectors. Applies in every RRC
     * state, including idle/inactive, as long as the table shows a primary cell.
     *
     * Both the reference and the row value are read from the adapter's own
     * buckets — the exact data snapshot NSG has just rendered — so the comparison
     * can never straddle two sample keys.
     */
    private boolean isSameSiteNeighbour(Object servingBucket, Object rowBucket,
                                        int intraRow) {
        if (bucketTextMethod == null) return false;
        if (servingBucket == null || rowBucket == null || servingBucket == rowBucket) {
            return false; // unknown bucket or serving row — keep NSG's own colour
        }
        // Entry 0 of the serving bucket is the PCell (ServingType 0 → "P").
        if (!"P".equals(bucketText(servingBucket, COL_SERVING_TYPE, 0))) return false;

        String servingPci   = bucketText(servingBucket, COL_PCI, 0);
        String servingArfcn = bucketText(servingBucket, COL_ARFCN, 0);
        if (!isUsable(servingPci) || !isUsable(servingArfcn)) return false;

        String rowPci   = bucketText(rowBucket, COL_PCI, intraRow);
        String rowArfcn = bucketText(rowBucket, COL_ARFCN, intraRow);
        return servingPci.equals(rowPci) && servingArfcn.equals(rowArfcn);
    }

    /**
     * Applies the highlight (when requested) and returns {@code result}
     * unchanged. Called at every exit of the getView post-processing so the
     * injected ARFCN/BW columns have already copied NSG's base PCI colour.
     */
    private static Object finishRow(boolean highlightPci, LinearLayout topRow, Object result) {
        if (!highlightPci) return result;
        try {
            int id = pciViewId(topRow.getResources());
            if (id == 0) return result;
            View pciView = topRow.findViewById(id);
            if (pciView instanceof TextView) {
                ((TextView) pciView).setTextColor(SAME_SITE_PCI_COLOR);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** Reads one formatted cell from a bucket; null on any failure. */
    private String bucketText(Object bucket, int column, int row) {
        if (bucketTextMethod == null || bucket == null) return null;
        try {
            Object value = bucketTextMethod.invoke(bucket, column, row);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isUsable(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !"-".equals(v);
    }

    // -----------------------------------------------------------------------
    // Hook 1: a10.getView — inject ARFCN + BW columns, conditionally hide sub-row
    // -----------------------------------------------------------------------

    private void hookGetView() {
        try {
            Class<?> adapterClass  = Nsg.load(loader, CELL_ADAPTER);
            if (adapterClass == null) {
                Log.i(TAG, "NrSaCellColumnsHook: a10 not available, skipping getView hook");
                return;
            }
            Method   getViewMethod = adapterClass.getMethod("getView",
                    int.class, View.class, ViewGroup.class);

            xposed.hook(getViewMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result   = chain.proceed();
                    boolean cellMods    = SettingsToggleHook.cellModsEnabled();
                    boolean sameSitePci = SettingsToggleHook.sameSitePciEnabled();
                    if (!cellMods && !sameSitePci) return result;
                    int    position = (int) chain.getArg(0);
                    View   rowView  = (View) result;
                    if (rowView == null) return result;

                    // Outer layout must be vertical with 2 children (top row + sub-row)
                    if (!(rowView instanceof LinearLayout)) return result;
                    LinearLayout outer = (LinearLayout) rowView;
                    if (outer.getOrientation() != LinearLayout.VERTICAL
                            || outer.getChildCount() < 2) return result;

                    // Top horizontal row (child[0])
                    View topChild = outer.getChildAt(0);
                    if (!(topChild instanceof LinearLayout)) return result;
                    LinearLayout topRow = (LinearLayout) topChild;
                    if (topRow.getOrientation() != LinearLayout.HORIZONTAL) return result;

                    // queryTime: use lastNrSaSampleKey captured from the cq.b() data
                    // callback (set before notifyDataSetChanged → getView).  Falls back
                    // to Long.MAX_VALUE on first render before any callback fires
                    // (live mode: MAX_VALUE = latest data = correct).
                    long    queryTime = lastNrSaSampleKey > 0 ? lastNrSaSampleKey : Long.MAX_VALUE;

                    // Resolve serving/intraRow once — shared by the optional same-site
                    // PCI highlight and the ARFCN/BW column injection below.
                    boolean isServing = false;
                    int     intraRow  = 0;
                    Object  rowBucket     = null;
                    Object  servingBucket = null;
                    try {
                        Object   adapter = chain.getThisObject();
                        Object[] sources = (Object[]) sourcesField.get(adapter);
                        android.util.Pair<?, ?> pair =
                                (android.util.Pair<?, ?>) gMethod.invoke(adapter, position);
                        if (pair != null && pair.second != null) {
                            intraRow = (int) pair.second;
                            if (pair.first != null && sources != null && sources.length > 0) {
                                isServing     = (pair.first == sources[0]);
                                rowBucket     = pair.first;
                                servingBucket = sources[0];
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "NrSaCellColumnsHook: g(position) failed: " + e);
                    }

                    // Decide whether this row belongs to the connected PCell's site.
                    // Applied at the end of the intercept so the injected ARFCN/BW
                    // columns (which copy the PCI colour list) keep NSG's base colour.
                    boolean highlightPci = false;
                    if (sameSitePci) {
                        try {
                            highlightPci = isSameSiteNeighbour(servingBucket, rowBucket,
                                    intraRow);
                        } catch (Throwable t) {
                            Log.w(TAG, "NrSaCellColumnsHook: same-site PCI check failed: " + t);
                        }
                    }

                    if (!cellMods) return finishRow(highlightPci, topRow, result);

                    // Match NR-NSA row height: container 22dp, children 21dp
                    android.content.Context ctx = rowView.getContext();
                    float density = ctx.getResources().getDisplayMetrics().density;
                    int   rowH    = (int) (21 * density + 0.5f);
                    int   topH    = (int) (22 * density + 0.5f);
                    ViewGroup.LayoutParams topRowLp = topRow.getLayoutParams();
                    if (topRowLp != null) {
                        topRowLp.height = topH;
                        topRow.setLayoutParams(topRowLp);
                    }

                    View subRow = outer.getChildAt(1);
                    if (subRow instanceof ViewGroup) {
                        ViewGroup subRowVg = (ViewGroup) subRow;
                        boolean hasData = false;
                        if (isNrCellDbLoaded()) {
                            String pkg = "com.qtrun.QuickTest";
                            int cellNameId = subRowVg.getResources().getIdentifier("tvRowCellName", "id", pkg);
                            int cellIdId = subRowVg.getResources().getIdentifier("tvRowCellID", "id", pkg);
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
                            int rfidId = subRowVg.getResources().getIdentifier("tvRowRFID", "id", "com.qtrun.QuickTest");
                            if (rfidId != 0) {
                                View rfid = subRowVg.findViewById(rfidId);
                                if (rfid != null) rfid.setVisibility(View.GONE);
                            }
                        } else {
                            subRow.setVisibility(View.GONE);
                        }
                    } else {
                        subRow.setVisibility(View.GONE);
                    }

                    // Read values using adapter-aligned sample key (not Workspace.g).
                    String arfcnText = "-";
                    String arfcn = readArfcn(isServing, intraRow, queryTime);
                    if (arfcn != null) arfcnText = arfcn;

                    String bwText = "-";
                    if (isServing) {
                        String bw = readBandwidth(intraRow, queryTime);
                        if (bw != null) bwText = bw;
                    }

                    // Already injected — refresh text and color
                    // (NSG's getView has already set pciView's color correctly for this row)
                    if (TAG_COLS_INJECTED.equals(topRow.getTag())) {
                        if (topRow.getChildCount() >= 8) {
                            // After injection layout is: [0]Serving [1]ARFCN [2]PCI [3]BW ...
                            TextView pciViewR  = (TextView) topRow.getChildAt(2);
                            TextView tvArfcn   = (TextView) topRow.getChildAt(1);
                            TextView tvBw      = (TextView) topRow.getChildAt(3);
                            android.content.res.ColorStateList colors = pciViewR.getTextColors();
                            tvArfcn.setText(arfcnText);
                            tvArfcn.setTextColor(colors);
                            tvBw.setText(bwText);
                            tvBw.setTextColor(colors);
                        }
                        return finishRow(highlightPci, topRow, result);
                    }

                    // First time — must have exactly 6 children
                    if (topRow.getChildCount() != 6) {
                        return finishRow(highlightPci, topRow, result);
                    }

                    // Copy style from PCI column (child[1]) for new text views
                    TextView pciView = (TextView) topRow.getChildAt(1);

                    // Resize all 6 pre-existing NSG children from 26dp to 21dp to match NR-NSA
                    for (int i = 0; i < topRow.getChildCount(); i++) {
                        ViewGroup.LayoutParams lp = topRow.getChildAt(i).getLayoutParams();
                        if (lp != null) {
                            lp.height = rowH;
                            topRow.getChildAt(i).setLayoutParams(lp);
                        }
                    }

                    // --- ARFCN TextView ---
                    TextView tvArfcn = new TextView(ctx);
                    tvArfcn.setText(arfcnText);
                    tvArfcn.setGravity(android.view.Gravity.CENTER);
                    tvArfcn.setMaxLines(1);
                    tvArfcn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            pciView.getTextSize());
                    tvArfcn.setTypeface(pciView.getTypeface());
                    tvArfcn.setTextColor(pciView.getTextColors());
                    LinearLayout.LayoutParams lpArfcn = new LinearLayout.LayoutParams(0, rowH);
                    lpArfcn.weight = 0.18f;
                    lpArfcn.gravity = android.view.Gravity.CENTER_VERTICAL;
                    tvArfcn.setLayoutParams(lpArfcn);

                    // --- BW TextView ---
                    TextView tvBw = new TextView(ctx);
                    tvBw.setText(bwText);
                    tvBw.setGravity(android.view.Gravity.CENTER);
                    tvBw.setMaxLines(1);
                    tvBw.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                            pciView.getTextSize());
                    tvBw.setTypeface(pciView.getTypeface());
                    tvBw.setTextColor(pciView.getTextColors());
                    LinearLayout.LayoutParams lpBw = new LinearLayout.LayoutParams(0, rowH);
                    lpBw.weight = 0.10f;
                    lpBw.gravity = android.view.Gravity.CENTER_VERTICAL;
                    tvBw.setLayoutParams(lpBw);

                    // Insert: ARFCN at index 1 (before PCI), BW at index 3 (after PCI)
                    topRow.addView(tvArfcn, 1);
                    topRow.addView(tvBw, 3);

                    // Redistribute weights across all 8 columns:
                    // [Serving, ARFCN, PCI, BW, Beam, RSRP, RSRQ, SINR]
                    float[] weights = {0.03f, 0.18f, 0.10f, 0.09f, 0.10f, 0.17f, 0.17f, 0.16f};
                    for (int i = 0; i < topRow.getChildCount() && i < weights.length; i++) {
                        LinearLayout.LayoutParams lp =
                                (LinearLayout.LayoutParams) topRow.getChildAt(i).getLayoutParams();
                        lp.weight = weights[i];
                        topRow.getChildAt(i).setLayoutParams(lp);
                    }

                    topRow.setTag(TAG_COLS_INJECTED);
                    return finishRow(highlightPci, topRow, result);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookGetView failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook 2: gb0.E (onCreateView) — inject header labels after layout posted
    // -----------------------------------------------------------------------

    private void hookOnCreateView() {
        try {
            Class<?> fragClass = Nsg.load(loader, CELL_FRAGMENT);
            if (fragClass == null) {
                Log.i(TAG, "NrSaCellColumnsHook: gb0 not available, skipping onCreateView hook");
                return;
            }
            // gb0.E: onCreateView
            Method   iMethod   = fragClass.getMethod("E",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);

            xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result       = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    View   fragmentView = (View) result;
                    if (fragmentView == null) return result;

                    int listId = fragmentView.getContext().getResources()
                            .getIdentifier("list_1", "id",
                                    fragmentView.getContext().getPackageName());
                    if (listId == 0) {
                        Log.w(TAG, "NrSaCellColumnsHook: list_1 id not found");
                        return result;
                    }
                    ListView listView = (ListView) fragmentView.findViewById(listId);
                    if (listView == null) {
                        Log.w(TAG, "NrSaCellColumnsHook: ListView not found");
                        return result;
                    }

                    listView.post(() -> injectHeaders(listView));
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCellColumnsHook: hookOnCreateView failed: " + e);
        }
    }

    private void injectHeaders(ListView listView) {
        try {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (!(child instanceof LinearLayout)) continue;
                LinearLayout ll = (LinearLayout) child;
                if (ll.getOrientation() != LinearLayout.HORIZONTAL) continue;
                if (ll.getChildCount() != 6) continue;
                if (TAG_COLS_INJECTED.equals(ll.getTag())) return;

                android.content.Context ctx = listView.getContext();

                // ARFCN header at index 1
                TextView tvArfcnHdr = makeHeaderLabel(ctx, "ARFCN", 0.18f);
                ll.addView(tvArfcnHdr, 1);

                // BW header at index 3
                TextView tvBwHdr = makeHeaderLabel(ctx, "BW", 0.10f);
                ll.addView(tvBwHdr, 3);

                // Redistribute header weights to match row weights
                float[] weights = {0.03f, 0.18f, 0.10f, 0.09f, 0.10f, 0.17f, 0.17f, 0.16f};
                for (int j = 0; j < ll.getChildCount() && j < weights.length; j++) {
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) ll.getChildAt(j).getLayoutParams();
                    lp.weight = weights[j];
                    ll.getChildAt(j).setLayoutParams(lp);
                }

                ll.setTag(TAG_COLS_INJECTED);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCellColumnsHook: injectHeaders failed: " + e);
        }
    }

    private TextView makeHeaderLabel(android.content.Context ctx, String text, float weight) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setTextColor(resolveColor(ctx, android.R.attr.textColorTertiary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.weight = weight;
        tv.setLayoutParams(lp);
        return tv;
    }

    private boolean isNrCellDbLoaded() {
        return nrCellDbLoaded;
    }

    private int resolveColor(android.content.Context ctx, int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = ctx.obtainStyledAttributes(attrs);
        int color = ta.getColor(0, 0xFFAAAAAA);
        ta.recycle();
        return color;
    }

    // -----------------------------------------------------------------------
    // Hook 3: db0.M() — force k0() rebuild when NR-SA CA Matrix becomes visible
    // -----------------------------------------------------------------------

    private void hookCaMatrixShown() {
        try {
            Class<?> cls    = Nsg.load(loader, Nsg.PAGE_SA_CA_MATRIX_DL);
            if (cls == null) {
                Log.i(TAG, "NrSaCellColumnsHook: db0 not available, skipping db0.M hook");
                return;
            }
            Method   qMeth  = cls.getDeclaredMethod("M");  // db0.M: page shown
            qMeth.setAccessible(true);
            Method   o0Meth = cls.getDeclaredMethod("k0"); // db0.k0: rebuild grid
            o0Meth.setAccessible(true);

            xposed.hook(qMeth).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result   = chain.proceed();
                    if (!SettingsToggleHook.cellModsEnabled()) return result;
                    Object fragment = chain.getThisObject();
                    try {
                        o0Meth.invoke(fragment);
                    } catch (Throwable t) {
                        Log.w(TAG, "NrSaCellColumnsHook: db0.k0() invoke failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "NrSaCellColumnsHook: hookCaMatrixShown failed: " + t);
        }
    }
}
