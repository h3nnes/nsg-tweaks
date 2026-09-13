package com.nsgmod.band;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

public class NrNsaGnbIdHeaderHook {

    private static final String TAG = "NSGBandHook";
    private static final int HOLO_PURPLE = 0xFFCC99FF;
    private static final String CGI_FRAGMENT = "com.qtrun.udv.header.HeaderCGIFragment";
    private static final String KEY_NR_ARFCN = "NR5G::Serving_Cell::NR_ARFCN_SSB";
    private static final String KEY_NR_PCI = "NR5G::Serving_Cell::NR_PCI";
    private static final String KEY_LOCATION_LATITUDE = "Common::Location::Location_Latitude";
    private static final String KEY_LOCATION_LONGITUDE = "Common::Location::Location_Longitude";
    private static final String NR5G_TECH = "NR5G";
    private static final String MAIN_PREFS = "com.qtrun.QuickTest_preferences";
    private static final String BACKUP_PREFS = "nsg_tweaks_per_sim_formats";
    private static final String KEY_NR_GNB_LENGTH = "NR_GNB_LENGTH";
    private static final String KEY_NR_CELLID = "NR_CellID";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean ready = false;
    private Class<?> cgiClass;
    private Field ecellIdValueField;
    private Field ecellIdLabelField;
    private Field tacValueField;
    private Field tacLabelField;
    private Field viewField;
    private Field gField;
    private Field colField;
    private Field widthField;
    private Field wsSingletonField;
    private Field wsRatField;
    private Field wsModuleSlotField;   // Workspace.a (module index; slot nibble)
    private Field sdSingletonField;
    private Method sdEMethod;
    private Method sdIMethod;
    private Method dsGetPropertyMethod;
    private Constructor<?> iterConstructor;
    private Method iterReverseLongMethod;
    private Method iterEndMethod;
    private Method iterValueMethod;
    private Field ydGCellIdField;
    private Field ydLonField;   // h7.b "a" = cell longitude (double)
    private Field ydLatField;   // h7.b "b" = cell latitude (double)
    private Field settingsSingletonField;
    private Field gnbLengthField;
    private Method onLayoutMethod;

    private int cachedArfcn = -1;
    private int cachedPci = -1;
    private long cachedGCellId = -1;
    // NaN = no geo fix observed yet this session; allows null->value transitions
    // to invalidate the cache.
    private double cachedLatitude = Double.NaN;
    private double cachedLongitude = Double.NaN;
    private boolean originalsSaved = false;
    private float origTacCol;
    private float origTacWidth;
    private float origEcellCol;
    private float origEcellWidth;
    private boolean loggedCellDb = false;

    // Identity of the header cell objects the adjusted geometry was last
    // applied to. NSG's per-tick b() path never rewrites the col/width fields
    // (verified against decompiled HeaderCGIFragment on qtrun v4.8.9 and gplay
    // v4.8.8: per-tick it only updates cell texts/values via h0 -> k2.a.j ->
    // v6.a.e(); geometry fields are only assigned at grid build time), so the
    // 8 setFloat + forceOnLayout sequence only needs to run when the target
    // cell instances change (view rebuild) or after restoreGeometry() ran.
    private Object appliedTacLabel;
    private Object appliedTacValue;
    private Object appliedEcellLabel;
    private Object appliedEcellValue;

    public NrNsaGnbIdHeaderHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        try {
            initReflection();
            if (!ready) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: init failed, hook not installed");
                return;
            }

            Class<?> dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Method bMethod = ClassMapping.getDeclaredMethod(cgiClass, CGI_FRAGMENT, "b", loader,
                    dsClass, long.class, short.class, Object.class);
            bMethod.setAccessible(true);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Object fragment = chain.getThisObject();
                        if (!SettingsToggleHook.gnbIdHeaderEnabled()) {
                            restoreGeometry(fragment);
                            return result;
                        }
                        List<Object> args = chain.getArgs();
                        Object dataSource = args.get(0);
                        long timestamp = (Long) args.get(1);
                        short moduleIndex = (Short) args.get(2);
                        updateECellId(fragment, dataSource, timestamp, moduleIndex);
                    } catch (Throwable t) {
                        Log.e(TAG, "NrNsaGnbIdHeaderHook update error", t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "NrNsaGnbIdHeaderHook installed (onLayout: " + (onLayoutMethod != null ? "direct" : "fallback") + ")");
        } catch (Throwable t) {
            Log.e(TAG, "NrNsaGnbIdHeaderHook install failed: " + t);
        }
    }

    private void initReflection() {
        try {
            cgiClass = ClassMapping.loadClass(CGI_FRAGMENT, loader);

            ecellIdValueField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "c1", loader));
            ecellIdValueField.setAccessible(true);

            ecellIdLabelField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "Y0", loader));
            ecellIdLabelField.setAccessible(true);

            tacValueField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "b1", loader));
            tacValueField.setAccessible(true);

            tacLabelField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "X0", loader));
            tacLabelField.setAccessible(true);

            Class<?> eh0Class = ClassMapping.loadClass("v6.g", loader);
            gField = eh0Class.getDeclaredField("g");
            gField.setAccessible(true);

            Class<?> yg0Class = ClassMapping.loadClass("v6.a", loader);
            viewField = yg0Class.getDeclaredField("a");
            viewField.setAccessible(true);
            colField = yg0Class.getDeclaredField("d");
            colField.setAccessible(true);
            widthField = yg0Class.getDeclaredField("e");
            widthField.setAccessible(true);

            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            wsSingletonField = wsClass.getField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader));
            wsRatField = wsClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "d", loader));
            wsRatField.setAccessible(true);

            wsModuleSlotField = wsClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "a", loader));
            wsModuleSlotField.setAccessible(true);

            Class<?> sdClass = ClassMapping.loadClass("f7.b", loader);
            for (Field f : sdClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == sdClass) {
                    sdSingletonField = f;
                    sdSingletonField.setAccessible(true);
                    break;
                }
            }
            if (sdSingletonField == null) throw new NoSuchFieldException("sd singleton not found");

            sdEMethod = ClassMapping.getDeclaredMethod(sdClass, "f7.b", "d", loader, String.class);
            sdEMethod.setAccessible(true);

            for (Method m : sdClass.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[1] == String.class
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == ArrayList.class) {
                    sdIMethod = m;
                    sdIMethod.setAccessible(true);
                    break;
                }
            }
            if (sdIMethod == null) throw new NoSuchMethodException("sd query method not found");

            Class<?> dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            dsGetPropertyMethod = dsClass.getMethod("getProperty", String.class, int.class);

            Class<?> propClass = ClassMapping.loadClass("com.qtrun.sys.Property", loader);
            Class<?> iterClass = ClassMapping.loadClass("com.qtrun.sys.Property$Iterator", loader);
            iterConstructor = iterClass.getDeclaredConstructor(propClass);
            iterConstructor.setAccessible(true);
            iterReverseLongMethod = iterClass.getDeclaredMethod("reverse", long.class);
            iterReverseLongMethod.setAccessible(true);
            iterEndMethod = iterClass.getDeclaredMethod("end");
            iterEndMethod.setAccessible(true);
            iterValueMethod = iterClass.getDeclaredMethod("value");
            iterValueMethod.setAccessible(true);

            Class<?> ydClass = ClassMapping.loadClass("h7.e", loader);
            ydGCellIdField = ydClass.getDeclaredField("g");
            ydGCellIdField.setAccessible(true);

            // Coordinate fields live on the base row class h7.b ("a"=lon, "b"=lat),
            // so they must be looked up with getDeclaredField on h7.b, not h7.e.
            Class<?> ydBaseClass = ClassMapping.loadClass("h7.b", loader);
            ydLonField = ydBaseClass.getDeclaredField("a");
            ydLonField.setAccessible(true);
            ydLatField = ydBaseClass.getDeclaredField("b");
            ydLatField.setAccessible(true);

            try {
                Class<?> settingsClass = ClassMapping.loadClass("d7.a", loader);
                if (settingsClass != null) {
                    settingsSingletonField = settingsClass.getDeclaredField(
                            ClassMapping.runtimeFieldName("d7.a", "l", loader));
                    settingsSingletonField.setAccessible(true);
                    gnbLengthField = settingsClass.getDeclaredField(
                            ClassMapping.runtimeFieldName("d7.a", "k", loader));
                    gnbLengthField.setAccessible(true);
                }
            } catch (Throwable ignored) {
            }

            try {
                Class<?> layoutClass = ClassMapping.loadClass("v6.d", loader);
                onLayoutMethod = layoutClass.getDeclaredMethod("onLayout",
                        boolean.class, int.class, int.class, int.class, int.class);
            } catch (Throwable t) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: onLayout method not found, using fallback");
                onLayoutMethod = null;
            }

            ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "NrNsaGnbIdHeaderHook init failed: " + t);
        }
    }

    private int getGnbLength() {
        int slot = getActiveSimSlot();
        if (slot >= 0) {
            String perSlot = readPref(BACKUP_PREFS, "slot" + slot + "_" + KEY_NR_GNB_LENGTH);
            if (perSlot != null) {
                try {
                    int k = Integer.parseInt(perSlot);
                    if (k >= 22 && k <= 32) return k;
                } catch (NumberFormatException ignored) {}
            }
        }
        String global = readPref(MAIN_PREFS, KEY_NR_GNB_LENGTH);
        if (global != null) {
            try {
                int k = Integer.parseInt(global);
                if (k >= 22 && k <= 32) return k;
            } catch (NumberFormatException ignored) {}
        }
        try {
            if (settingsSingletonField != null && gnbLengthField != null) {
                Object settingsInstance = settingsSingletonField.get(null);
                if (settingsInstance != null) {
                    int k = gnbLengthField.getInt(settingsInstance);
                    if (k >= 22 && k <= 32) return k;
                }
            }
        } catch (Throwable ignored) {
        }
        return 24;
    }

    private int getSlotNrCellIdFormat() {
        int slot = getActiveSimSlot();
        if (slot >= 0) {
            String perSlot = readPref(BACKUP_PREFS, "slot" + slot + "_" + KEY_NR_CELLID);
            if (perSlot != null) {
                try { return Integer.parseInt(perSlot); } catch (NumberFormatException ignored) {}
            }
        }
        String global = readPref(MAIN_PREFS, KEY_NR_CELLID);
        if (global != null) {
            try { return Integer.parseInt(global); } catch (NumberFormatException ignored) {}
        }
        return 12;
    }

    private String readPref(String prefsName, String key) {
        try {
            SharedPreferences p = getPrefs(prefsName);
            if (p == null) return null;
            // Value read stays fresh: SharedPreferences.getString reads the live
            // in-memory map of the shared process-wide instance.
            return p.getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    // SharedPreferences instances are process-wide singletons per name —
    // resolved once, values always read fresh.
    private static volatile SharedPreferences mainPrefs;
    private static volatile SharedPreferences backupPrefs;

    private static SharedPreferences getPrefs(String prefsName) {
        Context ctx = getAppContext();
        if (ctx == null) return null;
        if (MAIN_PREFS.equals(prefsName)) {
            if (mainPrefs == null) {
                synchronized (NrNsaGnbIdHeaderHook.class) {
                    if (mainPrefs == null)
                        mainPrefs = ctx.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
                }
            }
            return mainPrefs;
        }
        if (backupPrefs == null) {
            synchronized (NrNsaGnbIdHeaderHook.class) {
                if (backupPrefs == null)
                    backupPrefs = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE);
            }
        }
        return backupPrefs;
    }

    private int getActiveSimSlot() {
        try {
            // Fresh reads through handles cached at initReflection.
            if (wsSingletonField == null || wsModuleSlotField == null) return -1;
            Object wsInstance = wsSingletonField.get(null);
            if (wsInstance == null) return -1;
            short moduleShort = wsModuleSlotField.getShort(wsInstance);
            int nsgSlot = moduleShort >> 4;
            int defaultPhysicalSlot = getDefaultDataPhysicalSlot();
            if (defaultPhysicalSlot < 0) return nsgSlot;
            if (nsgSlot == 0) return defaultPhysicalSlot;
            return defaultPhysicalSlot == 0 ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    // SubscriptionManager class/method handles — resolved once (lazy, retried
    // until success). All VALUES (subscription ids, slot indices, service
    // state) are still read fresh on every call through the cached handles.
    private static volatile boolean smResolved = false;
    private static Class<?> smClass;
    private static Method   smGetDefaultDataSubId;
    private static Method   smGetDefaultSubId;
    private static Method   smGetActiveSubInfo;
    private static Method   subInfoGetSimSlotIndex;
    private static Object   smService;

    private static synchronized void resolveSubscriptionHandles() {
        if (smResolved) return;
        try {
            smClass = Class.forName("android.telephony.SubscriptionManager");
            smGetDefaultDataSubId = smClass.getMethod("getDefaultDataSubscriptionId");
            smGetDefaultSubId     = smClass.getMethod("getDefaultSubscriptionId");
            smGetActiveSubInfo    = smClass.getMethod("getActiveSubscriptionInfo", int.class);
            Context ctx = getAppContext();
            if (ctx == null) return; // retry next call
            smService = ctx.getSystemService("telephony_subscription_service");
            if (smService == null) return; // retry next call
            smResolved = true;
        } catch (Throwable ignored) {
            // retry next call
        }
    }

    private int getDefaultDataPhysicalSlot() {
        try {
            resolveSubscriptionHandles();
            if (!smResolved) return -1;
            int subId = -1;
            try {
                subId = (Integer) smGetDefaultDataSubId.invoke(null);
            } catch (Throwable ignored) {}
            if (subId < 0) {
                try {
                    subId = (Integer) smGetDefaultSubId.invoke(null);
                } catch (Throwable ignored) {}
            }
            if (subId < 0) return -1;
            Object info = smGetActiveSubInfo.invoke(smService, subId);
            if (info == null) return -1;
            if (subInfoGetSimSlotIndex == null) {
                subInfoGetSimSlotIndex = info.getClass().getMethod("getSimSlotIndex");
            }
            return (Integer) subInfoGetSimSlotIndex.invoke(info);
        } catch (Throwable t) {
            return -1;
        }
    }

    // The Application instance is constant for the process lifetime —
    // resolved once, retried until available.
    private static volatile Context appContext;

    private static Context getAppContext() {
        if (appContext != null) return appContext;
        synchronized (NrNsaGnbIdHeaderHook.class) {
            if (appContext != null) return appContext;
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Context ctx = (Context) atCls.getMethod("currentApplication").invoke(null);
                if (ctx != null) appContext = ctx;
                return ctx;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updateECellId(Object fragment, Object dataSource, long timestamp, short moduleIndex) throws Throwable {
        Object workspace = wsSingletonField.get(null);
        if (workspace == null) {
            restoreGeometry(fragment);
            return;
        }
        Object rat = wsRatField.get(workspace);
        if (rat == null) {
            restoreGeometry(fragment);
            return;
        }
        String ratStr = rat.toString();
        if (!"NR-NSA".equals(ratStr)) {
            restoreGeometry(fragment);
            return;
        }
        adjustGeometry(fragment);

        Long gCellId = resolveGCellIdFromDb(dataSource, timestamp, moduleIndex);
        if (gCellId == null) return;
        injectGnbIdText(fragment, gCellId);
    }

    @SuppressWarnings("unchecked")
    private Long resolveGCellIdFromDb(Object dataSource, long timestamp, short moduleIndex) throws Throwable {
        Object sdInstance = sdSingletonField.get(null);
        if (sdInstance == null) {
            if (!loggedCellDb) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: cell DB singleton null");
                loggedCellDb = true;
            }
            return null;
        }
        Object nrTable = sdEMethod.invoke(sdInstance, NR5G_TECH);
        if (nrTable == null) {
            if (!loggedCellDb) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: NR5G table not loaded in cell DB");
                loggedCellDb = true;
            }
            return null;
        }
        loggedCellDb = false;

        if (dataSource == null) return null;

        Integer arfcn = null;
        Integer pci = null;
        for (int mi = 0; mi <= 3; mi++) {
            arfcn = readIntProperty(dataSource, mi, timestamp, KEY_NR_ARFCN);
            pci = readIntProperty(dataSource, mi, timestamp, KEY_NR_PCI);
            if (arfcn != null && pci != null) break;
        }
        if (arfcn == null || pci == null) {
            int miUsed = moduleIndex & 0xFFFF;
            arfcn = readIntProperty(dataSource, miUsed, timestamp, KEY_NR_ARFCN);
            pci = readIntProperty(dataSource, miUsed, timestamp, KEY_NR_PCI);
        }
        if (arfcn == null || pci == null) {
            return null;
        }

        Double lat = null;
        Double lon = null;
        for (int mi = 0; mi <= 3; mi++) {
            lat = readDoubleProperty(dataSource, mi, timestamp, KEY_LOCATION_LATITUDE);
            lon = readDoubleProperty(dataSource, mi, timestamp, KEY_LOCATION_LONGITUDE);
            if (lat != null && lon != null) break;
        }
        if (lat == null || lon == null) {
            int miUsed = moduleIndex & 0xFFFF;
            lat = readDoubleProperty(dataSource, miUsed, timestamp, KEY_LOCATION_LATITUDE);
            lon = readDoubleProperty(dataSource, miUsed, timestamp, KEY_LOCATION_LONGITUDE);
        }

        boolean locationChanged = (lat == null) != Double.isNaN(cachedLatitude)
                || (lon == null) != Double.isNaN(cachedLongitude)
                || (lat != null && lon != null
                        && (Math.abs(lat - cachedLatitude) > 0.002
                                || Math.abs(lon - cachedLongitude) > 0.002));

        if (arfcn != cachedArfcn || pci != cachedPci || locationChanged) {
            cachedArfcn = arfcn;
            cachedPci = pci;
            cachedLatitude = (lat == null) ? Double.NaN : lat;
            cachedLongitude = (lon == null) ? Double.NaN : lon;
            cachedGCellId = -1;
            if (lat != null && lon != null) {
                // Geo-filtered query, matching NSG's native subrow lookup exactly.
                String where = String.format(java.util.Locale.US,
                        "nr_arfcn=%d and nr_pci=%d and Longitude between %.2f and %.2f"
                                + " and Latitude between %.2f and %.2f",
                        arfcn, pci, lon - 0.2, lon + 0.2, lat - 0.2, lat + 0.2);
                Object result = sdIMethod.invoke(sdInstance, nrTable, where);
                if (result instanceof List) {
                    double bestDist = 50000.0;
                    Object bestRow = null;
                    for (Object row : (List<Object>) result) {
                        if (row == null) continue;
                        double dist = haversine(lat, lon,
                                ydLatField.getDouble(row), ydLonField.getDouble(row));
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestRow = row;
                        }
                    }
                    if (bestRow != null) {
                        cachedGCellId = ydGCellIdField.getLong(bestRow);
                    }
                }
            } else {
                // Legacy fallback (no geo fix): first row, identical to before.
                String where = "nr_arfcn=" + arfcn + " and nr_pci=" + pci;
                Object result = sdIMethod.invoke(sdInstance, nrTable, where);
                if (result instanceof List && !((List<?>) result).isEmpty()) {
                    Object row = ((List<Object>) result).get(0);
                    cachedGCellId = ydGCellIdField.getLong(row);
                }
            }
        }
        if (cachedGCellId <= 0) return null;
        return cachedGCellId;
    }

    // Exact copy of NSG's ce.a(lat1, lon1, lat2, lon2) haversine distance (meters).
    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double d5 = lat1 * Math.PI / 180.0;
        double d6 = lat2 * Math.PI / 180.0;
        return Math.asin(Math.sqrt(Math.pow(Math.sin(((lon1 * Math.PI / 180.0) - (lon2 * Math.PI / 180.0)) / 2.0), 2.0)
                * Math.cos(d6) * Math.cos(d5) + Math.pow(Math.sin((d5 - d6) / 2.0), 2.0))) * 2.0 * 6378137.0;
    }

    private void adjustGeometry(Object fragment) throws Throwable {
        Object tacLabel = tacLabelField.get(fragment);
        Object tacValue = tacValueField.get(fragment);
        Object ecellIdLabel = ecellIdLabelField.get(fragment);
        Object ecellIdValue = ecellIdValueField.get(fragment);
        if (tacLabel == null || tacValue == null || ecellIdLabel == null || ecellIdValue == null)
            return;
        // Already applied to these exact cell instances — the geometry fields
        // are write-once (nothing rewrites them between ticks), so skip the
        // redundant 8x setFloat + requestLayout + posted onLayout work.
        if (tacLabel == appliedTacLabel && tacValue == appliedTacValue
                && ecellIdLabel == appliedEcellLabel && ecellIdValue == appliedEcellValue)
            return;
        if (!originalsSaved) {
            origTacCol = colField.getFloat(tacLabel);
            origTacWidth = widthField.getFloat(tacLabel);
            origEcellCol = colField.getFloat(ecellIdLabel);
            origEcellWidth = widthField.getFloat(ecellIdLabel);
            originalsSaved = true;
        }
        colField.setFloat(tacLabel, 36.0f);
        widthField.setFloat(tacLabel, 19.0f);
        colField.setFloat(tacValue, 36.0f);
        widthField.setFloat(tacValue, 19.0f);
        colField.setFloat(ecellIdLabel, 55.0f);
        widthField.setFloat(ecellIdLabel, 44.0f);
        colField.setFloat(ecellIdValue, 55.0f);
        widthField.setFloat(ecellIdValue, 44.0f);
        cacheLayoutAndRequest(fragment, tacLabel, tacValue, ecellIdLabel, ecellIdValue);
        appliedTacLabel   = tacLabel;
        appliedTacValue   = tacValue;
        appliedEcellLabel = ecellIdLabel;
        appliedEcellValue = ecellIdValue;
    }

    private void restoreGeometry(Object fragment) throws Throwable {
        // Forget what the adjusted geometry was applied to so the next NR-NSA
        // tick re-applies it (legacy behaviour re-applied unconditionally).
        appliedTacLabel = appliedTacValue = appliedEcellLabel = appliedEcellValue = null;
        if (!originalsSaved) return;
        Object tacLabel = tacLabelField.get(fragment);
        Object tacValue = tacValueField.get(fragment);
        Object ecellIdLabel = ecellIdLabelField.get(fragment);
        Object ecellIdValue = ecellIdValueField.get(fragment);
        boolean changed = false;
        if (tacLabel != null) {
            float c = colField.getFloat(tacLabel), w = widthField.getFloat(tacLabel);
            colField.setFloat(tacLabel, origTacCol);
            widthField.setFloat(tacLabel, origTacWidth);
            if (c != origTacCol || w != origTacWidth) changed = true;
        }
        if (tacValue != null) {
            float c = colField.getFloat(tacValue), w = widthField.getFloat(tacValue);
            colField.setFloat(tacValue, origTacCol);
            widthField.setFloat(tacValue, origTacWidth);
            if (c != origTacCol || w != origTacWidth) changed = true;
        }
        if (ecellIdLabel != null) {
            float c = colField.getFloat(ecellIdLabel), w = widthField.getFloat(ecellIdLabel);
            colField.setFloat(ecellIdLabel, origEcellCol);
            widthField.setFloat(ecellIdLabel, origEcellWidth);
            if (c != origEcellCol || w != origEcellWidth) changed = true;
        }
        if (ecellIdValue != null) {
            float c = colField.getFloat(ecellIdValue), w = widthField.getFloat(ecellIdValue);
            colField.setFloat(ecellIdValue, origEcellCol);
            widthField.setFloat(ecellIdValue, origEcellWidth);
            if (c != origEcellCol || w != origEcellWidth) changed = true;
        }
        if (changed) {
            cacheLayoutAndRequest(fragment, tacLabel, tacValue, ecellIdLabel, ecellIdValue);
        }
    }

    private void cacheLayoutAndRequest(Object fragment, Object tacLabel, Object tacValue,
                                        Object ecellIdLabel, Object ecellIdValue) {
        View layout = getLayoutView(ecellIdValue);
        if (layout == null) layout = getLayoutView(ecellIdLabel);
        if (layout == null) layout = getLayoutView(tacLabel);
        if (layout == null) layout = getLayoutView(tacValue);
        if (layout == null) return;
        forceOnLayout(layout);
    }

    private void forceOnLayout(View layout) {
        if (onLayoutMethod == null) {
            layout.requestLayout();
            return;
        }
        layout.requestLayout();
        layout.post(() -> {
            try {
                if (layout.getWidth() > 0 && layout.getHeight() > 0) {
                    onLayoutMethod.setAccessible(true);
                    onLayoutMethod.invoke(layout, true,
                            layout.getLeft(), layout.getTop(),
                            layout.getRight(), layout.getBottom());
                }
            } catch (Throwable t) {
                Log.e(TAG, "NrNsaGnbIdHeaderHook forceOnLayout post error", t);
            }
        });
    }

    private View getLayoutView(Object elem) {
        try {
            if (elem == null) return null;
            Object viewObj = viewField.get(elem);
            if (!(viewObj instanceof View)) return null;
            ViewParent parent = ((View) viewObj).getParent();
            if (parent instanceof View) return (View) parent;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void injectGnbIdText(Object fragment, long nci) throws Throwable {
        int gnbLength = getGnbLength();
        int shift = 36 - gnbLength;
        long gnbId = nci >> shift;
        long sector = nci & ((1L << shift) - 1);

        Object b1 = ecellIdValueField.get(fragment);
        if (b1 == null) return;
        Object textObj = gField.get(b1);
        if (!(textObj instanceof String)) return;
        String text = (String) textObj;
        if (text.isEmpty() || "-".equals(text)) return;
        if (text.contains(" || ")) return;

        int formatCode = getSlotNrCellIdFormat();
        String gnbText;
        if (formatCode == 13) {
            gnbText = Long.toHexString(gnbId) + " / " + Long.toHexString(sector);
        } else {
            gnbText = gnbId + " / " + sector;
        }
        String separator = " || ";
        String fullText = text + separator + gnbText;

        gField.set(b1, fullText);

        Object view = viewField.get(b1);
        if (view instanceof TextView) {
            SpannableString span = new SpannableString(fullText);
            int colorStart = text.length() + separator.length();
            span.setSpan(new ForegroundColorSpan(HOLO_PURPLE), colorStart, fullText.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ((TextView) view).setText(span);
        }
    }

    private Integer readIntProperty(Object dataSource, int moduleIndex, long timestamp, String key) {
        Object value = readProperty(dataSource, moduleIndex, timestamp, key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }

    private Double readDoubleProperty(Object dataSource, int moduleIndex, long timestamp, String key) {
        Object value = readProperty(dataSource, moduleIndex, timestamp, key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return null;
    }

    private Object readProperty(Object dataSource, int moduleIndex, long timestamp, String key) {
        try {
            Object prop = dsGetPropertyMethod.invoke(dataSource, key, moduleIndex);
            if (prop == null) return null;
            Object iter = iterConstructor.newInstance(prop);
            iterReverseLongMethod.invoke(iter, timestamp);
            if ((boolean) iterEndMethod.invoke(iter)) return null;
            return iterValueMethod.invoke(iter);
        } catch (Throwable t) {
            return null;
        }
    }
}
