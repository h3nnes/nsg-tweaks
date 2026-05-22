package com.nsgmod.band;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Keeps NSG's loading-status bar visible after a log file is successfully
 * loaded for replay, showing the filename and "Replaying" status.
 *
 * NSG behaviour (unhooked):
 *   1. User opens a log file → t7.e.b() shows wait_progress_layout (VISIBLE),
 *      sets wait_progress_tips text to "Loading <filename> …"
 *   2. Load completes → a4.h.e(Object) case 5 → AdvancedActivity.K() sets
 *      wait_progress_layout to GONE and clears FLAG_NOT_TOUCHABLE.
 *
 * Our hook (after a4.h.e):
 *   - Only when obj (DataSource) is non-null (load succeeded)
 *   - Re-show wait_progress_layout (VISIBLE)
 *   - Hide wait_progress_bar (GONE) — remove the progress bar, keep the label
 *   - Set wait_progress_tips text to "<filename> — Replaying"
 *   - Do NOT set FLAG_NOT_TOUCHABLE (user must be able to interact)
 *
 * When user stops replay / closes file:
 *   AdvancedActivity.K() is called again from other paths.  In those cases
 *   Workspace.c (DataSource) will be null or a new live session has started,
 *   so K() proceeds normally and sets the layout GONE again.
 *
 * View IDs resolved via Resources.getIdentifier() so we don't hard-code int values.
 */
public class ReplayStatusBarHook {

    private static final String TAG = "NSGBandHook_Replay";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // Reflection handles
    private Field  f76a;  // a4.h.f76a — int switch case
    private Field  f77b;  // a4.h.f77b — Object (AdvancedActivity)
    private Field  f78c;  // a4.h.f78c — Object (filename String)

    private boolean reflectionReady = false;

    public ReplayStatusBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> hClass = loader.loadClass("a4.h");
            f76a = hClass.getField("f76a");
            f77b = hClass.getField("f77b");
            f78c = hClass.getField("f78c");
            reflectionReady = true;
            Log.i(TAG, "reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "install skipped — reflection not ready");
            return;
        }
        try {
            Class<?> hClass = loader.loadClass("a4.h");
            Method eMethod = hClass.getMethod("e", Object.class);

            xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        handleAfterE(chain.getThisObject(), chain.getArg(0));
                    } catch (Throwable t) {
                        Log.w(TAG, "handleAfterE failed: " + t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "hook installed on a4.h.e(Object)");
        } catch (Exception e) {
            Log.e(TAG, "install failed: " + e);
        }
    }

    private void handleAfterE(Object thisObj, Object dataSourceArg) throws Exception {
        // Only handle case 5 (log file load completion)
        int switchCase = f76a.getInt(thisObj);
        if (switchCase != 5) return;

        // Only when load succeeded (DataSource non-null)
        if (dataSourceArg == null) return;

        String filename = (String) f78c.get(thisObj);
        Activity activity = (Activity) f77b.get(thisObj);
        if (activity == null || filename == null) return;

        String pkg = "com.qtrun.QuickTest";
        int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
        int progressId = activity.getResources().getIdentifier("wait_progress_bar",    "id", pkg);
        int tipsId     = activity.getResources().getIdentifier("wait_progress_tips",   "id", pkg);

        if (layoutId == 0 || progressId == 0 || tipsId == 0) {
            Log.w(TAG, "view id(s) not found — layout=" + layoutId
                    + " progress=" + progressId + " tips=" + tipsId);
            return;
        }

        View layout = activity.findViewById(layoutId);
        if (layout == null) {
            Log.w(TAG, "wait_progress_layout not found in activity");
            return;
        }

        View progressBar = layout.findViewById(progressId);
        TextView tipsView = (TextView) layout.findViewById(tipsId);

        if (progressBar == null || tipsView == null) {
            Log.w(TAG, "child views not found inside wait_progress_layout");
            return;
        }

        // Hide the spinning progress bar — load is done
        progressBar.setVisibility(View.GONE);

        // Update the label: "<filename> — Replaying"
        tipsView.setText(filename + " \u2014 Replaying");

        // Re-show the container (K() just set it GONE)
        layout.setVisibility(View.VISIBLE);

        Log.i(TAG, "replay status bar shown for: " + filename);
    }
}
