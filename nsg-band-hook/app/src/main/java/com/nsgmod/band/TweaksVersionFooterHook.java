package com.nsgmod.band;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Appends the tweaks-module version code and NSG flavor letter to NSG's
 * bottom-left footer version {@code TextView} ({@code @id/info_version}), so it
 * reads e.g. {@code 4.8.9 66:q} on the qtrun flavor or {@code 4.8.9 66:g} on
 * google-play. Reusing the same view keeps the footer's existing grey colour
 * ({@code #424242}) automatically; there is no toggle or user configuration.
 *
 * <p>NSG sets {@code info_version} in the {@code onResume()} of both
 * {@code com.qtrun.nsg.NormalActivity} (version name only) and
 * {@code com.qtrun.nsg.AdvancedActivity} (version name, plus {@code " *****"}
 * when activated). Both class names are stable across flavors, so no
 * {@link ClassMapping} entries are required. The {@code info_version} resource
 * id differs per flavor, so it is resolved by name at runtime.
 *
 * <p>The suffix is computed once per process and cached; each {@code onResume}
 * therefore only performs a cached id lookup, {@code findViewById},
 * {@code getText}, a containment check and {@code setText}. When activated, the
 * suffix is inserted immediately before the {@code " *****"} marker so it stays
 * adjacent to the NSG version.
 */
public class TweaksVersionFooterHook {

    private static final String TAG = "NSGBandHook";
    private static final String NORMAL_ACTIVITY = "com.qtrun.nsg.NormalActivity";
    private static final String ADVANCED_ACTIVITY = "com.qtrun.nsg.AdvancedActivity";
    private static final String ACTIVATED_MARKER = " *****";
    private static final int FALLBACK_VERSION_CODE = 67;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private static volatile int infoVersionId = 0;
    private static volatile String cachedSuffix = null;

    public TweaksVersionFooterHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installFor(NORMAL_ACTIVITY);
        installFor(ADVANCED_ACTIVITY);
    }

    private void installFor(String className) {
        try {
            Class<?> activityClass = ClassMapping.loadClass(className, loader);
            if (activityClass == null) {
                Log.w(TAG, "footer version hook: " + className + " not found, skipped");
                return;
            }
            Method onResume = activityClass.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            xposed.hook(onResume).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        applySuffix((Activity) chain.getThisObject());
                    } catch (Throwable t) {
                        Log.w(TAG, "footer version update failed: " + t);
                    }
                    return result;
                }
            });
            Log.i(TAG, "installed footer version hook on " + className);
        } catch (Throwable t) {
            Log.w(TAG, "footer version hook install failed for " + className + ": " + t);
        }
    }

    private void applySuffix(Activity activity) {
        if (activity == null) return;

        int id = infoVersionId;
        if (id == 0) {
            id = activity.getResources().getIdentifier(
                    "info_version", "id", activity.getPackageName());
            if (id == 0) return;
            infoVersionId = id;
        }

        View view = activity.findViewById(id);
        if (!(view instanceof TextView)) return;
        TextView versionView = (TextView) view;

        CharSequence currentSequence = versionView.getText();
        String current = currentSequence == null ? "" : currentSequence.toString();

        String suffix = cachedSuffix;
        if (suffix == null) {
            suffix = buildSuffix(activity);
            cachedSuffix = suffix;
        }

        if (current.contains(suffix)) return;

        int marker = current.indexOf(ACTIVATED_MARKER);
        String updated;
        if (marker >= 0) {
            updated = current.substring(0, marker) + suffix + current.substring(marker);
        } else {
            updated = current + suffix;
        }
        versionView.setText(updated);
    }

    private String buildSuffix(Activity activity) {
        return " " + resolveVersionCode(activity) + ":" + flavorLetter();
    }

    private int resolveVersionCode(Activity activity) {
        try {
            ApplicationInfo moduleInfo = xposed.getModuleApplicationInfo();
            if (moduleInfo != null) {
                PackageManager pm = activity.getPackageManager();
                String archivePath = moduleInfo.sourceDir != null
                        ? moduleInfo.sourceDir : moduleInfo.publicSourceDir;
                if (archivePath != null) {
                    try {
                        PackageInfo archiveInfo = pm.getPackageArchiveInfo(archivePath, 0);
                        if (archiveInfo != null) {
                            return versionCodeOf(archiveInfo);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    PackageInfo installed = pm.getPackageInfo(moduleInfo.packageName, 0);
                    if (installed != null) {
                        return versionCodeOf(installed);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "module version code lookup failed: " + t);
        }
        return FALLBACK_VERSION_CODE;
    }

    private int versionCodeOf(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
    }

    private String flavorLetter() {
        return FlavorDetector.detect(loader) == FlavorDetector.Flavor.GPLAY ? "g" : "q";
    }
}
