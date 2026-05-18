package com.nsgmod.band;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Date;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks com.qtrun.sys.Application subscriber/license checks so all
 * premium features are available for development/testing.
 *
 * When the "nsgmod.subscriber_bypass" toggle in NSG Settings is ON (default),
 * each method returns a spoofed value that passes all license checks.
 * When the toggle is OFF, the original NSG implementation is called through,
 * restoring normal (unlicensed) behaviour immediately without a restart.
 *
 *   Application.d() — subscriber.activate  → true
 *   Application.e() — subscriber.expire    → far-future Date
 *   Application.f() — subscriber.uid       → "dev-user"
 *   Application.g() — has("config")        → true
 */
public class SubscriberHook {

    private static final String TAG = "NSGBandHook/Sub";
    private static final String APP_CLASS = "com.qtrun.sys.Application";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public SubscriberHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    /** Returns true when the bypass toggle is enabled (default: false). */
    private static boolean bypassEnabled() {
        try {
            // ActivityThread is a hidden API — access via reflection.
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            android.app.Application app =
                    (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
            if (app == null) return false;
            // Toggle is persisted by NSG's own PreferenceManager into its default prefs file.
            SharedPreferences prefs = app.getSharedPreferences(
                    "com.qtrun.QuickTest_preferences", Context.MODE_PRIVATE);
            return prefs.getBoolean(SettingsToggleHook.PREF_KEY, false);
        } catch (Throwable t) {
            Log.w(TAG, "bypassEnabled check failed: " + t);
            return false;
        }
    }

    public void install() {
        try {
            Class<?> appCls = loader.loadClass(APP_CLASS);

            // d() → boolean  (subscriber.activate)
            xposed.hook(appCls.getDeclaredMethod("d")).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!bypassEnabled()) return chain.proceed();
                    return true;
                }
            });

            // e() → Date  (subscriber.expire — far future)
            xposed.hook(appCls.getDeclaredMethod("e")).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!bypassEnabled()) return chain.proceed();
                    return new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
                }
            });

            // f() → String  (subscriber.uid — non-empty so isEmpty() checks pass)
            xposed.hook(appCls.getDeclaredMethod("f")).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!bypassEnabled()) return chain.proceed();
                    return "dev-user";
                }
            });

            // g() → boolean  (has("config"))
            xposed.hook(appCls.getDeclaredMethod("g")).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!bypassEnabled()) return chain.proceed();
                    return true;
                }
            });

            Log.i(TAG, "subscriber hooks installed");
        } catch (Throwable t) {
            Log.w(TAG, "subscriber hook install failed", t);
        }
    }
}
