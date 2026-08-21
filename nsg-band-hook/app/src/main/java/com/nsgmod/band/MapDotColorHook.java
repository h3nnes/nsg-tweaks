package com.nsgmod.band;

import android.content.ContextWrapper;
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

public class MapDotColorHook {

    private static final String TAG = "NSGBandHook";

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

        java.lang.reflect.Constructor<?> sf0Ctor = sf0Class.getDeclaredConstructor();
        sf0Ctor.setAccessible(true);

        Object commonSinr = sf0Ctor.newInstance();
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

        Object nrHeadroom = sf0Ctor.newInstance();
        sf0A.set(nrHeadroom, "NR Power Headroom");
        sf0B.set(nrHeadroom, "NR_Power_Headroom");
        HashMap<String, String> nrPhrMap = (HashMap<String, String>) sf0C.get(nrHeadroom);
        nrPhrMap.put("NR_Power_Headroom", "NR5G::Uplink_Measurements::NR_Power_Headroom");
        try { sf0D.setBoolean(nrHeadroom, true); } catch (Throwable ignored) {}
        singlesList.add(nrHeadroom);

        Object lteHeadroom = sf0Ctor.newInstance();
        sf0A.set(lteHeadroom, "LTE Power Headroom");
        sf0B.set(lteHeadroom, "LTE_Power_Headroom");
        HashMap<String, String> ltePhrMap = (HashMap<String, String>) sf0C.get(lteHeadroom);
        ltePhrMap.put("LTE_Power_Headroom", "LTE::Uplink_Measurements::LTE_AvgPHR_UL");
        try { sf0D.setBoolean(lteHeadroom, true); } catch (Throwable ignored) {}
        singlesList.add(lteHeadroom);

        Object nrThroughput = sf0Ctor.newInstance();
        sf0A.set(nrThroughput, "NR Throughput DL");
        sf0B.set(nrThroughput, "NR_Throughput_DL");
        HashMap<String, String> nrTputMap = (HashMap<String, String>) sf0C.get(nrThroughput);
        nrTputMap.put("NR_Throughput_DL", "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL");
        try { sf0D.setBoolean(nrThroughput, true); } catch (Throwable ignored) {}
        singlesList.add(nrThroughput);

        Object lteThroughput = sf0Ctor.newInstance();
        sf0A.set(lteThroughput, "LTE Throughput DL");
        sf0B.set(lteThroughput, "LTE_Throughput_DL");
        HashMap<String, String> lteTputMap = (HashMap<String, String>) sf0C.get(lteThroughput);
        lteTputMap.put("LTE_Throughput_DL", "LTE::Downlink_Measurements::LTE_Physical_Throughput_DL");
        try { sf0D.setBoolean(lteThroughput, true); } catch (Throwable ignored) {}
        singlesList.add(lteThroughput);
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

            java.lang.reflect.Constructor<?> sf0Ctor = sf0Class.getDeclaredConstructor();
            sf0Ctor.setAccessible(true);
            Field sf0B = sf0Class.getDeclaredField("b");
            Field sf0A = sf0Class.getDeclaredField("a");
            sf0B.setAccessible(true);
            sf0A.setAccessible(true);

            final java.lang.reflect.Constructor<?> ctor = sf0Ctor;
            final Field fieldB = sf0B;
            final Field fieldA = sf0A;

            xposed.hook(sf0AMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (result != null) return result;
                    String storedKey = (String) chain.getArg(0);
                    Object fallback = ctor.newInstance();
                    fieldA.set(fallback, storedKey);
                    fieldB.set(fallback, storedKey);
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
