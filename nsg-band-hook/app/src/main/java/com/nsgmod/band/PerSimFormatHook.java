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

public class PerSimFormatHook {

    private static final String TAG = "NSGBandHook";
    private static final String MAIN_PREFS = "com.qtrun.QuickTest_preferences";
    private static final String BACKUP_PREFS = "nsg_tweaks_per_sim_formats";
    private static final String EDIT_SLOT_KEY = "nsgmod.per_sim_edit_slot";
    private static final String[] FORMAT_KEYS = {
        "GSM_CellID", "GSM_LAC", "WCDMA_LAC", "WCDMA_CellID",
        "LTE_PCI", "LTE_TAC", "LTE_CellID", "NR_CellID", "NR_GNB_LENGTH"
    };
    private static final String[] TYPE_INDEX_TO_KEY = {
        "GSM_CellID", "GSM_LAC", "WCDMA_LAC", "WCDMA_CellID",
        "LTE_PCI", "LTE_TAC", "LTE_CellID", "NR_CellID"
    };
    private static final long SLOT_CACHE_TTL_MS = 5000L;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Method sr0Method;
    private Method formatFragmentMethod;

    private static volatile int cachedSlot = -1;
    private static volatile long cachedSlotTime = 0;
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
                            Log.w(TAG, "PerSimFormatHook inject failed: " + t, t);
                        }
                        return result;
                    }
                });
            } else {
                Log.w(TAG, "PerSimFormatHook: format fragment not found, UI disabled");
            }

            if (sr0Method != null) {
                xposed.hook(sr0Method).intercept(new Hooker() {
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
                Log.w(TAG, "PerSimFormatHook: sr0 method not found, runtime disabled");
            }

            Log.i(TAG, "PerSimFormatHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "PerSimFormatHook install failed: " + t, t);
        }
    }

    private void initReflection() {
        try {
            Class<?> sr0Cls = ClassMapping.loadClass("sr0", loader);
            if (sr0Cls != null) {
                sr0Method = ClassMapping.getDeclaredMethod(sr0Cls, "sr0", "a", loader, long.class, int.class);
                sr0Method.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PerSimFormatHook: sr0 not found: " + t);
        }

        try {
            Class<?> jsCls = ClassMapping.loadClass("js", loader);
            if (jsCls != null) {
                formatFragmentMethod = ClassMapping.getDeclaredMethod(jsCls, "js", "f0", loader, String.class);
                formatFragmentMethod.setAccessible(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PerSimFormatHook: format fragment method not found: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectSlotSelector(Object fragment) throws Throwable {
        Class<?> baseCls = ClassMapping.loadClass("androidx.preference.c", loader);
        Field yField = baseCls.getField(ClassMapping.runtimeFieldName("androidx.preference.c", "Y", loader));
        Object prefManager = yField.get(fragment);
        if (prefManager == null) {
            Log.w(TAG, "PerSimFormatHook: PreferenceManager null");
            return;
        }

        Class<?> prefScreenCls = ClassMapping.loadClass("androidx.preference.PreferenceScreen", loader);
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
        if (screenField == null) {
            Log.w(TAG, "PerSimFormatHook: PreferenceScreen field not found");
            return;
        }
        screenField.setAccessible(true);
        Object prefScreen = screenField.get(prefManager);
        if (prefScreen == null) {
            Log.w(TAG, "PerSimFormatHook: PreferenceScreen null");
            return;
        }

        Class<?> prefCls = ClassMapping.loadClass("androidx.preference.Preference", loader);
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
        if (reqCtx == null) {
            Log.w(TAG, "PerSimFormatHook: requireContext() not found");
            return;
        }
        Context ctx = (Context) reqCtx.invoke(fragment);

        Class<?> ddCls = ClassMapping.loadClass("androidx.preference.DropDownPreference", loader);
        Constructor<?> ddCtor = null;
        for (Constructor<?> c : ddCls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0] == Context.class) {
                if (ddCtor == null || p.length < ddCtor.getParameterCount()) ddCtor = c;
            }
        }
        if (ddCtor == null) {
            Log.w(TAG, "PerSimFormatHook: DropDownPreference ctor not found");
            return;
        }
        ddCtor.setAccessible(true);
        Object[] ctorArgs = new Object[ddCtor.getParameterCount()];
        ctorArgs[0] = ctx;
        for (int i = 1; i < ctorArgs.length; i++) ctorArgs[i] = null;
        Object dropDown = ddCtor.newInstance(ctorArgs);

        Field keyField = null;
        Field titleField = null;
        Field summaryField = null;
        for (Field f : prefCls.getDeclaredFields()) {
            Class<?> ft = f.getType();
            int mod = f.getModifiers();
            if (ft == String.class && Modifier.isFinal(mod) && keyField == null) {
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

        Field iconSpaceField = null;
        try {
            String iconSpaceName = ClassMapping.runtimeFieldName("androidx.preference.Preference", "C", loader);
            iconSpaceField = prefCls.getDeclaredField(iconSpaceName);
            iconSpaceField.setAccessible(true);
        } catch (Throwable ignored) {}

        Class<?> listPrefCls = ClassMapping.loadClass("androidx.preference.ListPreference", loader);
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

        Log.w(TAG, "PerSimFormatHook: entriesField=" + entriesField
                + " entryValuesField=" + entryValuesField
                + " adapterField=" + adapterField);

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

        Field listPrefSummaryField = null;
        for (Field f : listPrefCls.getDeclaredFields()) {
            if (f.getType() == String.class && !Modifier.isFinal(f.getModifiers())) {
                listPrefSummaryField = f;
            }
        }
        if (listPrefSummaryField != null) {
            listPrefSummaryField.setAccessible(true);
            Log.w(TAG, "PerSimFormatHook: listPrefSummaryField=" + listPrefSummaryField.getName());
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

        Class<?> prefGroupCls = ClassMapping.loadClass("androidx.preference.PreferenceGroup", loader);
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
            Log.w(TAG, "PerSimFormatHook: children ArrayList null");
            return;
        }

        Log.w(TAG, "PerSimFormatHook: prefScreen children count=" + children.size()
                + " firstChild=" + (children.isEmpty() ? "null" : children.get(0).getClass().getName()));

        ArrayList<Object> targetChildren = children;
        Object targetParent = prefScreen;
        if (children.size() == 1 && prefGroupCls.isInstance(children.get(0))) {
            Object category = children.get(0);
            ArrayList<Object> catChildren = (ArrayList<Object>) qField.get(category);
            if (catChildren != null) {
                Log.w(TAG, "PerSimFormatHook: inserting into PreferenceCategory, catChildren=" + catChildren.size());
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

        Log.w(TAG, "PerSimFormatHook: keyField=" + keyField
                + " titleField=" + titleField
                + " summaryField=" + summaryField
                + " listenerField=" + listenerField
                + " listenerCls=" + listenerCls);

        if (keyField != null) keyField.set(dropDown, EDIT_SLOT_KEY);
        if (titleField != null) titleField.set(dropDown, "Edit Formats For");
        if (summaryField != null) summaryField.set(dropDown, "%s");
        if (listPrefSummaryField != null) listPrefSummaryField.set(dropDown, "%s");
        if (iconSpaceField != null) iconSpaceField.set(dropDown, false);
        if (setDefaultValue != null) setDefaultValue.invoke(dropDown, "-1");

        final SharedPreferences mainPrefs = ctx.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);

        if (listenerField != null && listenerCls != null) {
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listenerCls},
                    (p, m, a) -> {
                        Log.w(TAG, "PerSimFormatHook: listener invoked, method=" + m.getName()
                                + " args=" + (a == null ? "null" : a.length));
                        if (a != null && a.length == 2 && m.getReturnType() == boolean.class) {
                            String oldValue = mainPrefs.getString(EDIT_SLOT_KEY, "-1");
                            String newValue = String.valueOf(a[1]);
                            Log.w(TAG, "PerSimFormatHook: slot change " + oldValue + " -> " + newValue);
                            onEditSlotChanged(ctx, oldValue, newValue);
                            return Boolean.TRUE;
                        }
                        if (m.getReturnType() == boolean.class) return Boolean.FALSE;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                    });
            listenerField.set(dropDown, proxy);
            Log.w(TAG, "PerSimFormatHook: listener proxy set");
        } else {
            Log.w(TAG, "PerSimFormatHook: listener field not found, slot switching disabled");
        }

        targetChildren.add(0, dropDown);
        if (attachMethod != null) attachMethod.invoke(dropDown, prefManager);
        if (parentField != null) parentField.set(dropDown, targetParent);

        Log.w(TAG, "PerSimFormatHook: inserted at pos 0, targetChildren size=" + targetChildren.size());

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
            Log.w(TAG, "PerSimFormatHook: adapter refresh failed: " + t);
        }
    }

    private void onEditSlotChanged(Context ctx, String oldSlot, String newSlot) {
        Log.w(TAG, "PerSimFormatHook: onEditSlotChanged " + oldSlot + " -> " + newSlot);
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
        for (String key : FORMAT_KEYS) {
            String value = mainPrefs.getString(key, null);
            if (value != null) {
                backupEditor.putString(oldPrefix + key, value);
            }
        }
        backupEditor.apply();

        suppressListener = true;
        try {
            SharedPreferences.Editor mainEditor = mainPrefs.edit();
            for (String key : FORMAT_KEYS) {
                String value = null;
                if ("-1".equals(newSlot)) {
                    value = backupPrefs.getString("global_snapshot_" + key, null);
                } else {
                    value = backupPrefs.getString("slot" + newSlot + "_" + key, null);
                    if (value == null) {
                        value = backupPrefs.getString("global_snapshot_" + key, null);
                    }
                }
                if (value != null) {
                    mainEditor.putString(key, value);
                }
            }
            mainEditor.apply();
        } finally {
            suppressListener = false;
        }
    }

    private void onFormatPrefChanged(SharedPreferences mainPrefs, String key) {
        if (suppressListener) return;

        Log.w(TAG, "PerSimFormatHook: prefChanged key=" + key);

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
            Log.w(TAG, "PerSimFormatHook: save override failed: " + t);
        }
    }

    private int getActiveSimSlot() {
        long now = System.currentTimeMillis();
        if (cachedSlot >= 0 && (now - cachedSlotTime) < SLOT_CACHE_TTL_MS) {
            return cachedSlot;
        }
        try {
            Context ctx = getAppContext();
            if (ctx == null) {
                cachedSlot = -1;
                cachedSlotTime = now;
                return -1;
            }
            Object sm = ctx.getSystemService("telephony_subscription_service");
            if (sm == null) {
                cachedSlot = -1;
                cachedSlotTime = now;
                return -1;
            }
            Class<?> smCls = Class.forName("android.telephony.SubscriptionManager");
            int defaultSubId = (Integer) smCls.getMethod("getDefaultSubscriptionId").invoke(null);
            if (defaultSubId < 0) {
                cachedSlot = -1;
                cachedSlotTime = now;
                return -1;
            }
            Object info = smCls.getMethod("getActiveSubscriptionInfo", int.class).invoke(sm, defaultSubId);
            if (info == null) {
                cachedSlot = -1;
                cachedSlotTime = now;
                return -1;
            }
            int slot = (Integer) info.getClass().getMethod("getSimSlotIndex").invoke(info);
            cachedSlot = slot;
            cachedSlotTime = now;
            return slot;
        } catch (Throwable t) {
            cachedSlot = -1;
            cachedSlotTime = now;
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
