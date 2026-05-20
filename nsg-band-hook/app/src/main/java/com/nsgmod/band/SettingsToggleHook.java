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
 * Hooks t7.u.i0(String) — NSG's Experiment onCreatePreferences — to inject a
 * SwitchPreferenceCompat into the PreferenceScreen root.
 *
 * All androidx.preference classes are loaded from NSG's classloader (compileOnly
 * means they are absent from our APK at runtime). Every preference interaction
 * goes through reflection.
 *
 * Toggle state is stored in SharedPreferences file "nsgmod_prefs"
 * under key "nsgmod.subscriber_bypass" (default: false).
 * SubscriberHook reads the same file on every call.
 */
public class SettingsToggleHook {

    private static final String TAG          = "NSGBandHook_Settings";
    static final String  PREFS_FILE   = "nsgmod_prefs";
    static final String  PREF_KEY     = "nsgmod.subscriber_bypass";
    static final String  PREF_KEY_CELL_MODS = "nsgmod.cell_mods_enabled";

    /** Returns true when the cell-table modifications toggle is enabled (default: true). */
    public static boolean cellModsEnabled() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            android.app.Application app =
                    (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
            if (app == null) return true;
            SharedPreferences prefs = app.getSharedPreferences(
                    "com.qtrun.QuickTest_preferences", android.content.Context.MODE_PRIVATE);
            return prefs.getBoolean(PREF_KEY_CELL_MODS, true); // default ON
        } catch (Throwable t) {
            android.util.Log.w("NSGBandHook_Settings", "cellModsEnabled check failed: " + t);
            return true; // fail open — keep mods active
        }
    }
    private static final String FRAGMENT_CLS = "t7.u";

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    public SettingsToggleHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        Log.i(TAG, "install() called");
        try {
            Class<?> fragCls = loader.loadClass(FRAGMENT_CLS);
            Method i0 = fragCls.getDeclaredMethod("i0", String.class);
            Log.i(TAG, "method found: " + i0);

            xposed.hook(i0).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object fragment = chain.getThisObject();
                    Log.i(TAG, "i0() intercepted");
                    try {
                        injectToggle(fragment);
                    } catch (Throwable t) {
                        Log.w(TAG, "injectToggle failed: " + t, t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "settings toggle hook installed");
        } catch (Throwable t) {
            Log.w(TAG, "install failed: " + t, t);
        }
    }

    private void injectToggle(Object fragment) throws Throwable {
        // --- 1. Get PreferenceScreen via field reflection ---
        // fragment.Y  →  PreferenceManager (androidx.preference.f)
        // prefManager.<field of type PreferenceScreen>  →  PreferenceScreen
        Class<?> baseCls    = loader.loadClass("androidx.preference.c");
        Field yField        = baseCls.getField("Y");
        Object prefManager  = yField.get(fragment);
        if (prefManager == null) {
            Log.w(TAG, "PreferenceManager is null");
            return;
        }

        Class<?> prefScreenCls = loader.loadClass("androidx.preference.PreferenceScreen");
        Class<?> prefMgrCls    = prefManager.getClass();
        Field screenField = null;
        for (Field f : prefMgrCls.getDeclaredFields()) {
            if (f.getType() == prefScreenCls) {
                screenField = f;
                break;
            }
        }
        if (screenField == null) {
            // walk superclasses too
            Class<?> c = prefMgrCls.getSuperclass();
            while (c != null && screenField == null) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == prefScreenCls) { screenField = f; break; }
                }
                c = c.getSuperclass();
            }
        }
        if (screenField == null) {
            Log.w(TAG, "PreferenceScreen field not found in PreferenceManager");
            return;
        }
        screenField.setAccessible(true);
        Object prefScreen = screenField.get(prefManager);
        if (prefScreen == null) {
            Log.w(TAG, "PreferenceScreen is null");
            return;
        }

        // --- 2. Guard against double-injection ---
        // findPreference is obfuscated; find by signature: (CharSequence) → Preference
        Class<?> prefCls = loader.loadClass("androidx.preference.Preference");
        Method findPref = null;
        for (Method m : prefScreenCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == CharSequence.class && prefCls.isAssignableFrom(m.getReturnType())) {
                findPref = m;
                break;
            }
        }
        if (findPref != null && findPref.invoke(prefScreen, PREF_KEY) != null) {
            Log.i(TAG, "toggle already present, skipping");
            return;
        }
        // --- 3. Get Context ---
        // requireContext() is obfuscated; find by signature: () → Context
        Method reqCtx = null;
        for (Method m : fragment.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == Context.class) {
                reqCtx = m;
                break;
            }
        }
        if (reqCtx == null) {
            Log.w(TAG, "requireContext() method not found");
            return;
        }
        Context ctx = (Context) reqCtx.invoke(fragment);

        // --- 4. Build SwitchPreferenceCompat via reflection ---
        // Constructor is (Context, AttributeSet[, int[, int]]) — no single-arg variant.
        // Pick the shortest constructor with Context as first param, pass nulls for rest.
        Class<?> swCls = loader.loadClass("androidx.preference.SwitchPreferenceCompat");
        java.lang.reflect.Constructor<?> swCtor = null;
        for (java.lang.reflect.Constructor<?> c : swCls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0] == Context.class) {
                if (swCtor == null || p.length < swCtor.getParameterCount()) swCtor = c;
            }
        }
        if (swCtor == null) { Log.w(TAG, "SwitchPreferenceCompat ctor not found"); return; }
        swCtor.setAccessible(true);
        Object[] ctorArgs = new Object[swCtor.getParameterCount()];
        ctorArgs[0] = ctx;
        Object sw = swCtor.newInstance(ctorArgs);

        // Set fields directly by type — decompiler-assigned names don't match runtime.
        Class<?> prefBaseCls = loader.loadClass("androidx.preference.Preference");

        // key: the only String field whose name is "key" candidate — find by scanning
        // We identify fields by type + position heuristic: key=String#1, summary=String#2, etc.
        // Safer: use the obfuscated method that takes (String) and returns void for setKey.
        // x(String) in decompiled source writes to SharedPreferences — that's persistString.
        // The key field is final String — set via reflection by finding the first final String field.
        Field keyField = null;
        Field titleField = null;   // CharSequence — title
        Field summaryField = null; // CharSequence — summary (second CharSequence field)
        for (Field f : prefBaseCls.getDeclaredFields()) {
            Class<?> ft = f.getType();
            int mod = f.getModifiers();
            if (ft == String.class && java.lang.reflect.Modifier.isFinal(mod) && keyField == null) {
                keyField = f;
            } else if (ft == CharSequence.class && titleField == null) {
                titleField = f;
            } else if (ft == CharSequence.class && summaryField == null) {
                // second CharSequence field = summary
                summaryField = f;
            }
        }

        if (keyField != null) { keyField.setAccessible(true); keyField.set(sw, PREF_KEY); }
        else Log.w(TAG, "key field not found");

        if (titleField != null) { titleField.setAccessible(true); titleField.set(sw, "NSGMod: Subscriber bypass"); }
        else Log.w(TAG, "title field not found");

        if (summaryField != null) { summaryField.setAccessible(true); summaryField.set(sw, "Unlock all premium features"); }
        else Log.w(TAG, "summary field not found");

        // iconSpaceReserved = field "C" (public final boolean C) in Preference
        // Controls whether icon placeholder space is shown when no icon is set.
        // Do NOT call y(boolean) — that method sets f1518p (isVisible/shown), not iconSpaceReserved,
        // and causes k()=false which disables the entire view (greys it out).
        try {
            Field iconSpaceField = prefBaseCls.getDeclaredField("C");
            iconSpaceField.setAccessible(true);
            iconSpaceField.set(sw, false);
        } catch (Throwable t) {
            Log.w(TAG, "iconSpaceReserved field C not found: " + t);
        }

        // setDefaultValue → find void method taking Object
        for (Method m : prefBaseCls.getDeclaredMethods()) {
            if (m.getReturnType() == void.class) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Object.class) {
                    m.setAccessible(true);
                    m.invoke(sw, false);
                    break;
                }
            }
        }

        // --- 5. Wire PreferenceDataStore ---
        // PreferenceDataStore doesn't exist in NSG's obfuscated build.
        // The preference will persist via NSG's default PreferenceManager backing store
        // (com.qtrun.QuickTest_preferences). SubscriberHook reads from the same file.
        // No extra wiring needed.

        // --- 6. Add to PreferenceScreen ---
        // addPreference has no public equivalent in this build.
        // PreferenceGroup.Q is the ArrayList of children — add directly.
        Class<?> prefGroupCls = loader.loadClass("androidx.preference.PreferenceGroup");
        Field qField = null;
        for (Field f : prefGroupCls.getDeclaredFields()) {
            if (f.getType() == java.util.ArrayList.class) {
                qField = f;
                break;
            }
        }
        if (qField == null) {
            Log.w(TAG, "PreferenceGroup children ArrayList not found");
            return;
        }
        qField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.ArrayList<Object> children = (java.util.ArrayList<Object>) qField.get(prefScreen);
        if (children == null) {
            Log.w(TAG, "children ArrayList is null");
            return;
        }
        children.add(sw);

        // --- Second toggle: Cell table mods ---
        if (findPref != null && findPref.invoke(prefScreen, PREF_KEY_CELL_MODS) == null) {
            Object sw2 = swCtor.newInstance(ctorArgs);

            if (keyField != null) { keyField.set(sw2, PREF_KEY_CELL_MODS); }
            if (titleField != null) { titleField.set(sw2, "NSGMod: Cell table mods"); }
            if (summaryField != null) { summaryField.set(sw2, "Add extra columns/rows to LTE and NR cell tables"); }

            try {
                Field iconSpaceField = prefBaseCls.getDeclaredField("C");
                iconSpaceField.setAccessible(true);
                iconSpaceField.set(sw2, false);
            } catch (Throwable t) {
                Log.w(TAG, "iconSpaceReserved field C not found for sw2: " + t);
            }

            for (Method m : prefBaseCls.getDeclaredMethods()) {
                if (m.getReturnType() == void.class) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == Object.class) {
                        m.setAccessible(true);
                        m.invoke(sw2, Boolean.TRUE);
                        break;
                    }
                }
            }

            children.add(sw2);
        }

        // Notify the RecyclerView adapter that the preference list changed
        // n() = notifyHierarchyChanged in decompiled source
        prefScreenCls.getMethod("n").invoke(prefScreen);

        Log.i(TAG, "subscriber bypass toggle injected into Experiment screen");
    }
}
