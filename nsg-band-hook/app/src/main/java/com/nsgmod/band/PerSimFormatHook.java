package com.nsgmod.band;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Per-SIM cell-ID format overrides.
 *
 * <p>v4.8.14 targets: {@code rr0.a(long,int)} (cell-ID formatter; hooked, arg 1 is the
 * type index 0..7 = GSM_CellID, GSM_LAC, WCDMA_LAC, WCDMA_CellID, LTE_PCI, LTE_TAC,
 * LTE_CellID, NR_CellID), {@code is.f0(String)} (format settings fragment
 * onCreatePreferences; hooked), {@code if0.X} (PreferenceFragmentCompat's
 * PreferenceManager), {@code Preference.B} (iconSpaceReserved) and
 * DropDownPreference/ListPreference for the injected slot selector.
 *
 * <p>Overrides live per physical SIM slot in the "nsg_tweaks_per_sim_formats"
 * SharedPreferences file; NSG's own format keys are rewritten from global/slot
 * snapshots whenever the "Edit Formats For" selector changes.
 */
public class PerSimFormatHook {

    private static final String TAG = "NSGBandHook";
    private static final String MAIN_PREFS = "com.qtrun.QuickTest_preferences";
    private static final String BACKUP_PREFS = "nsg_tweaks_per_sim_formats";
    private static final String EDIT_SLOT_KEY = "nsgmod.per_sim_edit_slot";
    private static final String[] FORMAT_KEYS = {
        "GSM_CellID", "GSM_LAC", "WCDMA_LAC", "WCDMA_CellID",
        "LTE_PCI", "LTE_TAC", "LTE_CellID", "NR_CellID", "NR_GNB_LENGTH"
    };
    private static final String[] FORMAT_DEFAULTS = {
        "0", "0", "0", "5", "7", "0", "3", "12", "24"
    };
    private static final String[] TYPE_INDEX_TO_KEY = {
        "GSM_CellID", "GSM_LAC", "WCDMA_LAC", "WCDMA_CellID",
        "LTE_PCI", "LTE_TAC", "LTE_CellID", "NR_CellID"
    };

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Method cellIdFormatMethod;
    private Method formatFragmentMethod;

    private static volatile boolean suppressListener = false;
    private static boolean listenerRegistered = false;
    private static SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    public PerSimFormatHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        try {
            initReflection();

            if (formatFragmentMethod != null) {
                xposed.hook(formatFragmentMethod).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            injectSlotSelector(chain.getThisObject());
                        } catch (Throwable t) {
                            Log.e(TAG, "PerSimFormatHook inject failed: " + t, t);
                        }
                        return result;
                    }
                });
            } else {
                Log.w(TAG, "PerSimFormatHook: format fragment not found");
            }

            if (cellIdFormatMethod != null) {
                xposed.hook(cellIdFormatMethod).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        try {
                            List<Object> args = chain.getArgs();
                            int typeIndex = (Integer) args.get(1);
                            if (typeIndex < 0 || typeIndex >= TYPE_INDEX_TO_KEY.length) return result;
                            int activeSlot = getActiveSimSlot();
                            if (activeSlot < 0) return result;
                            String formatKey = TYPE_INDEX_TO_KEY[typeIndex];
                            Integer overrideCode = getSlotFormatCode(activeSlot, formatKey);
                            if (overrideCode == null) return result;
                            long cellId = (Long) args.get(0);
                            int gnbLength = 24;
                            if (overrideCode == 12 || overrideCode == 13) {
                                Integer slotGnb = getSlotFormatCode(activeSlot, "NR_GNB_LENGTH");
                                if (slotGnb != null) gnbLength = slotGnb;
                            }
                            return formatCellId(cellId, overrideCode, gnbLength);
                        } catch (Throwable t) {
                            Log.e(TAG, "PerSimFormatHook runtime error: " + t, t);
                            return result;
                        }
                    }
                });
            } else {
                Log.w(TAG, "PerSimFormatHook: rr0 method not found");
            }

            Log.i(TAG, "PerSimFormatHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "PerSimFormatHook install failed: " + t, t);
        }
    }

    private void initReflection() {
        try {
            Class<?> cellIdFormatCls = Nsg.load(loader, "rr0");
            if (cellIdFormatCls != null) {
                cellIdFormatMethod = cellIdFormatCls.getDeclaredMethod("a", long.class, int.class);
                cellIdFormatMethod.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PerSimFormatHook: rr0 not found: " + t);
        }

        try {
            Class<?> formatFragmentCls = Nsg.load(loader, "is");
            if (formatFragmentCls != null) {
                formatFragmentMethod = formatFragmentCls.getDeclaredMethod("f0", String.class);
                formatFragmentMethod.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PerSimFormatHook: format fragment not found: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectSlotSelector(Object fragment) throws Throwable {
        Class<?> baseCls = Nsg.load(loader, "if0");
        Field yField = baseCls.getField("X");
        Object prefManager = yField.get(fragment);
        if (prefManager == null) return;

        Class<?> prefScreenCls = Nsg.load(loader, "androidx.preference.PreferenceScreen");
        Class<?> prefMgrCls = prefManager.getClass();

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
        if (screenField == null) return;
        screenField.setAccessible(true);
        Object prefScreen = screenField.get(prefManager);
        if (prefScreen == null) return;

        Class<?> prefCls = Nsg.load(loader, "androidx.preference.Preference");
        Method findPref = null;
        for (Method m : prefScreenCls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == CharSequence.class) {
                findPref = m;
                break;
            }
        }
        if (findPref != null && findPref.invoke(prefScreen, EDIT_SLOT_KEY) != null) {
            return;
        }

        Method reqCtx = null;
        for (Method m : fragment.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == Context.class) {
                reqCtx = m;
                break;
            }
        }
        if (reqCtx == null) return;
        Context ctx = (Context) reqCtx.invoke(fragment);

        Class<?> ddCls = Nsg.load(loader, "androidx.preference.DropDownPreference");
        Constructor<?> ddCtor = null;
        for (Constructor<?> c : ddCls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0] == Context.class) {
                if (ddCtor == null || p.length < ddCtor.getParameterCount()) ddCtor = c;
            }
        }
        if (ddCtor == null) return;
        ddCtor.setAccessible(true);
        Object[] ctorArgs = new Object[ddCtor.getParameterCount()];
        ctorArgs[0] = ctx;
        for (int i = 1; i < ctorArgs.length; i++) ctorArgs[i] = null;
        Object dropDown = ddCtor.newInstance(ctorArgs);

        Field keyField = null;
        java.util.List<Field> csFields = new java.util.ArrayList<>();
        for (Field f : prefCls.getDeclaredFields()) {
            Class<?> ft = f.getType();
            int mod = f.getModifiers();
            if (ft == String.class && Modifier.isFinal(mod) && keyField == null) {
                keyField = f;
            } else if (ft == CharSequence.class) {
                csFields.add(f);
            }
        }
        if (keyField != null) keyField.setAccessible(true);
        for (Field f : csFields) f.setAccessible(true);

        Class<?> listPrefCls = Nsg.load(loader, "androidx.preference.ListPreference");

        Method setSummaryMethod = null;
        for (Method m : listPrefCls.getDeclaredMethods()) {
            if (m.getReturnType() == void.class && m.getParameterCount() == 1) {
                Class<?> p = m.getParameterTypes()[0];
                if (p == CharSequence.class) {
                    setSummaryMethod = m;
                    setSummaryMethod.setAccessible(true);
                    break;
                }
            }
        }

        Field summaryField = null;
        Field titleField = null;
        if (setSummaryMethod != null && csFields.size() >= 2) {
            try {
                java.util.List<CharSequence> originals = new java.util.ArrayList<>();
                for (Field f : csFields) originals.add((CharSequence) f.get(dropDown));
                setSummaryMethod.invoke(dropDown, (CharSequence) "PROBE");
                for (int pi = 0; pi < csFields.size(); pi++) {
                    CharSequence now = (CharSequence) csFields.get(pi).get(dropDown);
                    if (!java.util.Objects.equals(originals.get(pi), now)) {
                        summaryField = csFields.get(pi);
                        break;
                    }
                }
                setSummaryMethod.invoke(dropDown, (CharSequence) null);
                for (Field f : csFields) {
                    if (f != summaryField) { titleField = f; break; }
                }
            } catch (Throwable t) {
                Log.w(TAG, "PerSimFormatHook: field probe failed: " + t);
            }
        }        if (summaryField == null && csFields.size() >= 2) summaryField = csFields.get(0);
        if (titleField == null && csFields.size() >= 2) titleField = csFields.get(1);

        Field iconSpaceField = null;
        try {
            iconSpaceField = prefCls.getDeclaredField("B"); // iconSpaceReserved
            iconSpaceField.setAccessible(true);
        } catch (Throwable ignored) {}

        Field entriesField = null;
        Field entryValuesField = null;
        for (Field f : listPrefCls.getDeclaredFields()) {
            if (f.getType() == CharSequence[].class) {
                f.setAccessible(true);
                if (entriesField == null) {
                    entriesField = f;
                } else if (entryValuesField == null) {
                    entryValuesField = f;
                    break;
                }
            }
        }

        Field adapterField = null;
        for (Field f : ddCls.getDeclaredFields()) {
            if (f.getType().getName().equals("android.widget.ArrayAdapter")) {
                f.setAccessible(true);
                adapterField = f;
                break;
            }
        }

        CharSequence[] entries = {"Global", "SIM Slot 0", "SIM Slot 1"};
        CharSequence[] entryValues = {"-1", "0", "1"};

        if (entriesField != null) {
            entriesField.set(dropDown, entries);
        }
        if (entryValuesField != null) {
            entryValuesField.set(dropDown, entryValues);
        }
        if (adapterField != null) {
            Object adapter = adapterField.get(dropDown);
            if (adapter != null) {
                Method addMethod = adapter.getClass().getMethod("add", Object.class);
                for (CharSequence entry : entries) {
                    addMethod.invoke(adapter, entry.toString());
                }
            }
        }

        Method setDefaultValue = null;
        for (Method m : prefCls.getDeclaredMethods()) {
            if (m.getReturnType() == void.class) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Object.class) {
                    setDefaultValue = m;
                    setDefaultValue.setAccessible(true);
                    break;
                }
            }
        }

        Method attachMethod = null;
        Class<?> prefMgrType = prefManager.getClass();
        outer:
        for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(prefMgrType) && m.getReturnType() == void.class) {
                    attachMethod = m;
                    break outer;
                }
            }
        }
        if (attachMethod != null) attachMethod.setAccessible(true);

        Class<?> prefGroupCls = Nsg.load(loader, "androidx.preference.PreferenceGroup");
        Field parentField = null;
        outer2:
        for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == prefGroupCls) { parentField = f; break outer2; }
            }
        }
        if (parentField != null) parentField.setAccessible(true);

        Field qField = null;
        for (Field f : prefGroupCls.getDeclaredFields()) {
            if (f.getType() == ArrayList.class) { qField = f; break; }
        }
        if (qField == null) {
            Log.w(TAG, "PerSimFormatHook: children ArrayList not found");
            return;
        }
        qField.setAccessible(true);
        ArrayList<Object> children = (ArrayList<Object>) qField.get(prefScreen);
        if (children == null) {
            return;
        }

        ArrayList<Object> targetChildren = children;
        Object targetParent = prefScreen;
        if (children.size() == 1 && prefGroupCls.isInstance(children.get(0))) {
            Object category = children.get(0);
            ArrayList<Object> catChildren = (ArrayList<Object>) qField.get(category);
            if (catChildren != null) {
                targetChildren = catChildren;
                targetParent = category;
            }
        }

        Field listenerField = null;
        Class<?> listenerCls = null;
        for (Field f : prefCls.getDeclaredFields()) {
            Class<?> ft = f.getType();
            if (ft.isInterface()) {
                for (Method m : ft.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() == boolean.class && p.length == 2
                            && p[0].isAssignableFrom(prefCls) && p[1] == Object.class) {
                        listenerField = f;
                        listenerCls = ft;
                        break;
                    }
                }
                if (listenerField != null) break;
            }
        }
        if (listenerField != null) listenerField.setAccessible(true);

        if (keyField != null) keyField.set(dropDown, EDIT_SLOT_KEY);
        if (titleField != null) titleField.set(dropDown, "Edit Formats For");
        if (setSummaryMethod != null) {
            setSummaryMethod.invoke(dropDown, (CharSequence) "%s");
        } else if (summaryField != null) {
            summaryField.set(dropDown, "%s");
        }
        if (iconSpaceField != null) iconSpaceField.set(dropDown, false);

        for (Field f : prefCls.getDeclaredFields()) {
            if (f.getType() == int.class && !Modifier.isFinal(f.getModifiers())) {
                try {
                    f.setAccessible(true);
                    if (f.getInt(dropDown) == Integer.MAX_VALUE) {
                        f.set(dropDown, 0);
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (setDefaultValue != null) setDefaultValue.invoke(dropDown, "-1");

        final SharedPreferences mainPrefs = ctx.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
        final ArrayList<Object> prefCategoryChildren = targetChildren;
        final Method refreshMethod = setDefaultValue;

        if (listenerField != null && listenerCls != null) {
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listenerCls},
                    (p, m, a) -> {
                        if (a != null && a.length == 2 && m.getReturnType() == boolean.class) {
                            String oldValue = mainPrefs.getString(EDIT_SLOT_KEY, "-1");
                            String newValue = String.valueOf(a[1]);
                            onEditSlotChanged(ctx, oldValue, newValue);
                            refreshListPreferences(prefCategoryChildren, refreshMethod);
                            return Boolean.TRUE;
                        }
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                    });
            listenerField.set(dropDown, proxy);
        }

        targetChildren.add(0, dropDown);
        if (attachMethod != null) attachMethod.invoke(dropDown, prefManager);
        if (parentField != null) parentField.set(dropDown, targetParent);

        if (!listenerRegistered) {
            listenerRegistered = true;
            prefsListener = (sharedPreferences, key) -> onFormatPrefChanged(sharedPreferences, key);
            mainPrefs.registerOnSharedPreferenceChangeListener(prefsListener);
        }

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
            Log.e(TAG, "PerSimFormatHook: adapter refresh failed: " + t);
        }
    }

    private void refreshListPreferences(ArrayList<Object> categoryChildren, Method refreshMethod) {
        if (refreshMethod == null || categoryChildren == null) return;
        Class<?> listPrefCls = Nsg.load(loader, "androidx.preference.ListPreference");
        Class<?> prefCls = Nsg.load(loader, "androidx.preference.Preference");
        Field keyField = null;
        try {
            for (Field f : prefCls.getDeclaredFields()) {
                if (f.getType() == String.class && Modifier.isFinal(f.getModifiers())) {
                    keyField = f;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        if (keyField == null) return;
        keyField.setAccessible(true);

        for (Object pref : categoryChildren) {
            if (pref == null) continue;
            if (listPrefCls != null && !listPrefCls.isInstance(pref)) continue;
            try {
                String key = (String) keyField.get(pref);
                boolean isFormatKey = false;
                for (String fk : FORMAT_KEYS) {
                    if (fk.equals(key)) { isFormatKey = true; break; }
                }
                if (!isFormatKey) continue;
                refreshMethod.invoke(pref, (Object) null);
            } catch (Throwable t) {
                Log.e(TAG, "PerSimFormatHook: refresh failed for pref: " + t);
            }
        }
    }

    private void onEditSlotChanged(Context ctx, String oldSlot, String newSlot) {
        if (oldSlot.equals(newSlot)) return;

        SharedPreferences mainPrefs = ctx.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
        SharedPreferences backupPrefs = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);

        String oldPrefix;
        if ("-1".equals(oldSlot)) {
            oldPrefix = "global_snapshot_";
        } else {
            oldPrefix = "slot" + oldSlot + "_";
        }

        SharedPreferences.Editor backupEditor = backupPrefs.edit();
        for (int i = 0; i < FORMAT_KEYS.length; i++) {
            String key = FORMAT_KEYS[i];
            String value = mainPrefs.getString(key, FORMAT_DEFAULTS[i]);
            backupEditor.putString(oldPrefix + key, value);
        }
        backupEditor.apply();

        suppressListener = true;
        try {
            SharedPreferences.Editor mainEditor = mainPrefs.edit();
            for (int i = 0; i < FORMAT_KEYS.length; i++) {
                String key = FORMAT_KEYS[i];
                String value;
                if ("-1".equals(newSlot)) {
                    value = backupPrefs.getString("global_snapshot_" + key, FORMAT_DEFAULTS[i]);
                } else {
                    value = backupPrefs.getString("slot" + newSlot + "_" + key, null);
                    if (value == null) {
                        value = backupPrefs.getString("global_snapshot_" + key, FORMAT_DEFAULTS[i]);
                    }
                }
                mainEditor.putString(key, value);
            }
            mainEditor.commit();
        } finally {
            suppressListener = false;
        }
    }

    private void onFormatPrefChanged(SharedPreferences mainPrefs, String key) {
        if (suppressListener) return;

        boolean isFormatKey = false;
        for (String fk : FORMAT_KEYS) {
            if (fk.equals(key)) {
                isFormatKey = true;
                break;
            }
        }
        if (!isFormatKey) return;

        String currentSlot = mainPrefs.getString(EDIT_SLOT_KEY, "-1");
        String value = mainPrefs.getString(key, null);
        if (value == null) return;

        try {
            Context ctx = getAppContext();
            if (ctx == null) return;
            SharedPreferences backupPrefs = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
            String prefix;
            if ("-1".equals(currentSlot)) {
                prefix = "global_snapshot_";
            } else {
                prefix = "slot" + currentSlot + "_";
            }
            backupPrefs.edit().putString(prefix + key, value).apply();
        } catch (Throwable t) {
            Log.e(TAG, "PerSimFormatHook: save override failed: " + t);
        }
    }

    private int getActiveSimSlot() {
        try {
            Class<?> wsCls = Nsg.load(loader, Nsg.WORKSPACE);
            if (wsCls == null) return -1;
            Field singletonField = wsCls.getDeclaredField("j");
            singletonField.setAccessible(true);
            Object wsInstance = singletonField.get(null);
            if (wsInstance == null) return -1;
            Field modField = wsCls.getDeclaredField("a");
            modField.setAccessible(true);
            short moduleShort = modField.getShort(wsInstance);
            int nsgSlot = moduleShort >> 4;

            int defaultPhysicalSlot = getDefaultDataPhysicalSlot();
            if (defaultPhysicalSlot < 0) return nsgSlot;
            if (nsgSlot == 0) return defaultPhysicalSlot;
            return defaultPhysicalSlot == 0 ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private int getDefaultDataPhysicalSlot() {
        try {
            Context ctx = getAppContext();
            if (ctx == null) return -1;
            Object sm = ctx.getSystemService("telephony_subscription_service");
            if (sm == null) return -1;
            Class<?> smCls = Class.forName("android.telephony.SubscriptionManager");
            int subId = -1;
            try {
                subId = (Integer) smCls.getMethod("getDefaultDataSubscriptionId").invoke(null);
            } catch (Throwable ignored) {}
            if (subId < 0) {
                try {
                    subId = (Integer) smCls.getMethod("getDefaultSubscriptionId").invoke(null);
                } catch (Throwable ignored) {}
            }
            if (subId < 0) return -1;
            Object info = smCls.getMethod("getActiveSubscriptionInfo", int.class).invoke(sm, subId);
            if (info == null) return -1;
            return (Integer) info.getClass().getMethod("getSimSlotIndex").invoke(info);
        } catch (Throwable t) {
            return -1;
        }
    }

    private Integer getSlotFormatCode(int slot, String key) {
        try {
            Context ctx = getAppContext();
            if (ctx == null) return null;
            SharedPreferences backupPrefs = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
            String value = backupPrefs.getString("slot" + slot + "_" + key, null);
            if (value == null) return null;
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return null;
        }
    }

    private String formatCellId(long cellId, int formatCode, int gnbLength) {
        switch (formatCode) {
            case 0:
                return String.format("%d", (int) cellId);
            case 1:
                return String.format("%X", (int) cellId);
            case 2:
                return String.format("%d / %d", (int) (cellId / 3), (int) (cellId % 3));
            case 3:
                return String.format("%d / %d", (int) (cellId >> 8), (int) (cellId & 255));
            case 4:
                return String.format("%X / %X", (int) (cellId >> 8), (int) (cellId & 255));
            case 5:
                return String.format("%d / %d", (int) (cellId >> 16), (int) (cellId & 65535));
            case 6:
                return String.format("%X / %X", (int) (cellId >> 16), (int) (cellId & 65535));
            case 7:
                return String.format("%03d / %d", (int) cellId, (int) (cellId % 3));
            case 8:
            case 9:
            default:
                return "-";
            case 10:
                return Long.toString(cellId);
            case 11:
                return Long.toHexString(cellId);
            case 12: {
                int shift = 36 - gnbLength;
                return Long.toString(cellId >> shift) + " / " + Long.toString(cellId & (((int) Math.pow(2.0d, shift)) - 1));
            }
            case 13: {
                int shift = 36 - gnbLength;
                return Long.toHexString(cellId >> shift) + " / " + Long.toHexString(cellId & (((int) Math.pow(2.0d, shift)) - 1));
            }
        }
    }

    private static Context getAppContext() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            return (Context) atCls.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
