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
    }

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public MapDotColorHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installPresentationManagerHook();
        installLegendManagerHook();
        installHw0BNullSafeHook();
        installSf0AFallbackHook();
    }

    private void installPresentationManagerHook() {
        try {
            Class<?> pmClass = ClassMapping.loadClass(
                    "com.qtrun.legend.Presentation.PresentationManager", loader);
            if (pmClass == null) {
                Log.w(TAG, "MapDotColorHook: PresentationManager not found");
                return;
            }
            String initMethodName = ClassMapping.runtimeMethodName(
                    "com.qtrun.legend.Presentation.PresentationManager", "a", loader);
            Method initMethod = pmClass.getDeclaredMethod(initMethodName, ContextWrapper.class);

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
        Class<?> pmClass = ClassMapping.loadClass(
                "com.qtrun.legend.Presentation.PresentationManager", loader);
        if (pmClass == null) {
            Log.w(TAG, "MapDotColorHook: PresentationManager class not found");
            return;
        }
        String singletonName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.Presentation.PresentationManager", "e", loader);
        Field singletonField = pmClass.getDeclaredField(singletonName);
        singletonField.setAccessible(true);
        Object pmInstance = singletonField.get(null);

        String commonListName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.Presentation.PresentationManager", "a", loader);
        String singlesListName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.Presentation.PresentationManager", "b", loader);
        Field commonListField = pmClass.getDeclaredField(commonListName);
        Field singlesListField = pmClass.getDeclaredField(singlesListName);
        commonListField.setAccessible(true);
        singlesListField.setAccessible(true);
        List<Object> commonList = (List<Object>) commonListField.get(pmInstance);
        List<Object> singlesList = (List<Object>) singlesListField.get(pmInstance);

        Class<?> sf0Class = ClassMapping.loadClass("sf0", loader);
        if (sf0Class == null) {
            Log.w(TAG, "MapDotColorHook: sf0 class not found");
            return;
        }
        Field sf0A = sf0Class.getDeclaredField("a");
        Field sf0B = sf0Class.getDeclaredField("b");
        Field sf0C = sf0Class.getDeclaredField("c");
        Field sf0D = sf0Class.getDeclaredField("d");
        sf0A.setAccessible(true);
        sf0B.setAccessible(true);
        sf0C.setAccessible(true);
        sf0D.setAccessible(true);

        java.lang.reflect.Constructor<?> sf0Ctor;
        java.lang.reflect.Constructor<?> sf0CtorSingles = null;
        try {
            sf0Ctor = sf0Class.getDeclaredConstructor();
            sf0Ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            sf0Ctor = null;
        }
        try {
            sf0CtorSingles = sf0Class.getDeclaredConstructor(int.class);
            sf0CtorSingles.setAccessible(true);
        } catch (NoSuchMethodException e) {
            sf0CtorSingles = null;
        }

        Object commonSinr = sf0Ctor != null ? sf0Ctor.newInstance() : sf0CtorSingles.newInstance(0);
        sf0A.set(commonSinr, "Common SINR");
        sf0B.set(commonSinr, "Common_SINR");
        HashMap<String, String> sinrMap = (HashMap<String, String>) sf0C.get(commonSinr);
        sinrMap.put("GSM", "GSM::Downlink_Measurements::ServC2I");
        sinrMap.put("WCDMA", "WCDMA::Downlink_Measurements::Uu_Serving_EcNo");
        sinrMap.put("TDSCDMA", "TDSCDMA::Downlink_Measurements::Uu_PCCPCH_C2I");
        sinrMap.put("CDMA", "CDMA::Pilot_Measurements::EcIo_Combined");
        sinrMap.put("EVDO", "EvDo::Pilot_Measurements::EV_SINR_Best");
        sinrMap.put("LTE", "LTE::Downlink_Measurements::LTE_SINR_PCell");
        sinrMap.put("NR5G", "NR5G::Downlink_Measurements::NR_SS_SINR");
        try { sf0D.setBoolean(commonSinr, false); } catch (Throwable ignored) {}
        commonList.add(commonSinr);

        Object nrHeadroom = sf0CtorSingles != null ? sf0CtorSingles.newInstance(0) : sf0Ctor.newInstance();
        sf0A.set(nrHeadroom, "NR Power Headroom");
        sf0B.set(nrHeadroom, "NR_Power_Headroom");
        HashMap<String, String> nrPhrMap = (HashMap<String, String>) sf0C.get(nrHeadroom);
        nrPhrMap.put("NR_Power_Headroom", "NR5G::Uplink_Measurements::NR_Power_Headroom");
        try { sf0D.setBoolean(nrHeadroom, true); } catch (Throwable ignored) {}
        singlesList.add(nrHeadroom);

        Object lteHeadroom = sf0CtorSingles != null ? sf0CtorSingles.newInstance(0) : sf0Ctor.newInstance();
        sf0A.set(lteHeadroom, "LTE Power Headroom");
        sf0B.set(lteHeadroom, "LTE_Power_Headroom");
        HashMap<String, String> ltePhrMap = (HashMap<String, String>) sf0C.get(lteHeadroom);
        ltePhrMap.put("LTE_Power_Headroom", "LTE::Uplink_Measurements::LTE_AvgPHR_UL");
        try { sf0D.setBoolean(lteHeadroom, true); } catch (Throwable ignored) {}
        singlesList.add(lteHeadroom);

        Object nrThroughput = sf0CtorSingles != null ? sf0CtorSingles.newInstance(0) : sf0Ctor.newInstance();
        sf0A.set(nrThroughput, "NR Throughput DL");
        sf0B.set(nrThroughput, "NR_Throughput_DL");
        HashMap<String, String> nrTputMap = (HashMap<String, String>) sf0C.get(nrThroughput);
        nrTputMap.put("NR_Throughput_DL", "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL");
        try { sf0D.setBoolean(nrThroughput, true); } catch (Throwable ignored) {}
        singlesList.add(nrThroughput);

        Object lteThroughput = sf0CtorSingles != null ? sf0CtorSingles.newInstance(0) : sf0Ctor.newInstance();
        sf0A.set(lteThroughput, "LTE Throughput DL");
        sf0B.set(lteThroughput, "LTE_Throughput_DL");
        HashMap<String, String> lteTputMap = (HashMap<String, String>) sf0C.get(lteThroughput);
        lteTputMap.put("LTE_Throughput_DL", "LTE::Downlink_Measurements::LTE_Physical_Throughput_DL");
        try { sf0D.setBoolean(lteThroughput, true); } catch (Throwable ignored) {}
        singlesList.add(lteThroughput);

        injectLegendEntries();
    }

    @SuppressWarnings("unchecked")
    private void injectLegendEntries() throws Throwable {
        Class<?> lmClass = ClassMapping.loadClass("com.qtrun.legend.LegendManager", loader);
        if (lmClass == null) return;
        String singletonName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.LegendManager", "e", loader);
        Field singletonField = lmClass.getDeclaredField(singletonName);
        singletonField.setAccessible(true);
        Object lmInstance = singletonField.get(null);

        String attrMapName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.LegendManager", "c", loader);
        Field attrMapField = lmClass.getDeclaredField(attrMapName);
        attrMapField.setAccessible(true);
        HashMap<String, String> attrMap = (HashMap<String, String>) attrMapField.get(lmInstance);

        String defMapName = ClassMapping.runtimeFieldName(
                "com.qtrun.legend.LegendManager", "b", loader);
        Field defMapField = lmClass.getDeclaredField(defMapName);
        defMapField.setAccessible(true);
        HashMap<String, Object> defMap = (HashMap<String, Object>) defMapField.get(lmInstance);

        Class<?> e20Class = ClassMapping.loadClass("e20", loader);
        Class<?> or0Class = ClassMapping.loadClass("or0", loader);
        Class<?> oi0Class = ClassMapping.loadClass("oi0", loader);
        if (e20Class == null || or0Class == null || oi0Class == null) return;

        Constructor<?> e20Ctor = null;
        Method e20UnsafeAlloc = null;
        try {
            e20Ctor = e20Class.getDeclaredConstructor();
            e20Ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            e20UnsafeAlloc = unsafeClass.getMethod("allocateInstance", Class.class);
            e20UnsafeAlloc.setAccessible(true);
        }

        Constructor<?> or0Ctor;
        try {
            or0Ctor = or0Class.getDeclaredConstructor(int.class);
        } catch (NoSuchMethodException e) {
            or0Ctor = or0Class.getDeclaredConstructor();
        }
        or0Ctor.setAccessible(true);

        Constructor<?> oi0Ctor = oi0Class.getDeclaredConstructor(double.class, int.class);
        oi0Ctor.setAccessible(true);

        Field e20A = e20Class.getDeclaredField("a");
        Field e20B = e20Class.getDeclaredField("b");
        Field e20C = e20Class.getDeclaredField("c");
        Field e20D = e20Class.getDeclaredField("d");
        e20A.setAccessible(true);
        e20B.setAccessible(true);
        e20C.setAccessible(true);
        e20D.setAccessible(true);

        String listFieldName = ClassMapping.runtimeFieldName("or0", "b", loader);
        Field or0ListField = or0Class.getDeclaredField(listFieldName);
        or0ListField.setAccessible(true);

        double[] hrThresholds = {-10, 5, 10, 15, 25, 35};
        int[] hrColors = {0xFFF44336, 0xFFFF9800, 0xFFFBC02D, 0xFF43A047, 0xFF2E7D32, 0xFF1B5E20};
        injectLegend(attrMap, defMap, e20Ctor, e20UnsafeAlloc, e20Class, or0Ctor, oi0Ctor,
                e20A, e20B, e20C, e20D, or0ListField,
                "NR5G::Uplink_Measurements::NR_Power_Headroom", "NR_Power_Headroom",
                hrThresholds, hrColors, 40, -10);
        injectLegend(attrMap, defMap, e20Ctor, e20UnsafeAlloc, e20Class, or0Ctor, oi0Ctor,
                e20A, e20B, e20C, e20D, or0ListField,
                "LTE::Uplink_Measurements::LTE_AvgPHR_UL", "LTE_Power_Headroom",
                hrThresholds, hrColors, 40, -10);

        double[] tputThresholds = {0, 5, 10, 20, 50, 100, 200, 300, 500, 800, 1000};
        int[] tputColors = {0xFF8B0000, 0xFFC03030, 0xFFE06030, 0xFFF08020, 0xFFF0B020,
                0xFFE0E030, 0xFFA0C020, 0xFF60C020, 0xFF20A040, 0xFF208040, 0xFF1B5E20};
        injectLegend(attrMap, defMap, e20Ctor, e20UnsafeAlloc, e20Class, or0Ctor, oi0Ctor,
                e20A, e20B, e20C, e20D, or0ListField,
                "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL", "NR_Throughput_DL",
                tputThresholds, tputColors, 2000, 0);
        injectLegend(attrMap, defMap, e20Ctor, e20UnsafeAlloc, e20Class, or0Ctor, oi0Ctor,
                e20A, e20B, e20C, e20D, or0ListField,
                "LTE::Downlink_Measurements::LTE_Physical_Throughput_DL", "LTE_Throughput_DL",
                tputThresholds, tputColors, 2000, 0);
    }

    @SuppressWarnings("unchecked")
    private void injectLegend(HashMap<String, String> attrMap, HashMap<String, Object> defMap,
            Constructor<?> e20Ctor, Method e20UnsafeAlloc, Class<?> e20Class,
            Constructor<?> or0Ctor, Constructor<?> oi0Ctor,
            Field e20A, Field e20B, Field e20C, Field e20D, Field or0ListField,
            String attrKey, String legendName,
            double[] thresholds, int[] colors, double max, double min) throws Throwable {
        attrMap.put(attrKey, legendName);

        Object def;
        if (e20Ctor != null) {
            def = e20Ctor.newInstance();
        } else {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            def = e20UnsafeAlloc.invoke(unsafeField.get(null), e20Class);
        }
        e20A.set(def, legendName);
        e20C.setDouble(def, max);
        e20D.setDouble(def, min);

        Object listHolder;
        if (or0Ctor.getParameterCount() == 1 && or0Ctor.getParameterTypes()[0] == int.class) {
            listHolder = or0Ctor.newInstance(15);
        } else {
            listHolder = or0Ctor.newInstance();
        }

        ArrayList<Object> list = (ArrayList<Object>) or0ListField.get(listHolder);
        for (int i = 0; i < thresholds.length; i++) {
            Object entry = oi0Ctor.newInstance(thresholds[i], colors[i]);
            list.add(entry);
        }

        e20B.set(def, listHolder);
        defMap.put(legendName, def);
    }

    private void installLegendManagerHook() {
        try {
            Class<?> lmClass = ClassMapping.loadClass("com.qtrun.legend.LegendManager", loader);
            if (lmClass == null) {
                Log.w(TAG, "MapDotColorHook: LegendManager not found");
                return;
            }
            String lmSingletonName = ClassMapping.runtimeFieldName(
                    "com.qtrun.legend.LegendManager", "e", loader);
            Field lmSingletonField = lmClass.getDeclaredField(lmSingletonName);
            lmSingletonField.setAccessible(true);
            lmSingletonField.get(null);

            Class<?> sysAClass = ClassMapping.loadClass("com.qtrun.sys.a", loader);
            Field sysAFieldA = sysAClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.a", "a", loader));
            sysAFieldA.setAccessible(true);

            String colorMethodName = ClassMapping.runtimeMethodName(
                    "com.qtrun.legend.LegendManager", "a", loader);
            Class<?> sysBClass = ClassMapping.loadClass("com.qtrun.sys.b", loader);
            Method colorMethod = lmClass.getDeclaredMethod(colorMethodName, sysBClass, double.class);

            xposed.hook(colorMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object m8Var = chain.getArg(0);
                    if (m8Var != null) {
                        Object keyObj = sysAFieldA.get(m8Var);
                        if (keyObj != null) {
                            String key = keyObj.toString();
                            double value = (Double) chain.getArg(1);
                            if (isHeadroomKey(key)) {
                                return headroomColor(value);
                            }
                            if (isThroughputKey(key)) {
                                return throughputColor(value);
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

    private void installHw0BNullSafeHook() {
        try {
            Class<?> hw0Class = ClassMapping.loadClass("hw0", loader);
            if (hw0Class == null) {
                Log.w(TAG, "MapDotColorHook: hw0 class not found");
                return;
            }
            Class<?> sf0Class = ClassMapping.loadClass("sf0", loader);
            if (sf0Class == null) {
                Log.w(TAG, "MapDotColorHook: sf0 class not found for hw0.b hook");
                return;
            }
            Method bMethod = hw0Class.getDeclaredMethod("b", sf0Class);
            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (chain.getArg(0) == null) {
                        return new HashMap<>();
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "MapDotColorHook: hw0.b null-safe hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: hw0.b null-safe hook failed: " + t);
        }
    }

    private void installSf0AFallbackHook() {
        try {
            Class<?> sf0Class = ClassMapping.loadClass("sf0", loader);
            if (sf0Class == null) {
                Log.w(TAG, "MapDotColorHook: sf0 class not found for sf0.a hook");
                return;
            }
            Method sf0AMethod = null;
            for (Method m : sf0Class.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != sf0Class) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                sf0AMethod = m;
                break;
            }
            if (sf0AMethod == null) {
                Log.w(TAG, "MapDotColorHook: sf0.a(String,?) method not found");
                return;
            }
            sf0AMethod.setAccessible(true);

            java.lang.reflect.Constructor<?> sf0CtorNoArg = null;
            java.lang.reflect.Constructor<?> sf0CtorInt = null;
            try {
                sf0CtorNoArg = sf0Class.getDeclaredConstructor();
                sf0CtorNoArg.setAccessible(true);
            } catch (NoSuchMethodException e) { }
            try {
                sf0CtorInt = sf0Class.getDeclaredConstructor(int.class);
                sf0CtorInt.setAccessible(true);
            } catch (NoSuchMethodException e) { }
            final java.lang.reflect.Constructor<?> ctorNoArg = sf0CtorNoArg;
            final java.lang.reflect.Constructor<?> ctorInt = sf0CtorInt;

            Field sf0B = sf0Class.getDeclaredField("b");
            Field sf0A = sf0Class.getDeclaredField("a");
            Field sf0C = sf0Class.getDeclaredField("c");
            sf0B.setAccessible(true);
            sf0A.setAccessible(true);
            sf0C.setAccessible(true);

            final Field fieldB = sf0B;
            final Field fieldA = sf0A;
            final Field fieldC = sf0C;

            xposed.hook(sf0AMethod).intercept(new Hooker() {
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
                    Object fallback = ctorInt != null ? ctorInt.newInstance(0) : ctorNoArg.newInstance();
                    fieldA.set(fallback, optionKey);
                    fieldB.set(fallback, optionKey);
                    if (cEntries != null) {
                        HashMap<String, String> fallbackC = (HashMap<String, String>) fieldC.get(fallback);
                        fallbackC.putAll(cEntries);
                    }
                    return fallback;
                }
            });
            Log.i(TAG, "MapDotColorHook: sf0.a fallback hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "MapDotColorHook: sf0.a fallback hook failed: " + t);
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
}
