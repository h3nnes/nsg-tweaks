package com.nsgmod.band;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks t7.g0.E() (start local test) to reschedule the UI refresh tick Runnable
 * with a shorter interval when the "NSGMod: Fast refresh" toggle is enabled.
 *
 * NSG's default refresh interval is 880 ms. When the toggle is ON this hook cancels
 * the default ScheduledFuture and creates a new one with 440 ms.
 *
 * Approach:
 *   1. After-hook on g0.E() — after the original method schedules the 880 ms tick,
 *      we cancel it and reschedule with the desired interval.
 *   2. SharedPreferences listener — when the toggle changes mid-test, we detect the
 *      active g0 instance via TestService.o() and reschedule again.
 *
 * All reflection is resolved once in initReflection() and stored in final fields.
 * The hook fails silently (logs a warning) and never crashes NSG.
 */
public class RefreshIntervalHook {

    private static final String TAG = "NSGBandHook";
    private static final long NORMAL_INTERVAL_MS = 880L;
    private static final long FAST_INTERVAL_MS   = 440L;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // Reflection cache
    private Class<?> g0Class;
    private Method   eMethod;          // g0.E()
    private Field    f7773xField;      // g0: ScheduledFuture<?> field (found by type)
    private Field    f3852jField;      // TestService: ScheduledExecutorService field (found by type)
    private Class<?> e0Class;          // t7.e0 (Runnable tick handler)
    private Constructor<?> e0Ctor;     // e0(g0, int)
    private Field    e0CaseField;      // e0: int case-selector field (found by type)

    private boolean ready = false;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    public RefreshIntervalHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    /** Resolve all classes / fields / methods once at construction time. */
    private void initReflection() {
        try {
            g0Class = loader.loadClass("t7.g0");
            eMethod = g0Class.getDeclaredMethod("E");
            eMethod.setAccessible(true);

            // Find ScheduledFuture<?> field on t7.g0 by type (name varies between builds)
            for (Field f : g0Class.getDeclaredFields()) {
                if (ScheduledFuture.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f7773xField = f;
                    break;
                }
            }
            if (f7773xField == null) {
                throw new NoSuchFieldException("No ScheduledFuture field in t7.g0");
            }

            // Find ScheduledExecutorService field on TestService by type
            Class<?> testServiceClass = loader.loadClass("com.qtrun.sys.TestService");
            for (Field f : testServiceClass.getDeclaredFields()) {
                if (ScheduledExecutorService.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f3852jField = f;
                    break;
                }
            }
            if (f3852jField == null) {
                throw new NoSuchFieldException("No ScheduledExecutorService field in TestService");
            }

            e0Class = loader.loadClass("t7.e0");
            e0Ctor = e0Class.getDeclaredConstructor(g0Class, int.class);
            e0Ctor.setAccessible(true);

            // Find int case-selector field on t7.e0 by type
            for (Field f : e0Class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    e0CaseField = f;
                    break;
                }
            }
            if (e0CaseField == null) {
                throw new NoSuchFieldException("No int field in t7.e0");
            }

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "Not ready — skipping install");
            return;
        }
        installEHook();
        registerPrefListener();
        Log.i(TAG, "RefreshIntervalHook: installed");
    }

    /** After-hook on g0.E() — reschedule tick Runnable after original scheduling. */
    private void installEHook() {
        xposed.hook(eMethod).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                Object g0Instance = chain.getThisObject();
                if (g0Instance != null) {
                    try {
                        rescheduleIfNeeded(g0Instance);
                    } catch (Throwable t) {
                        Log.w(TAG, "reschedule after E() failed: " + t);
                    }
                }
                return result;
            }
        });
    }

    /** Register a SharedPreferences listener so mid-test toggle changes take effect. */
    private void registerPrefListener() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Application app =
                    (Application) atCls.getMethod("currentApplication").invoke(null);
            if (app == null) return;

            prefs = app.getSharedPreferences(
                    "com.qtrun.QuickTest_preferences", Context.MODE_PRIVATE);

            prefListener = (sharedPreferences, key) -> {
                if (!SettingsToggleHook.PREF_KEY_FAST_REFRESH.equals(key)) return;
                try {
                    Class<?> testServiceCls = loader.loadClass("com.qtrun.sys.TestService");
                    Method oMethod = testServiceCls.getDeclaredMethod("o");
                    Object testService = oMethod.invoke(null);
                    if (testService != null && g0Class.isInstance(testService)) {
                        rescheduleIfNeeded(testService);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "pref change reschedule failed: " + t);
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register preference listener: " + t);
        }
    }

    /**
     * Cancel the current ScheduledFuture (if any) and reschedule with the interval
     * dictated by the fast-refresh toggle state.
     */
    private void rescheduleIfNeeded(Object g0Instance) throws Throwable {
        boolean fastEnabled = SettingsToggleHook.fastRefreshEnabled();
        long desiredInterval = fastEnabled ? FAST_INTERVAL_MS : NORMAL_INTERVAL_MS;

        // Read current ScheduledFuture
        ScheduledFuture<?> currentFuture = (ScheduledFuture<?>) f7773xField.get(g0Instance);
        if (currentFuture == null) {
            // No active test — nothing to reschedule
            return;
        }

        // Cancel existing schedule (allow running task to complete)
        currentFuture.cancel(false);
        f7773xField.set(g0Instance, null);

        // Get ScheduledExecutorService
        ScheduledExecutorService executor =
                (ScheduledExecutorService) f3852jField.get(g0Instance);

        // Build new tick Runnable: e0(g0Instance, 1)  — case 1 is the UI refresh tick
        Object tickRunnable = e0Ctor.newInstance(g0Instance, 1);
        int caseValue = e0CaseField.getInt(tickRunnable);
        if (caseValue != 1) {
            Log.w(TAG, "Unexpected e0 case " + caseValue + " — aborting reschedule");
            return;
        }

        // Schedule with new interval (0 ms initial delay)
        ScheduledFuture<?> newFuture = executor.scheduleWithFixedDelay(
                (Runnable) tickRunnable, 0L, desiredInterval, TimeUnit.MILLISECONDS);

        // Store new future back into g0
        f7773xField.set(g0Instance, newFuture);
    }
}
