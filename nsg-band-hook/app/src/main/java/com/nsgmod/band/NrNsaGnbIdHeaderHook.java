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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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
    private Field tacValueField;
    private Field ecellIdLabelField;
    private Field tacLabelField;
    private Field bandLabelField;
    private Field bandValueField;
    private Field viewField;
    private Field gField;
    private Field colField;
    private Field widthField;
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
    private boolean originalsSaved = false;
    private float origBandCol;
    private float origBandWidth;
    private float origTacCol;
    private float origTacWidth;
    private float origEcellCol;
    private float origEcellWidth;
    private final Set<Object> adjustedFragments = Collections.newSetFromMap(new WeakHashMap<>());
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
                        Object fragment = chain.getThisObject();
                        if (!SettingsToggleHook.gnbIdHeaderEnabled()) {
                            restoreGeometry(fragment);
                            return result;
                        }
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

            tacValueField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "b1", loader));
            tacValueField.setAccessible(true);

            ecellIdLabelField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "Y0", loader));
            ecellIdLabelField.setAccessible(true);

            tacLabelField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "X0", loader));
            tacLabelField.setAccessible(true);

            bandLabelField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "Z0", loader));
            bandLabelField.setAccessible(true);

            bandValueField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "d1", loader));
            bandValueField.setAccessible(true);

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

    private void adjustGeometry(Object fragment) throws Throwable {
        if (adjustedFragments.contains(fragment)) return;
        Object ecellIdLabel = ecellIdLabelField.get(fragment);
        Object ecellIdValue = ecellIdValueField.get(fragment);
        Object tacLabel = tacLabelField.get(fragment);
        Object tacValue = tacValueField.get(fragment);
        Object bandLabel = bandLabelField.get(fragment);
        Object bandValue = bandValueField.get(fragment);
        if (ecellIdLabel == null || ecellIdValue == null || tacLabel == null || tacValue == null)
            return;
        if (!originalsSaved) {
            Object bandElem = bandLabel != null ? bandLabel : bandValue;
            if (bandElem != null) {
                origBandCol = colField.getFloat(bandElem);
                origBandWidth = widthField.getFloat(bandElem);
            }
            origTacCol = colField.getFloat(tacLabel);
            origTacWidth = widthField.getFloat(tacLabel);
            origEcellCol = colField.getFloat(ecellIdLabel);
            origEcellWidth = widthField.getFloat(ecellIdLabel);
            originalsSaved = true;
        }
        if (bandLabel != null) {
            colField.setFloat(bandLabel, 21.0f);
            widthField.setFloat(bandLabel, 12.0f);
        }
        if (bandValue != null) {
            colField.setFloat(bandValue, 21.0f);
            widthField.setFloat(bandValue, 12.0f);
        }
        colField.setFloat(tacLabel, 33.0f);
        widthField.setFloat(tacLabel, 12.0f);
        colField.setFloat(tacValue, 33.0f);
        widthField.setFloat(tacValue, 12.0f);
        colField.setFloat(ecellIdLabel, 45.0f);
        widthField.setFloat(ecellIdLabel, 54.0f);
        colField.setFloat(ecellIdValue, 45.0f);
        widthField.setFloat(ecellIdValue, 54.0f);
        adjustedFragments.add(fragment);
    }

    private void restoreGeometry(Object fragment) throws Throwable {
        if (!adjustedFragments.contains(fragment)) return;
        if (!originalsSaved) return;
        Object ecellIdLabel = ecellIdLabelField.get(fragment);
        Object ecellIdValue = ecellIdValueField.get(fragment);
        Object tacLabel = tacLabelField.get(fragment);
        Object tacValue = tacValueField.get(fragment);
        Object bandLabel = bandLabelField.get(fragment);
        Object bandValue = bandValueField.get(fragment);
        if (bandLabel != null) {
            colField.setFloat(bandLabel, origBandCol);
            widthField.setFloat(bandLabel, origBandWidth);
        }
        if (bandValue != null) {
            colField.setFloat(bandValue, origBandCol);
            widthField.setFloat(bandValue, origBandWidth);
        }
        if (tacLabel != null) {
            colField.setFloat(tacLabel, origTacCol);
            widthField.setFloat(tacLabel, origTacWidth);
        }
        if (tacValue != null) {
            colField.setFloat(tacValue, origTacCol);
            widthField.setFloat(tacValue, origTacWidth);
        }
        if (ecellIdLabel != null) {
            colField.setFloat(ecellIdLabel, origEcellCol);
            widthField.setFloat(ecellIdLabel, origEcellWidth);
        }
        if (ecellIdValue != null) {
            colField.setFloat(ecellIdValue, origEcellCol);
            widthField.setFloat(ecellIdValue, origEcellWidth);
        }
        adjustedFragments.remove(fragment);
    }

    @SuppressWarnings("unchecked")
    private void updateECellId(Object fragment, Object dataSource, short moduleIndex) throws Throwable {
        Long gCellId = resolveGCellId(dataSource, moduleIndex);
        if (gCellId == null) {
            restoreGeometry(fragment);
            return;
        }
        adjustGeometry(fragment);
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
