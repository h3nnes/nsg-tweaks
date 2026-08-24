package com.nsgmod.band;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks t7.t.i0(String) — NSG's Experiment onCreatePreferences — to inject
 * SwitchPreferenceCompat toggles into the PreferenceScreen root.
 *
 * All androidx.preference classes are loaded from NSG's classloader (compileOnly
 * means they are absent from our APK at runtime). Every preference interaction
 * goes through reflection.
 *
 * Toggle states are stored in SharedPreferences file "com.qtrun.QuickTest_preferences".
 */
public class SettingsToggleHook {

    private static final String TAG               = "NSGBandHook";
    static final String  PREF_KEY_CELL_MODS       = "nsgmod.cell_mods_enabled";
    static final String  PREF_KEY_CELL_ID_MATCH   = "nsgmod.cell_id_match_enabled";
    static final String  PREF_KEY_RT_PLAY         = "nsgmod.rt_play_enabled";
    static final String  PREF_KEY_NRNSA_EXT_CELLS = "nsgmod.nrnsa_ext_cells_enabled";
    static final String  PREF_KEY_LTE_EXT_CELLS   = "nsgmod.lte_ext_cells_enabled";
    static final String  PREF_KEY_FAST_REFRESH    = "nsgmod.fast_refresh_enabled";
    static final String  PREF_KEY_CELL_ROW_HEIGHT = "nsgmod.cell_row_height_enabled";
    static final String  PREF_KEY_PATHLOSS_COLUMN = "nsgmod.pathloss_column_enabled";
    static final String  PREF_KEY_NR_PDSCH_SINR  = "nsgmod.nr_pdsch_snr_enabled";
    static final String  PREF_KEY_GNB_ID_HEADER  = "nsgmod.gnb_id_header_enabled";

    // -----------------------------------------------------------------------
    // Cached toggle reads.
    //
    // The Application + SharedPreferences are resolved ONCE (lazy, synchronized).
    // Each toggle is mirrored in a volatile field kept exact and immediate by a
    // SharedPreferences.OnSharedPreferenceChangeListener — toggles are user
    // settings, not modem data. All other hooks only read the volatiles.
    //
    // Fallback behavior matches the previous per-call reflective implementation:
    // if the Application cannot be resolved (or resolution throws) the documented
    // per-toggle default is returned and resolution is retried on the next call.
    // -----------------------------------------------------------------------
    private static final String PREFS_NAME = "com.qtrun.QuickTest_preferences";

    private static volatile SharedPreferences sPrefs;
    private static boolean sResolutionWarned = false;

    private static volatile boolean sCellMods       = true;  // default ON  (fail open)
    private static volatile boolean sCellIdMatch    = true;  // default ON  (fail open)
    private static volatile boolean sRtPlay         = true;  // default ON  (fail open)
    private static volatile boolean sNrNsaExtCells  = true;  // default ON  (fail open)
    private static volatile boolean sLteExtCells    = true;  // default ON  (fail open)
    private static volatile boolean sFastRefresh    = false; // default OFF (fail closed)
    private static volatile boolean sCellRowHeight  = true;  // default ON  (fail open)
    private static volatile boolean sPathlossColumn = false; // default OFF (fail closed)
    private static volatile boolean sNrPdschSnr     = false; // default OFF (fail closed)
    private static volatile boolean sGnbIdHeader    = true;  // default ON  (fail open)

    // NOTE: must be a strong static reference — SharedPreferencesImpl keeps
    // listeners in a WeakHashMap.
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (key == null) return;
                    try {
                        updateToggle(p, key);
                    } catch (Throwable t) {
                        Log.w(TAG, "toggle listener failed for key " + key + ": " + t);
                    }
                }
            };

    private static void updateToggle(SharedPreferences p, String key) {
        switch (key) {
            case PREF_KEY_CELL_MODS:       sCellMods       = p.getBoolean(key, true);  break;
            case PREF_KEY_CELL_ID_MATCH:   sCellIdMatch    = p.getBoolean(key, true);  break;
            case PREF_KEY_RT_PLAY:         sRtPlay         = p.getBoolean(key, true);  break;
            case PREF_KEY_NRNSA_EXT_CELLS: sNrNsaExtCells  = p.getBoolean(key, true);  break;
            case PREF_KEY_LTE_EXT_CELLS:   sLteExtCells    = p.getBoolean(key, true);  break;
            case PREF_KEY_FAST_REFRESH:    sFastRefresh    = p.getBoolean(key, false); break;
            case PREF_KEY_CELL_ROW_HEIGHT: sCellRowHeight  = p.getBoolean(key, true);  break;
            case PREF_KEY_PATHLOSS_COLUMN: sPathlossColumn = p.getBoolean(key, false); break;
            case PREF_KEY_NR_PDSCH_SINR:   sNrPdschSnr     = p.getBoolean(key, false); break;
            case PREF_KEY_GNB_ID_HEADER:   sGnbIdHeader    = p.getBoolean(key, true);  break;
            default: break;
        }
    }

    private static void loadAllToggles(SharedPreferences p) {
        updateToggle(p, PREF_KEY_CELL_MODS);
        updateToggle(p, PREF_KEY_CELL_ID_MATCH);
        updateToggle(p, PREF_KEY_RT_PLAY);
        updateToggle(p, PREF_KEY_NRNSA_EXT_CELLS);
        updateToggle(p, PREF_KEY_LTE_EXT_CELLS);
        updateToggle(p, PREF_KEY_FAST_REFRESH);
        updateToggle(p, PREF_KEY_CELL_ROW_HEIGHT);
        updateToggle(p, PREF_KEY_PATHLOSS_COLUMN);
        updateToggle(p, PREF_KEY_NR_PDSCH_SINR);
        updateToggle(p, PREF_KEY_GNB_ID_HEADER);
    }

    /** Resolves Application + SharedPreferences once; on failure retries next call. */
    private static void ensurePrefs() {
        if (sPrefs != null) return;
        synchronized (SettingsToggleHook.class) {
            if (sPrefs != null) return;
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                android.app.Application app =
                        (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
                if (app == null) return; // not ready yet — defaults, retry next call
                SharedPreferences p = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                loadAllToggles(p);
                p.registerOnSharedPreferenceChangeListener(PREF_LISTENER);
                sPrefs = p;
            } catch (Throwable t) {
                if (!sResolutionWarned) {
                    sResolutionWarned = true;
                    Log.w(TAG, "SharedPreferences resolution failed, using defaults: " + t);
                }
            }
        }
    }

    /** Returns true when the cell-table modifications toggle is enabled (default: true). */
    public static boolean cellModsEnabled() {
        ensurePrefs();
        return sCellMods;
    }

    /** Returns true when the cell-ID matching toggle is enabled (default: true). */
    public static boolean cellIdMatchEnabled() {
        ensurePrefs();
        return sCellIdMatch;
    }

    /** Returns true when the fast-refresh toggle is enabled (default: false). */
    public static boolean fastRefreshEnabled() {
        ensurePrefs();
        return sFastRefresh;
    }

    /** Returns true when the NR-SA Pathloss header column toggle is enabled (default: false). */
    public static boolean pathlossColumnEnabled() {
        ensurePrefs();
        return sPathlossColumn;
    }

    /** Returns true when the cell row height toggle is enabled (default: true). */
    public static boolean cellRowHeightEnabled() {
        ensurePrefs();
        return sCellRowHeight;
    }

    /** Returns true when the RT-Play button toggle is enabled (default: true). */
    public static boolean rtPlayEnabled() {
        ensurePrefs();
        return sRtPlay;
    }

    /** Returns true when NR-NSA extended cell count (16 rows) is enabled (default: true). */
    public static boolean nrNsaExtCellsEnabled() {
        ensurePrefs();
        return sNrNsaExtCells;
    }

    /** Returns true when LTE extended cell count (16 rows) is enabled (default: true). */
    public static boolean lteExtCellsEnabled() {
        ensurePrefs();
        return sLteExtCells;
    }

    /** Returns true when the NR PDSCH SINR row toggle is enabled (default: false). */
    public static boolean nrPdschSnrEnabled() {
        ensurePrefs();
        return sNrPdschSnr;
    }

    /** Returns true when the NR-NSA gNB-ID header toggle is enabled (default: true). */
    public static boolean gnbIdHeaderEnabled() {
        ensurePrefs();
        return sGnbIdHeader;
    }

    private static final String FRAGMENT_CLS = "t7.t";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    public SettingsToggleHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        try {
            Class<?> fragCls = ClassMapping.loadClass(FRAGMENT_CLS, loader);
            if (fragCls == null) {
                Log.i(TAG, "SettingsToggleHook: " + FRAGMENT_CLS
                        + " not available on this flavor, skipping");
                return;
            }
            Method i0 = ClassMapping.getDeclaredMethod(fragCls, FRAGMENT_CLS, "i0", loader, String.class);

            xposed.hook(i0).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object fragment = chain.getThisObject();
                    try {
                        injectToggle(fragment);
                    } catch (Throwable t) {
                        Log.w(TAG, "injectToggle failed: " + t, t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "SettingsToggleHook: installed");
        } catch (Throwable t) {
            Log.w(TAG, "install failed: " + t, t);
        }
    }

    private void injectToggle(Object fragment) throws Throwable {
        // --- 1. Get PreferenceScreen via field reflection ---
        // fragment.Y  →  PreferenceManager (androidx.preference.f)
        // prefManager.<field of type PreferenceScreen>  →  PreferenceScreen
        Class<?> baseCls = ClassMapping.loadClass("androidx.preference.c", loader);
        Field yField = baseCls.getField(ClassMapping.runtimeFieldName("androidx.preference.c", "Y", loader));
        Object prefManager = yField.get(fragment);
        if (prefManager == null) {
            Log.w(TAG, "PreferenceManager (Y) is null — aborting");
            return;
        }

        Class<?> prefScreenCls = ClassMapping.loadClass("androidx.preference.PreferenceScreen", loader);
        Class<?> prefMgrCls    = prefManager.getClass();

        Field screenField = null;
        for (Field f : prefMgrCls.getDeclaredFields()) {
            if (f.getType() == prefScreenCls) { screenField = f; break; }
        }
        if (screenField == null) {
            Class<?> c = prefMgrCls.getSuperclass();
            while (c != null && screenField == null) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == prefScreenCls) { screenField = f; break; }
                }
                c = c.getSuperclass();
            }
        }
        if (screenField == null) {
            Log.w(TAG, "PreferenceScreen field not found — aborting");
            return;
        }
        screenField.setAccessible(true);
        Object prefScreen = screenField.get(prefManager);
        if (prefScreen == null) {
            Log.w(TAG, "PreferenceScreen is null — aborting");
            return;
        }

        // --- 2. Guard against double-injection ---
        // findPreference generic signature <T extends Preference> T F(CharSequence) erases
        // return type to Object at runtime — match on CharSequence parameter only.
        Class<?> prefCls = ClassMapping.loadClass("androidx.preference.Preference", loader);
        Method findPref = null;
        for (Method m : prefScreenCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == CharSequence.class) {
                findPref = m;
                break;
            }
        }
        if (findPref != null && findPref.invoke(prefScreen, PREF_KEY_CELL_MODS) != null) {
            return; // already injected
        }

        // --- 3. Get Context ---
        Method reqCtx = null;
        for (Method m : fragment.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == Context.class) {
                reqCtx = m; break;
            }
        }
        if (reqCtx == null) {
            Log.w(TAG, "requireContext() not found — aborting");
            return;
        }
        Context ctx = (Context) reqCtx.invoke(fragment);

        // --- 4. Locate SwitchPreferenceCompat constructor ---
        Class<?> swCls = ClassMapping.loadClass("androidx.preference.SwitchPreferenceCompat", loader);
        java.lang.reflect.Constructor<?> swCtor = null;
        for (java.lang.reflect.Constructor<?> c : swCls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0] == Context.class) {
                if (swCtor == null || p.length < swCtor.getParameterCount()) swCtor = c;
            }
        }
        if (swCtor == null) {
            Log.w(TAG, "SwitchPreferenceCompat ctor not found — aborting");
            return;
        }
        swCtor.setAccessible(true);

        // --- 5. Locate key/title/summary/iconSpaceReserved fields on Preference ---
        Class<?> prefBaseCls = ClassMapping.loadClass("androidx.preference.Preference", loader);
        Field keyField     = null;
        Field titleField   = null;
        Field summaryField = null;
        for (Field f : prefBaseCls.getDeclaredFields()) {
            Class<?> ft = f.getType();
            int mod = f.getModifiers();
            if (ft == String.class && java.lang.reflect.Modifier.isFinal(mod) && keyField == null) {
                keyField = f;
            } else if (ft == CharSequence.class && titleField == null) {
                titleField = f;
            } else if (ft == CharSequence.class && summaryField == null) {
                summaryField = f;
            }
        }
        if (keyField != null) keyField.setAccessible(true);
        if (titleField != null) titleField.setAccessible(true);
        if (summaryField != null) summaryField.setAccessible(true);

        // iconSpaceReserved field (named "C" in v4.8.6)
        Field iconSpaceField = null;
        try {
            String iconSpaceName = ClassMapping.runtimeFieldName("androidx.preference.Preference", "C", loader);
            iconSpaceField = prefBaseCls.getDeclaredField(iconSpaceName);
            iconSpaceField.setAccessible(true);
        } catch (Throwable ignored) {}

        // setDefaultValue: void method(Object) on Preference
        Method setDefaultValue = null;
        for (Method m : prefBaseCls.getDeclaredMethods()) {
            if (m.getReturnType() == void.class) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Object.class) {
                    setDefaultValue = m;
                    setDefaultValue.setAccessible(true);
                    break;
                }
            }
        }

        // attach-to-manager method: void o(PreferenceManager) on Preference
        Class<?> prefMgrType = prefManager.getClass();
        Method attachMethod = null;
        outer:
        for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(prefMgrType)
                        && m.getReturnType() == void.class) {
                    attachMethod = m;
                    break outer;
                }
            }
        }
        if (attachMethod != null) attachMethod.setAccessible(true);

        // parent (PreferenceGroup) field on Preference
        Class<?> prefGroupCls = ClassMapping.loadClass("androidx.preference.PreferenceGroup", loader);
        Field parentField = null;
        outer2:
        for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == prefGroupCls) { parentField = f; break outer2; }
            }
        }
        if (parentField != null) parentField.setAccessible(true);

        // children ArrayList on PreferenceGroup
        Field qField = null;
        for (Field f : prefGroupCls.getDeclaredFields()) {
            if (f.getType() == java.util.ArrayList.class) { qField = f; break; }
        }
        if (qField == null) {
            Log.w(TAG, "children ArrayList field not found — aborting");
            return;
        }
        qField.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.ArrayList<Object> children = (java.util.ArrayList<Object>) qField.get(prefScreen);
        if (children == null) {
            Log.w(TAG, "children ArrayList is null — aborting");
            return;
        }

        Object[] ctorArgs = new Object[swCtor.getParameterCount()];
        ctorArgs[0] = ctx;

        // --- 6. Inject toggles ---
        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_CELL_MODS) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_CELL_MODS, "NSGMod: Cell table mods",
                    "Add extra columns/rows to LTE, NR-NSA and NR-SA cell tables",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_RT_PLAY) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_RT_PLAY, "NSGMod: RT-Play button",
                    "Show real-time replay button in log playback controls",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_CELL_ID_MATCH) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_CELL_ID_MATCH, "NSGMod: Use CellID instead of PCI",
                    "Match neighbour cells by EARFCN+CellID rather than EARFCN+PCI",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_NRNSA_EXT_CELLS) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_NRNSA_EXT_CELLS, "NSGMod: NR-NSA 16-cell table",
                    "Show up to 16 cells in NR-NSA NR cell table (default is 8)",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_LTE_EXT_CELLS) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_LTE_EXT_CELLS, "NSGMod: LTE 16-cell table",
                    "Show up to 16 cells in LTE cell table (LTE mode and NR-NSA mode)",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_FAST_REFRESH) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_FAST_REFRESH, "NSGMod: Fast refresh",
                    "Double UI refresh rate (440 ms instead of 880 ms)",
                    false, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_CELL_ROW_HEIGHT) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_CELL_ROW_HEIGHT, "NSGMod: Collapse empty cell rows",
                    "Shrink cell rows when no CSV cell info is available",
                    true, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_PATHLOSS_COLUMN) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_PATHLOSS_COLUMN, "NSGMod: NR-SA Pathloss column",
                    "Show Pathloss column in top RF header bar in NR-SA mode (Qualcomm only)",
                    false, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_NR_PDSCH_SINR) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_NR_PDSCH_SINR, "NSGMod: Show NR PDSCH SINR",
                    "Show PDSCH SINR row in NR-SA CA Matrix DL (Qualcomm may not provide data)",
                    false, prefManager, prefScreen, children);
        }

        if (findPref == null || findPref.invoke(prefScreen, PREF_KEY_GNB_ID_HEADER) == null) {
            injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                    iconSpaceField, setDefaultValue, attachMethod, parentField,
                    PREF_KEY_GNB_ID_HEADER, "NSGMod: NR-NSA gNB-ID in header",
                    "Show gNB-ID / sector next to ECellID in NR-NSA mode (requires cell DB)",
                    true, prefManager, prefScreen, children);
        }

        // --- 7. Trigger adapter refresh ---
        // Send handler message 1 — replicates what j0() does when view is already created.
        // If the view is not yet created the adapter is built fresh from Q in onViewCreated.
        try {
            android.os.Handler handler = null;
            for (Class<?> c = fragment.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (android.os.Handler.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(fragment);
                        if (val != null) { handler = (android.os.Handler) val; break; }
                    }
                }
                if (handler != null) break;
            }
            if (handler != null) {
                handler.sendEmptyMessage(1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "adapter refresh failed: " + t);
        }
    }

    /**
     * Creates, configures, and adds a single SwitchPreferenceCompat to the given children list.
     */
    private Object injectSwitch(
            java.lang.reflect.Constructor<?> swCtor,
            Object[] ctorArgs,
            Field keyField, Field titleField, Field summaryField,
            Field iconSpaceField, Method setDefaultValue,
            Method attachMethod, Field parentField,
            String key, String title, String summary,
            boolean defaultOn,
            Object prefManager, Object prefScreen,
            java.util.ArrayList<Object> children) {
        try {
            Object sw = swCtor.newInstance(ctorArgs);
            if (keyField != null)     keyField.set(sw, key);
            if (titleField != null)   titleField.set(sw, title);
            if (summaryField != null) summaryField.set(sw, summary);
            if (iconSpaceField != null) iconSpaceField.set(sw, false);
            if (setDefaultValue != null) setDefaultValue.invoke(sw, defaultOn);
            children.add(sw);
            if (attachMethod != null) attachMethod.invoke(sw, prefManager);
            if (parentField != null)  parentField.set(sw, prefScreen);
            return sw;
        } catch (Throwable t) {
            Log.w(TAG, "injectSwitch(" + key + ") failed: " + t, t);
            return null;
        }
    }
}
