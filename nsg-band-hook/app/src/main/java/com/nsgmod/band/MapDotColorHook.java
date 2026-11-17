package com.nsgmod.band;

import android.content.ContextWrapper;
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MapDotColorHook {

    private static final String TAG = "NSGBandHook";

    static final HashMap<String, HashMap<String, String>> INJECTED_OPTIONS = new HashMap<>();

    static {
        HashMap<String, String> commonSinr = new HashMap<>();
        commonSinr.put("GSM", "GSM::Downlink_Measurements::ServC2I");
        commonSinr.put("WCDMA", "WCDMA::Downlink_Measurements::Uu_Serving_EcNo");
        commonSinr.put("TDSCDMA", "TDSCDMA::Downlink_Measurements::Uu_PCCPCH_C2I");
        commonSinr.put("CDMA", "CDMA::Pilot_Measurements::EcIo_Combined");
        commonSinr.put("EVDO", "EvDo::Pilot_Measurements::EV_SINR_Best");
        commonSinr.put("LTE", "LTE::Downlink_Measurements::LTE_SINR_PCell");
        commonSinr.put("NR5G", "NR5G::Downlink_Measurements::NR_SS_SINR");
        INJECTED_OPTIONS.put("Common_SINR", commonSinr);

        HashMap<String, String> nrPhr = new HashMap<>();
        nrPhr.put("NR_Power_Headroom", "NR5G::Uplink_Measurements::NR_Power_Headroom");
        INJECTED_OPTIONS.put("NR_Power_Headroom", nrPhr);

        HashMap<String, String> ltePhr = new HashMap<>();
        ltePhr.put("LTE_Power_Headroom", "LTE::Uplink_Measurements::LTE_AvgPHR_UL");
        INJECTED_OPTIONS.put("LTE_Power_Headroom", ltePhr);

        HashMap<String, String> nrTput = new HashMap<>();
        nrTput.put("NR_Throughput_DL", "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL");
        INJECTED_OPTIONS.put("NR_Throughput_DL", nrTput);

        HashMap<String, String> lteTput = new HashMap<>();
        lteTput.put("LTE_Throughput_DL", "LTE::Downlink_Measurements::LTE_Physical_Throughput_DL");
        INJECTED_OPTIONS.put("LTE_Throughput_DL", lteTput);

        HashMap<String, String> nrTputUl = new HashMap<>();
        nrTputUl.put("NR_Throughput_UL", "NR5G::Uplink_Measurements::NR_Physical_Throughput_UL");
        INJECTED_OPTIONS.put("NR_Throughput_UL", nrTputUl);

        HashMap<String, String> lteTputUl = new HashMap<>();
        lteTputUl.put("LTE_Throughput_UL", "LTE::Uplink_Measurements::LTE_Physical_Throughput_UL");
        INJECTED_OPTIONS.put("LTE_Throughput_UL", lteTputUl);
    }

    // NSG v4.8.14 targets
    private static final String PRESENTATION_MANAGER =
            "com.qtrun.legend.Presentation.PresentationManager"; // enum; e singleton, a common / b singles lists, a(ContextWrapper) init
    private static final String LEGEND_MANAGER =
            "com.qtrun.legend.LegendManager";   // enum; e singleton, b legend defs, c attr->legend name, a(m8,double) color lookup
    private static final String MAP_OPTION = "rf0";    // map option: a label, b key, c attr map, d isCommon
    private static final String OPTION_UTIL = "gw0";   // static b(rf0) -> HashMap
    private static final String LEGEND_DEF = "d20";    // legend def: a name, b stops, c max, d min; no constructor
    private static final String LEGEND_STOPS = "nr0";  // R8-merged class; nr0(15) = ArrayList holder in field b
    private static final String LEGEND_STOP = "ni0";   // stop (double threshold, int color)
    private static final String ATTR = "m8";           // attribute (extends l8, whose field a is the key)
    private static final String ATTR_BASE = "l8";
    private static final String CONTEXT_W4 = "w4";     // second parameter of rf0.a(String, w4)

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public MapDotColorHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installPresentationManagerHook();
        installLegendManagerHook();
        installOptionUtilNullSafeHook();
        installOptionLookupFallbackHook();
    }

    private void installPresentationManagerHook() {
        try {
            Class<?> pmClass = Nsg.load(loader, PRESENTATION_MANAGER);
            Method initMethod = pmClass.getDeclaredMethod("a", ContextWrapper.class);

            xposed.hook(initMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        injectOptions();
                    } catch (Throwable t) {
                        Log.e(TAG, "MapDotColorHook: injectOptions failed: " + t);
                    }
                    return result;
                }
            });
            Log.i(TAG, "MapDotColorHook: PresentationManager hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: PresentationManager hook failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectOptions() throws Throwable {
        Class<?> pmClass = Nsg.load(loader, PRESENTATION_MANAGER);
        Field singletonField = pmClass.getDeclaredField("e");
        singletonField.setAccessible(true);
        Object pmInstance = singletonField.get(null);

        Field commonListField = pmClass.getDeclaredField("a");
        Field singlesListField = pmClass.getDeclaredField("b");
        commonListField.setAccessible(true);
        singlesListField.setAccessible(true);
        List<Object> commonList = (List<Object>) commonListField.get(pmInstance);
        List<Object> singlesList = (List<Object>) singlesListField.get(pmInstance);

        Class<?> optClass = Nsg.load(loader, MAP_OPTION);
        Field optLabel = optClass.getDeclaredField("a");
        Field optKey = optClass.getDeclaredField("b");
        Field optAttrs = optClass.getDeclaredField("c");
        Field optCommon = optClass.getDeclaredField("d");
        optLabel.setAccessible(true);
        optKey.setAccessible(true);
        optAttrs.setAccessible(true);
        optCommon.setAccessible(true);
        Constructor<?> optCtor = optClass.getDeclaredConstructor();
        optCtor.setAccessible(true);

        Object commonSinr = optCtor.newInstance();
        optLabel.set(commonSinr, "Common SINR");
        optKey.set(commonSinr, "Common_SINR");
        ((HashMap<String, String>) optAttrs.get(commonSinr))
                .putAll(INJECTED_OPTIONS.get("Common_SINR"));
        optCommon.setBoolean(commonSinr, false);
        commonList.add(commonSinr);

        String[][] singles = {
                {"NR Power Headroom", "NR_Power_Headroom"},
                {"LTE Power Headroom", "LTE_Power_Headroom"},
                {"NR Throughput DL", "NR_Throughput_DL"},
                {"LTE Throughput DL", "LTE_Throughput_DL"},
                {"NR Throughput UL", "NR_Throughput_UL"},
                {"LTE Throughput UL", "LTE_Throughput_UL"},
        };
        for (String[] single : singles) {
            Object option = optCtor.newInstance();
            optLabel.set(option, single[0]);
            optKey.set(option, single[1]);
            ((HashMap<String, String>) optAttrs.get(option))
                    .putAll(INJECTED_OPTIONS.get(single[1]));
            optCommon.setBoolean(option, true);
            singlesList.add(option);
        }

        injectLegendEntries();
    }

    @SuppressWarnings("unchecked")
    private void injectLegendEntries() throws Throwable {
        Class<?> lmClass = Nsg.load(loader, LEGEND_MANAGER);
        Field singletonField = lmClass.getDeclaredField("e");
        singletonField.setAccessible(true);
        Object lmInstance = singletonField.get(null);

        Field attrMapField = lmClass.getDeclaredField("c");
        attrMapField.setAccessible(true);
        HashMap<String, String> attrMap = (HashMap<String, String>) attrMapField.get(lmInstance);

        Field defMapField = lmClass.getDeclaredField("b");
        defMapField.setAccessible(true);
        HashMap<String, Object> defMap = (HashMap<String, Object>) defMapField.get(lmInstance);

        Class<?> defClass = Nsg.load(loader, LEGEND_DEF);
        Class<?> stopsClass = Nsg.load(loader, LEGEND_STOPS);
        Class<?> stopClass = Nsg.load(loader, LEGEND_STOP);

        Constructor<?> stopsCtor = stopsClass.getDeclaredConstructor(int.class);
        stopsCtor.setAccessible(true);
        Constructor<?> stopCtor = stopClass.getDeclaredConstructor(double.class, int.class);
        stopCtor.setAccessible(true);

        Field defName = defClass.getDeclaredField("a");
        Field defStops = defClass.getDeclaredField("b");
        Field defMax = defClass.getDeclaredField("c");
        Field defMin = defClass.getDeclaredField("d");
        defName.setAccessible(true);
        defStops.setAccessible(true);
        defMax.setAccessible(true);
        defMin.setAccessible(true);

        Field stopsList = stopsClass.getDeclaredField("b");
        stopsList.setAccessible(true);

        // d20 has no constructor in v4.8.14: allocate it with sun.misc.Unsafe.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField;
        try {
            unsafeField = unsafeClass.getDeclaredField("THE_ONE");
        } catch (NoSuchFieldException e) {
            unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        }
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        allocate.setAccessible(true);

        LegendFactory f = new LegendFactory(attrMap, defMap, unsafe, allocate, defClass,
                stopsCtor, stopCtor, defName, defStops, defMax, defMin, stopsList);

        double[] hrThresholds = {-10, 5, 10, 15, 25, 35};
        int[] hrColors = {0xFFF44336, 0xFFFF9800, 0xFFFBC02D, 0xFF43A047, 0xFF2E7D32, 0xFF1B5E20};
        f.inject("NR5G::Uplink_Measurements::NR_Power_Headroom", "NR_Power_Headroom",
                hrThresholds, hrColors, 40, -10);
        f.inject("LTE::Uplink_Measurements::LTE_AvgPHR_UL", "LTE_Power_Headroom",
                hrThresholds, hrColors, 40, -10);

        double[] tputThresholds = {0, 5, 10, 20, 50, 100, 200, 300, 500, 800, 1000};
        int[] tputColors = {0xFF8B0000, 0xFFC03030, 0xFFE06030, 0xFFF08020, 0xFFF0B020,
                0xFFE0E030, 0xFFA0C020, 0xFF60C020, 0xFF20A040, 0xFF208040, 0xFF1B5E20};
        f.inject("NR5G::Downlink_Measurements::NR_Physical_Throughput_DL", "NR_Throughput_DL",
                tputThresholds, tputColors, 2000, 0);
        f.inject("LTE::Downlink_Measurements::LTE_Physical_Throughput_DL", "LTE_Throughput_DL",
                tputThresholds, tputColors, 2000, 0);

        double[] ulTputThresholds = {0, 5, 10, 20, 30, 40, 50, 60, 80, 100, 120, 140, 160, 180, 200, 250, 300, 500};
        int[] ulTputColors = {
                0xFF6B0000,
                0xFF8B0000,
                0xFFB03030,
                0xFFD04030,
                0xFFE05020,
                0xFFF06010,
                0xFFF08020,
                0xFFF0A020,
                0xFFF0C020,
                0xFFF0E020,
                0xFFE0E020,
                0xFFC0E020,
                0xFFA0D020,
                0xFF80C020,
                0xFF60B020,
                0xFF40A030,
                0xFF2E9030,
                0xFF1B5E20
        };
        f.inject("NR5G::Uplink_Measurements::NR_Physical_Throughput_UL", "NR_Throughput_UL",
                ulTputThresholds, ulTputColors, 600, 0);
        f.inject("LTE::Uplink_Measurements::LTE_Physical_Throughput_UL", "LTE_Throughput_UL",
                ulTputThresholds, ulTputColors, 600, 0);
    }

    /** Builds d20 legend definitions and registers them in LegendManager (c: attr -> name, b: defs). */
    private static final class LegendFactory {
        private final HashMap<String, String> attrMap;
        private final HashMap<String, Object> defMap;
        private final Object unsafe;
        private final Method allocate;
        private final Class<?> defClass;
        private final Constructor<?> stopsCtor;
        private final Constructor<?> stopCtor;
        private final Field defName, defStops, defMax, defMin, stopsList;

        LegendFactory(HashMap<String, String> attrMap, HashMap<String, Object> defMap,
                Object unsafe, Method allocate, Class<?> defClass,
                Constructor<?> stopsCtor, Constructor<?> stopCtor,
                Field defName, Field defStops, Field defMax, Field defMin, Field stopsList) {
            this.attrMap = attrMap;
            this.defMap = defMap;
            this.unsafe = unsafe;
            this.allocate = allocate;
            this.defClass = defClass;
            this.stopsCtor = stopsCtor;
            this.stopCtor = stopCtor;
            this.defName = defName;
            this.defStops = defStops;
            this.defMax = defMax;
            this.defMin = defMin;
            this.stopsList = stopsList;
        }

        @SuppressWarnings("unchecked")
        void inject(String attrKey, String legendName, double[] thresholds, int[] colors,
                double max, double min) throws Throwable {
            attrMap.put(attrKey, legendName);

            Object def = allocate.invoke(unsafe, defClass);
            defName.set(def, legendName);
            defMax.setDouble(def, max);
            defMin.setDouble(def, min);

            Object holder = stopsCtor.newInstance(15); // nr0(15): b = new ArrayList()
            ArrayList<Object> list = (ArrayList<Object>) stopsList.get(holder);
            for (int i = 0; i < thresholds.length; i++) {
                list.add(stopCtor.newInstance(thresholds[i], colors[i]));
            }

            defStops.set(def, holder);
            defMap.put(legendName, def);
        }
    }

    private void installLegendManagerHook() {
        try {
            Class<?> lmClass = Nsg.load(loader, LEGEND_MANAGER);
            Class<?> attrBase = Nsg.load(loader, ATTR_BASE);
            Class<?> attrClass = Nsg.load(loader, ATTR);
            final Field attrKeyField = attrBase.getDeclaredField("a"); // l8.a: attribute key (String)
            attrKeyField.setAccessible(true);

            // LegendManager.a(m8, double) -> Integer color
            Method colorMethod = lmClass.getDeclaredMethod("a", attrClass, double.class);

            xposed.hook(colorMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object attr = chain.getArg(0);
                    if (attr != null) {
                        Object keyObj = attrKeyField.get(attr);
                        if (keyObj != null) {
                            String key = keyObj.toString();
                            double value = (Double) chain.getArg(1);
                            if (isHeadroomKey(key)) {
                                return headroomColor(value);
                            }
                            if (isThroughputKey(key)) {
                                return throughputColor(value);
                            }
                            if (isUplinkThroughputKey(key)) {
                                return uplinkThroughputColor(value);
                            }
                        }
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "MapDotColorHook: LegendManager color hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: LegendManager color hook failed: " + t);
        }
    }

    /** gw0.b(rf0): returns the option's attribute map; make it null-safe. */
    private void installOptionUtilNullSafeHook() {
        try {
            Class<?> utilClass = Nsg.load(loader, OPTION_UTIL);
            Class<?> optClass = Nsg.load(loader, MAP_OPTION);
            Method bMethod = utilClass.getDeclaredMethod("b", optClass);
            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (chain.getArg(0) == null) {
                        return new HashMap<>();
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "MapDotColorHook: gw0.b null-safe hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: gw0.b null-safe hook failed: " + t);
        }
    }

    /** rf0.a(String, w4): option lookup by stored key; synthesise an option for our injected keys. */
    private void installOptionLookupFallbackHook() {
        try {
            Class<?> optClass = Nsg.load(loader, MAP_OPTION);
            Class<?> w4Class = Nsg.load(loader, CONTEXT_W4);
            Method lookup = optClass.getDeclaredMethod("a", String.class, w4Class);
            lookup.setAccessible(true);

            final Constructor<?> optCtor = optClass.getDeclaredConstructor();
            optCtor.setAccessible(true);
            final Field fieldA = optClass.getDeclaredField("a");
            final Field fieldB = optClass.getDeclaredField("b");
            final Field fieldC = optClass.getDeclaredField("c");
            fieldA.setAccessible(true);
            fieldB.setAccessible(true);
            fieldC.setAccessible(true);

            xposed.hook(lookup).intercept(new Hooker() {
                @Override
                @SuppressWarnings("unchecked")
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (result != null) return result;
                    String storedKey = (String) chain.getArg(0);
                    String optionKey = storedKey;
                    int idx = storedKey.indexOf("::");
                    if (idx != -1) {
                        optionKey = storedKey.substring(idx + 2);
                    }
                    HashMap<String, String> cEntries = INJECTED_OPTIONS.get(optionKey);
                    if (cEntries == null) {
                        optionKey = storedKey;
                        cEntries = INJECTED_OPTIONS.get(optionKey);
                    }
                    Object fallback = optCtor.newInstance();
                    fieldA.set(fallback, optionKey);
                    fieldB.set(fallback, optionKey);
                    if (cEntries != null) {
                        ((HashMap<String, String>) fieldC.get(fallback)).putAll(cEntries);
                    }
                    return fallback;
                }
            });
            Log.i(TAG, "MapDotColorHook: rf0.a fallback hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: rf0.a fallback hook failed: " + t);
        }
    }

    private static boolean isHeadroomKey(String key) {
        return "NR5G::Uplink_Measurements::NR_Power_Headroom".equals(key)
                || "LTE::Uplink_Measurements::LTE_AvgPHR_UL".equals(key);
    }

    private static boolean isThroughputKey(String key) {
        return "LTE::Downlink_Measurements::LTE_Physical_Throughput_DL".equals(key)
                || "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL".equals(key);
    }

    private static boolean isUplinkThroughputKey(String key) {
        return "NR5G::Uplink_Measurements::NR_Physical_Throughput_UL".equals(key)
                || "LTE::Uplink_Measurements::LTE_Physical_Throughput_UL".equals(key);
    }

    private static int headroomColor(double value) {
        if (value > 35.0) return 0xFF1B5E20;
        if (value > 25.0) return 0xFF2E7D32;
        if (value > 15.0) return 0xFF43A047;
        if (value > 10.0) return 0xFFFBC02D;
        if (value > 5.0) return 0xFFFF9800;
        return 0xFFF44336;
    }

    private static int throughputColor(double value) {
        if (value >= 1000.0) return 0xFF1B5E20;
        if (value >= 800.0) return 0xFF208040;
        if (value >= 500.0) return 0xFF20A040;
        if (value >= 300.0) return 0xFF60C020;
        if (value >= 200.0) return 0xFFA0C020;
        if (value >= 100.0) return 0xFFE0E030;
        if (value >= 50.0) return 0xFFF0B020;
        if (value >= 20.0) return 0xFFF08020;
        if (value >= 10.0) return 0xFFE06030;
        if (value >= 5.0) return 0xFFC03030;
        return 0xFF8B0000;
    }

    private static int uplinkThroughputColor(double value) {
        if (value >= 500.0) return 0xFF1B5E20;
        if (value >= 300.0) return 0xFF2E9030;
        if (value >= 250.0) return 0xFF40A030;
        if (value >= 200.0) return 0xFF60B020;
        if (value >= 180.0) return 0xFF80C020;
        if (value >= 160.0) return 0xFFA0D020;
        if (value >= 140.0) return 0xFFC0E020;
        if (value >= 120.0) return 0xFFE0E020;
        if (value >= 100.0) return 0xFFF0E020;
        if (value >= 80.0)  return 0xFFF0C020;
        if (value >= 60.0)  return 0xFFF0A020;
        if (value >= 50.0)  return 0xFFF08020;
        if (value >= 40.0)  return 0xFFF06010;
        if (value >= 30.0)  return 0xFFE05020;
        if (value >= 20.0)  return 0xFFD04030;
        if (value >= 10.0)  return 0xFFB03030;
        if (value >= 5.0)   return 0xFF8B0000;
        return 0xFF6B0000;
    }
}
