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
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Single-interceptor dispatcher for the cell-table adapter {@code nc.getView}.
 *
 * <p>In v4.8.14 every cell-table adapter (LTE, NR-NSA, WCDMA, ...) is the same class
 * {@code nc extends q00}; {@code nc.f} (int) selects the table type (4 = LTE, 6 = NR-NSA).
 * Four features (BandColumnHook, NrNsaBandwidthColumnHook, LteBandwidthColumnHook,
 * CellRowHeightHook) need to post-process the returned row view. This dispatcher
 * installs ONE interceptor on {@code nc.getView(int, View, ViewGroup)}, resolves the
 * shared per-call row context once, and invokes each feature's row processor.
 *
 * <p>libxposed runs same-priority interceptors in registration order, so post-proceed
 * bodies would run in reverse registration order. The dispatcher keeps that order:
 * {@code beforeProceed()} ascending, {@code afterProceed()} descending. MainHook registers
 * NrNsaBandwidthColumnHook, BandColumnHook, LteBandwidthColumnHook, CellRowHeightHook, so the
 * effective post order is CellRowHeightHook -> LteBandwidthColumnHook -> BandColumnHook ->
 * NrNsaBandwidthColumnHook.
 *
 * <p>Error isolation: an exception in one processor's beforeProceed drops that processor for
 * this call; an exception in afterProceed is logged and the remaining processors still run.
 * The original result is always returned unchanged.
 *
 * <p>Row context: {@code nc.g(int)} maps a list position to Pair(source {@code de},
 * intraRow); {@code nc.e} is the {@code de[]} sources array; {@code q00.c} (long) is the
 * sample key of the adapter's current data.
 */
final class CellTableRowDispatcher {

    private static final String TAG = "NSGBandHook";

    /** Cell-table adapter class (extends q00) */
    static final String ADAPTER = "nc";
    private static final String ADAPTER_BASE = "q00";
    private static final String METHOD_ADAPTER_PAIR = "g"; // nc.g(int) -> Pair

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
    // Resolution semantics:
    //   * adapter type read failure / missing field -> adapterType stays null
    //     -> every feature runs.
    //   * pair resolution failure -> defaults (intraRow=position, isSource0=false,
    //     sampleKey=-1) and one Log.w.
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

        /** Passes when the type field is missing/unreadable. */
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

        /** Raw q00.c sample key, or -1 when unavailable (callers map <= 0 to MAX_VALUE). */
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
                    // feature stays active
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
                        (android.util.Pair<?, ?>) h.pairMethod.invoke(adapter, position);
                if (pair != null && pair.second != null) {
                    intraRow  = (int) pair.second;
                    isSource0 = (pair.first != null && sources != null
                            && sources.length > 0 && pair.first == sources[0]);
                }
                if (h.sampleKeyField != null && sources != null && sources.length > 0) {
                    sampleKey = h.sampleKeyField.getLong(sources[0]);
                }
            } catch (Exception e) {
                Log.w(TAG, "CellTableRowDispatcher: g(position) failed: " + e);
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
        Field  eField;            // nc.e — de[] sources array
        Method pairMethod;        // nc.g(int) — Pair<source, intraRow>
        Field  sampleKeyField;    // q00.c — adapter's current data sample key (long)
        Field  adapterTypeField;  // nc.f — adapter type (4=LTE, 6=NR-NSA)
        boolean pairReady;

        // Workspace singleton / module index / data source — resolved once so
        // every RowProcessor can share them via RowContext.
        Field wsSingleton;   // Workspace.j — enum singleton
        Field wsModuleIndex; // Workspace.a — short module index
        Field wsDataSource;  // Workspace.c — DataSource

        static Handles resolve(ClassLoader loader) {
            Handles h = new Handles();
            Class<?> ncClass = Nsg.load(loader, ADAPTER);
            Class<?> baseClass = Nsg.load(loader, ADAPTER_BASE);
            if (ncClass == null || baseClass == null) {
                return h;
            }
            try {
                Field f = ncClass.getDeclaredField("f");
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    h.adapterTypeField = f;
                }
            } catch (NoSuchFieldException ignored) {
            }
            try {
                h.eField = ncClass.getField("e");
                h.pairMethod = ncClass.getMethod(METHOD_ADAPTER_PAIR, int.class);
                h.sampleKeyField = baseClass.getField("c");
                h.pairReady = true;
            } catch (Exception e) {
                Log.w(TAG, "CellTableRowDispatcher: pair resolution handles unavailable: " + e);
            }
            try {
                Class<?> wsClass = Nsg.load(loader, Nsg.WORKSPACE);
                if (wsClass != null) {
                    h.wsSingleton = wsClass.getField("j");
                    h.wsModuleIndex = wsClass.getField("a");
                    h.wsDataSource = wsClass.getField("c");
                }
            } catch (Exception ignored) {
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

    private static final List<Entry> PROCESSORS = new ArrayList<>();
    private static volatile Entry[] entries = new Entry[0];
    private static long seqCounter = 0;
    private static boolean interceptorInstalled = false;

    /**
     * Registers a row processor for {@code nc.getView} and installs the single shared
     * interceptor on the first registration.
     *
     * Call order must mirror MainHook install order so that the before/after ordering
     * is as documented above.
     */
    static synchronized boolean register(XposedInterface xposed, ClassLoader loader,
                                         String featureName, RowProcessor processor) {
        if (handles == null) {
            handles = Handles.resolve(loader);
        }
        if (!interceptorInstalled) {
            Class<?> adapterClass = Nsg.load(loader, ADAPTER);
            if (adapterClass == null) {
                Log.w(TAG, "CellTableRowDispatcher: " + ADAPTER + " not available, skipping "
                        + featureName);
                return false;
            }
            Method getViewMethod;
            try {
                getViewMethod = adapterClass.getMethod(
                        "getView", int.class, View.class, ViewGroup.class);
            } catch (Throwable t) {
                Log.w(TAG, "CellTableRowDispatcher: getView not found for " + featureName + ": " + t);
                return false;
            }
            installInterceptor(xposed, getViewMethod);
            interceptorInstalled = true;
            Log.i(TAG, "CellTableRowDispatcher: installed single getView interceptor on "
                    + adapterClass.getName());
        }
        PROCESSORS.add(new Entry(processor, seqCounter++, featureName));
        entries = PROCESSORS.toArray(new Entry[0]);
        return true;
    }

    private static void installInterceptor(XposedInterface xposed, final Method method) {
        xposed.hook(method).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                final Entry[] entries = CellTableRowDispatcher.entries;
                RowContext ctx = new RowContext(chain.getThisObject(), (int) chain.getArg(0));

                // Pre-proceed phase — registration (chain) order.
                boolean[] postAllowed = new boolean[entries.length];
                for (int i = 0; i < entries.length; i++) {
                    postAllowed[i] = true;
                    try {
                        entries[i].processor.beforeProceed(ctx);
                    } catch (Throwable t) {
                        // a processor that throws pre-proceed is skipped
                        // post-proceed for this call.
                        postAllowed[i] = false;
                        Log.e(TAG, entries[i].name + " getView pre-check failed: " + t);
                    }
                }

                // The original getView is invoked exactly once per call.
                Object result = chain.proceed();

                // Post-proceed phase — REVERSE registration order.
                for (int i = entries.length - 1; i >= 0; i--) {
                    if (!postAllowed[i]) continue;
                    try {
                        entries[i].processor.afterProceed(ctx, result);
                    } catch (Throwable t) {
                        // log and continue; the original result is returned
                        // unchanged.
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
