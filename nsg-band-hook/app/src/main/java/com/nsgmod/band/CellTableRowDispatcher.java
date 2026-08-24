package com.nsgmod.band;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Single-interceptor dispatcher for the homepage cell-table adapter getView
 * methods (logical a8.f$a LTE and a8.h$a NR-NSA).
 *
 * On qtrun v4.8.9 both logical classes collapse to the same runtime class
 * (nc), so all four features (BandColumnHook, NrNsaBandwidthColumnHook,
 * LteBandwidthColumnHook, CellRowHeightHook) previously installed FOUR
 * separate interceptors on the SAME runtime getView method. Each interceptor
 * independently re-resolved the adapter type, the sources array, the
 * h(position) pair and the f5509c sample key per row.
 *
 * This dispatcher installs exactly ONE interceptor per distinct runtime
 * Method (deduped by Method identity), resolves the shared per-call row
 * context once, and invokes each feature's row processor in the exact
 * effective order the four interceptors ran before:
 *
 *   - libxposed invokes same-priority interceptors in registration order
 *     (first registered = outermost), so POST-proceed bodies run in REVERSE
 *     registration order.
 *   - MainHook registers NrNsaBandwidthColumnHook, BandColumnHook,
 *     LteBandwidthColumnHook, CellRowHeightHook (in that order), so today the
 *     effective order on a runtime getView method is:
 *       pre : CellRowHeightHook checks (toggle + adapter type)
 *       post: CellRowHeightHook -> LteBandwidthColumnHook -> BandColumnHook
 *             -> NrNsaBandwidthColumnHook   (per-method subsets on gplay)
 *   - The dispatcher therefore runs beforeProceed() in ascending registration
 *     order and afterProceed() in descending registration order.
 *
 * Error isolation matches libxposed's PROTECTIVE exception mode: an exception
 * in one processor's beforeProceed drops that processor for this call; an
 * exception in afterProceed is logged (Log.e) and the remaining processors
 * still run, with the original proceed() result returned unchanged.
 */
final class CellTableRowDispatcher {

    private static final String TAG = "NSGBandHook";

    private CellTableRowDispatcher() {
    }

    // -----------------------------------------------------------------------
    // Row processor interface
    // -----------------------------------------------------------------------

    interface RowProcessor {
        /** Pre-proceed phase, runs BEFORE the original getView (registration order). */
        default void beforeProceed(RowContext ctx) throws Throwable {
        }

        /** Post-proceed phase, runs AFTER the original getView (reverse registration order). */
        default void afterProceed(RowContext ctx, Object result) throws Throwable {
        }
    }

    // -----------------------------------------------------------------------
    // Per-call row context — resolved lazily, shared by all processors.
    // Resolution semantics mirror the legacy per-hook code exactly:
    //   * adapter type read failure / missing field -> adapterType stays null
    //     -> every feature runs (legacy: catch ignored / guard skipped).
    //   * pair resolution failure -> defaults (intraRow=position, isSource0=false,
    //     sampleKey=-1), one Log.w (legacy logged once per hook).
    // -----------------------------------------------------------------------

    static final class RowContext {
        final Object adapter;
        final int    position;

        private boolean typeResolved;
        private boolean pairResolved;
        private boolean dsResolved;

        /** null => adapter-type field missing or read failed => NO type filtering. */
        Integer  adapterType;
        Object[] sources;
        boolean  isSource0;
        int      intraRow;
        long     sampleKey = -1;

        /** Workspace data source / module index — resolved once per getView call
         *  and shared across all RowProcessors so they don't each independently
         *  reflectively read the Workspace singleton. */
        Object dataSource;
        int    moduleIndex = -1;

        RowContext(Object adapter, int position) {
            this.adapter  = adapter;
            this.position = position;
            this.intraRow = position;
        }

        /** Legacy type-check parity: passes when the field is missing/unreadable. */
        boolean isAdapterType(int expected) {
            resolveType();
            return adapterType == null || adapterType == expected;
        }

        int position() {
            return position;
        }

        int intraRow() {
            resolvePair();
            return intraRow;
        }

        boolean isSource0() {
            resolvePair();
            return isSource0;
        }

        /** Raw f5509c sample key, or -1 when unavailable (callers map <= 0 to MAX_VALUE). */
        long sampleKey() {
            resolvePair();
            return sampleKey;
        }

        /** Shared data source for this row — resolved once, reused by all processors. */
        Object dataSource() {
            resolveDs();
            return dataSource;
        }

        /** Shared module index for this row — resolved once, reused by all processors. */
        int moduleIndex() {
            resolveDs();
            return moduleIndex;
        }

        private void resolveType() {
            if (typeResolved) return;
            typeResolved = true;
            Handles h = handles;
            if (h != null && h.adapterTypeField != null) {
                try {
                    adapterType = h.adapterTypeField.getInt(adapter);
                } catch (Exception ignored) {
                    // legacy: catch (Exception ignored) {} -> feature stays active
                }
            }
        }

        private void resolvePair() {
            if (pairResolved) return;
            pairResolved = true;
            Handles h = handles;
            if (h == null || !h.pairReady) return;
            try {
                sources = (Object[]) h.eField.get(adapter);
                android.util.Pair<?, ?> pair =
                        (android.util.Pair<?, ?>) h.hMethod.invoke(adapter, position);
                if (pair != null && pair.second != null) {
                    intraRow  = (int) pair.second;
                    isSource0 = (pair.first != null && sources != null
                            && sources.length > 0 && pair.first == sources[0]);
                }
                if (h.f5509cField != null && sources != null && sources.length > 0) {
                    sampleKey = h.f5509cField.getLong(sources[0]);
                }
            } catch (Exception e) {
                Log.w(TAG, "CellTableRowDispatcher: h(position) failed: " + e);
            }
        }

        private void resolveDs() {
            if (dsResolved) return;
            dsResolved = true;
            Handles h = handles;
            if (h == null || h.wsSingleton == null) return;
            try {
                Object ws = h.wsSingleton.get(null);
                if (ws != null) {
                    moduleIndex = ((Number) h.wsModuleIndex.get(ws)).intValue();
                    dataSource  = h.wsDataSource.get(ws);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shared reflection handles (one-time resolution per target ClassLoader)
    // -----------------------------------------------------------------------

    private static final class Handles {
        Field  eField;           // a8.b$b.e — Object[] sources array
        Method hMethod;          // a8.b$b.h(int) — Pair<source, intraRow>
        Field  f5509cField;      // k8.c.c — adapter's current data sample key
        Field  adapterTypeField; // a8.b$b.f — adapter type (4=LTE, 6=NR-NSA)
        boolean pairReady;

        // Workspace singleton / module index / data source — resolved once so
        // every RowProcessor can share them via RowContext instead of each
        // independently doing 3 reflective field reads per row per frame.
        Field wsSingleton;   // com.qtrun.sys.Workspace — static singleton field
        Field wsModuleIndex; // com.qtrun.sys.Workspace — module index field
        Field wsDataSource;  // com.qtrun.sys.Workspace — data source field

        static Handles resolve(ClassLoader loader) {
            Handles h = new Handles();
            try {
                Class<?> bbClass  = ClassMapping.loadClass("a8.b$b", loader);
                Class<?> k8cClass = ClassMapping.loadClass("k8.c", loader);
                if (bbClass == null || k8cClass == null) {
                    Log.i(TAG, "CellTableRowDispatcher: adapter base class missing, degrading");
                    return h;
                }
                try {
                    Field f = bbClass.getDeclaredField("f");
                    if (f.getType() == int.class) {
                        h.adapterTypeField = f;
                        h.adapterTypeField.setAccessible(true);
                    }
                } catch (NoSuchFieldException ignored) {
                }
                try {
                    h.eField = bbClass.getField(ClassMapping.runtimeFieldName("a8.b$b", "e", loader));
                    h.hMethod = ClassMapping.getMethod(bbClass, "a8.b$b", "h", loader, int.class);
                    h.f5509cField = k8cClass.getField(
                            ClassMapping.runtimeFieldName("k8.c", "c", loader));
                    h.pairReady = true;
                } catch (Exception e) {
                    Log.w(TAG, "CellTableRowDispatcher: pair resolution handles unavailable: " + e);
                }
                try {
                    Class<?> wsClass = ClassMapping.loadClass(
                            "com.qtrun.sys.Workspace", loader);
                    if (wsClass != null) {
                        h.wsSingleton = wsClass.getField(
                                ClassMapping.runtimeFieldName(
                                        "com.qtrun.sys.Workspace", "j", loader));
                        h.wsModuleIndex = wsClass.getField(
                                ClassMapping.runtimeFieldName(
                                        "com.qtrun.sys.Workspace", "a", loader));
                        h.wsDataSource = wsClass.getField(
                                ClassMapping.runtimeFieldName(
                                        "com.qtrun.sys.Workspace", "c", loader));
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                Log.w(TAG, "CellTableRowDispatcher: handle resolution failed: " + e);
            }
            return h;
        }
    }

    private static volatile Handles handles;

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    private static final class Entry {
        final RowProcessor processor;
        final long         seq;
        final String       name;

        Entry(RowProcessor processor, long seq, String name) {
            this.processor = processor;
            this.seq       = seq;
            this.name      = name;
        }
    }

    /**
     * Per-runtime-Method slot holding the processor list and a volatile
     * snapshot array. The snapshot is published via a volatile write in
     * register() and read lock-free in the interceptor hot path, so a
     * getView call does NOT acquire a monitor or allocate an array.
     */
    private static final class MethodSlot {
        final List<Entry> processors = new ArrayList<>();
        volatile Entry[] entries = new Entry[0];
    }

    /** Runtime getView Method -> slot. */
    private static final Map<Method, MethodSlot> SLOTS = new IdentityHashMap<>();
    private static long seqCounter = 0;

    /**
     * Registers a row processor for the getView method of the given logical
     * adapter class and installs the single shared interceptor on the first
     * registration for that runtime Method.
     *
     * Call order must mirror MainHook install order so that the effective
     * before/after ordering matches the legacy multi-interceptor chain.
     */
    static synchronized boolean register(XposedInterface xposed, ClassLoader loader,
                                         String logicalAdapterClass, String featureName,
                                         RowProcessor processor) {
        if (handles == null) {
            handles = Handles.resolve(loader);
        }
        Class<?> adapterClass = ClassMapping.loadClass(logicalAdapterClass, loader);
        if (adapterClass == null) {
            Log.i(TAG, "CellTableRowDispatcher: " + logicalAdapterClass
                    + " not available, skipping " + featureName);
            return false;
        }
        Method getViewMethod;
        try {
            getViewMethod = adapterClass.getDeclaredMethod(
                    "getView", int.class, View.class, ViewGroup.class);
            getViewMethod.setAccessible(true);
        } catch (Throwable t) {
            try {
                getViewMethod = adapterClass.getMethod(
                        "getView", int.class, View.class, ViewGroup.class);
            } catch (Throwable t2) {
                Log.w(TAG, "CellTableRowDispatcher: getView not found on "
                        + logicalAdapterClass + " for " + featureName + ": " + t2);
                return false;
            }
        }

        MethodSlot slot = SLOTS.get(getViewMethod);
        if (slot == null) {
            slot = new MethodSlot();
            SLOTS.put(getViewMethod, slot);
        }
        slot.processors.add(new Entry(processor, seqCounter++, featureName));
        slot.entries = slot.processors.toArray(new Entry[0]);

        if (slot.processors.size() == 1) {
            installInterceptor(xposed, getViewMethod, slot);
            Log.i(TAG, "CellTableRowDispatcher: installed single getView interceptor on "
                    + getViewMethod.getDeclaringClass().getName());
        }
        return true;
    }

    private static void installInterceptor(XposedInterface xposed, final Method method,
                                           final MethodSlot slot) {
        xposed.hook(method).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                final Entry[] entries = slot.entries;
                RowContext ctx = new RowContext(chain.getThisObject(), (int) chain.getArg(0));

                // Pre-proceed phase — registration (chain) order.
                boolean[] postAllowed = new boolean[entries.length];
                for (int i = 0; i < entries.length; i++) {
                    postAllowed[i] = true;
                    try {
                        entries[i].processor.beforeProceed(ctx);
                    } catch (Throwable t) {
                        // PROTECTIVE parity: hook that throws pre-proceed is
                        // dropped from the chain for this call.
                        postAllowed[i] = false;
                        Log.e(TAG, entries[i].name + " getView pre-check failed: " + t);
                    }
                }

                // The original getView is invoked exactly once per call.
                Object result = chain.proceed();

                // Post-proceed phase — REVERSE registration order (legacy chain
                // unwind order).
                for (int i = entries.length - 1; i >= 0; i--) {
                    if (!postAllowed[i]) continue;
                    try {
                        entries[i].processor.afterProceed(ctx, result);
                    } catch (Throwable t) {
                        // PROTECTIVE parity: log and continue; result returned
                        // by the original proceed() is passed on unchanged.
                        Log.e(TAG, entries[i].name + " getView processing failed: " + t);
                    }
                }
                return result;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Shared per-Resources view id cache (tvRowCellName / tvRowCellID / tvRowRFID)
    //
    // Resource ids are constant for a given Resources/package pair, so they are
    // resolved once per Resources object instead of once per row per tick.
    // -----------------------------------------------------------------------

    public static final int ROW_ID_CELL_NAME = 0;
    public static final int ROW_ID_CELL_ID   = 1;
    public static final int ROW_ID_RF_ID     = 2;

    private static final Map<Resources, int[]> RES_ID_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Returns {tvRowCellName, tvRowCellID, tvRowRFID} ids for the given Resources. */
    static int[] cellRowIds(Resources res) {
        int[] ids = RES_ID_CACHE.get(res);
        if (ids == null) {
            ids = new int[]{
                    res.getIdentifier("tvRowCellName", "id", "com.qtrun.QuickTest"),
                    res.getIdentifier("tvRowCellID", "id", "com.qtrun.QuickTest"),
                    res.getIdentifier("tvRowRFID", "id", "com.qtrun.QuickTest"),
            };
            RES_ID_CACHE.put(res, ids);
        }
        return ids;
    }
}
