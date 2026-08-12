package com.nsgmod.band;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
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
    private static final String NR5G_TECH = "NR5G";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean ready = false;
    private Class<?> cgiClass;
    private Field ecellIdValueField;
    private Field viewField;
    private Field gField;
    private Field wsSingletonField;
    private Field wsRatField;
    private Field sdSingletonField;
    private Method sdEMethod;
    private Method sdIMethod;
    private Method dsGetPropertyMethod;
    private Constructor<?> iterConstructor;
    private Method iterReverseMethod;
    private Method iterEndMethod;
    private Method iterValueMethod;
    private Field ydGCellIdField;
    private Field settingsSingletonField;
    private Field gnbLengthField;

    private int cachedArfcn = -1;
    private int cachedPci = -1;
    private long cachedGCellId = -1;
    private boolean loggedRatMode = false;
    private boolean loggedCellDb = false;
    private boolean loggedProps = false;

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
                        if (!SettingsToggleHook.gnbIdHeaderEnabled()) return result;
                        Object fragment = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        Object dataSource = args.get(0);
                        short moduleIndex = (Short) args.get(2);
                        updateECellId(fragment, dataSource, moduleIndex);
                    } catch (Throwable t) {
                        Log.e(TAG, "NrNsaGnbIdHeaderHook update error", t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "NrNsaGnbIdHeaderHook installed");
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

            Class<?> eh0Class = ClassMapping.loadClass("v6.g", loader);
            gField = eh0Class.getDeclaredField("g");
            gField.setAccessible(true);

            Class<?> yg0Class = ClassMapping.loadClass("v6.a", loader);
            viewField = yg0Class.getDeclaredField("a");
            viewField.setAccessible(true);

            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            wsSingletonField = wsClass.getField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader));
            wsRatField = wsClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "d", loader));
            wsRatField.setAccessible(true);

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
            iterReverseMethod = iterClass.getDeclaredMethod("reverse");
            iterEndMethod = iterClass.getDeclaredMethod("end");
            iterValueMethod = iterClass.getDeclaredMethod("value");

            Class<?> ydClass = ClassMapping.loadClass("h7.e", loader);
            ydGCellIdField = ydClass.getDeclaredField("g");
            ydGCellIdField.setAccessible(true);

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

            ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "NrNsaGnbIdHeaderHook init failed: " + t);
        }
    }

    private int getGnbLength() {
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

    @SuppressWarnings("unchecked")
    private void updateECellId(Object fragment, Object dataSource, short moduleIndex) throws Throwable {
        Long gCellId = resolveGCellId(dataSource, moduleIndex);
        if (gCellId == null) return;
        injectGnbIdText(fragment, gCellId);
    }

    @SuppressWarnings("unchecked")
    private Long resolveGCellId(Object dataSource, short moduleIndex) throws Throwable {
        Object workspace = wsSingletonField.get(null);
        if (workspace == null) return null;
        Object rat = wsRatField.get(workspace);
        if (rat == null) return null;
        String ratStr = rat.toString();
        if (!"NR-NSA".equals(ratStr)) {
            if (!loggedRatMode) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: RAT=" + ratStr + " (not NR-NSA), skipping");
                loggedRatMode = true;
            }
            return null;
        }
        loggedRatMode = false;

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
            arfcn = readIntProperty(dataSource, mi, KEY_NR_ARFCN);
            pci = readIntProperty(dataSource, mi, KEY_NR_PCI);
            if (arfcn != null && pci != null) break;
        }
        if (arfcn == null || pci == null) {
            int miUsed = moduleIndex & 0xFFFF;
            arfcn = readIntProperty(dataSource, miUsed, KEY_NR_ARFCN);
            pci = readIntProperty(dataSource, miUsed, KEY_NR_PCI);
        }
        if (arfcn == null || pci == null) {
            if (!loggedProps) {
                Log.w(TAG, "NrNsaGnbIdHeaderHook: NR ARFCN/PCI null (tried mi 0..3 + " + (moduleIndex & 0xFFFF) + ")");
                loggedProps = true;
            }
            return null;
        }
        loggedProps = false;

        if (arfcn != cachedArfcn || pci != cachedPci) {
            cachedArfcn = arfcn;
            cachedPci = pci;
            cachedGCellId = -1;
            String where = "nr_arfcn=" + arfcn + " and nr_pci=" + pci;
            Object result = sdIMethod.invoke(sdInstance, nrTable, where);
            if (result instanceof List && !((List<?>) result).isEmpty()) {
                Object row = ((List<Object>) result).get(0);
                cachedGCellId = ydGCellIdField.getLong(row);
            }
        }
        if (cachedGCellId <= 0) return null;
        return cachedGCellId;
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

        String separator = " || ";
        String gnbText = gnbId + " / " + sector;
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

    private Integer readIntProperty(Object dataSource, int moduleIndex, String key) {
        Object value = readProperty(dataSource, moduleIndex, key);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return null;
    }

    private Object readProperty(Object dataSource, int moduleIndex, String key) {
        try {
            Object prop = dsGetPropertyMethod.invoke(dataSource, key, moduleIndex);
            if (prop == null) return null;
            Object iter = iterConstructor.newInstance(prop);
            iterReverseMethod.invoke(iter);
            if ((boolean) iterEndMethod.invoke(iter)) return null;
            return iterValueMethod.invoke(iter);
        } catch (Throwable t) {
            return null;
        }
    }
}
