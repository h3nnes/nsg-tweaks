package com.nsgmod.band;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Keeps NSG's loading-status bar visible after a log file is successfully
 * loaded for replay, showing the filename and "Replaying" status.
 *
 * NSG behaviour (unhooked):
 *   t7.e.b() case 4: shows wait_progress_layout ("Loading <file> …")
 *   a4.h.e() case 5: calls AdvancedActivity.K() → sets layout GONE, then
 *                    populates Workspace, calls M() and invalidateOptionsMenu().
 *
 * Strategy — three hooks that work together:
 *
 * Prong A: a4.h.e(Object) case 5 after-hook.
 *   Fires after the full case-5 body (K, workspace setup, M(), menu) completes.
 *   Re-shows the bar with "<filename> — Replaying" text and progress bar hidden.
 *   Sets replayActive = true and stores a WeakReference to the Activity.
 *
 * Prong B: AdvancedActivity.K() intercept.
 *   Uses Workspace.c (DataSource field) to distinguish the two K() callers:
 *   • DataSource == null  → called from a4.h.e() case 5 during file loading
 *     (workspace.c() clears it first; workspace.f() sets it after K()).
 *     → Suppress K(), keep bar visible.
 *   • DataSource != null  → called from t7.e.e() case 1 (user stopped replay).
 *     h0.G() never clears the DataSource, so it remains set.
 *     → Allow K(), clear replayActive so save-log / live mode resume normally.
 *
 * Prong C: t7.h0.E() before-hook (start-new-test).
 *   When menu_open_new_test is tapped in replay mode, NSG calls h0.D(false) then
 *   h0.E() directly — it NEVER calls K(). So replayActive would stay true forever.
 *   Prong C detects h0.E() while replayActive==true, clears the flag, and hides
 *   the bar on the main thread. This is the primary "end replay" signal.
 *   h0.E() is also called from the "Discard" dialog path, so all stop-replay
 *   cases are covered.
 *
 * Prong D: t7.x0.c(Activity) before-hook (graceful exit).
 *   In replay mode h0.A() returns false (no live test), so menu_exit takes the
 *   fast-exit branch: x0.c() is called directly and I() (which shows the loading
 *   spinner) is never called. The user sees the replay bar vanish with no spinner.
 *   Prong D intercepts x0.c() — when replayActive, shows wait_progress_bar
 *   (spinner + "Stopping…") before proceeding, matching the live-test exit UX.
 *   replayActive is cleared here so Prong B does not interfere with subsequent K().
 */
public class ReplayStatusBarHook {

    private static final String TAG = "NSGBandHook_Replay";

    /**
     * True while a log file is loaded and being replayed.
     * Set to true by Prong A when load succeeds.
     * Cleared by Prong C when h0.E() fires (start-new-test path).
     * Also cleared by Prong B as a fallback if K() is called with DataSource set.
     */
    private static volatile boolean replayActive = false;

    /** Weak reference to the Activity currently showing the replay bar (set by Prong A). */
    private static volatile WeakReference<Activity> replayActivity = new WeakReference<>(null);

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // a4.h fields — runtime names are a/b/c (JADX renames them to f76a/f77b/f78c)
    private Field f76a;  // int   — switch case       (runtime: "a")
    private Field f77b;  // Object — AdvancedActivity (runtime: "b")
    private Field f78c;  // Object — filename String  (runtime: "c")

    // Workspace fields (used to detect live→replay transition)
    private Field wsSingleton;
    private Field wsDataSource;

    private boolean reflectionReady = false;

    public ReplayStatusBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> hClass  = loader.loadClass("a4.h");
            Class<?> wsClass = loader.loadClass("com.qtrun.sys.Workspace");

            f76a = hClass.getDeclaredField("a"); f76a.setAccessible(true);  // int discriminator
            f77b = hClass.getDeclaredField("b"); f77b.setAccessible(true);  // AdvancedActivity
            f78c = hClass.getDeclaredField("c"); f78c.setAccessible(true);  // filename String

            wsSingleton  = wsClass.getField("j");
            wsDataSource = wsClass.getField("c");

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
        installProngA();
        installProngB();
        installProngC();
        installProngD();
    }

    // -----------------------------------------------------------------------
    // Prong A: a4.h.e(Object) — after-hook for file load completion (case 5)
    // -----------------------------------------------------------------------
    private void installProngA() {
        try {
            Class<?> hClass  = loader.loadClass("a4.h");
            Method   eMethod = hClass.getMethod("e", Object.class);

            xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        int switchCase = f76a.getInt(chain.getThisObject());
                        if (switchCase != 5) return result;

                        Object dataSourceArg = chain.getArg(0);
                        if (dataSourceArg == null) {
                            // Load failed — make sure flag is clear
                            replayActive = false;
                            Log.d(TAG, "Prong A: load failed, replayActive=false");
                            return result;
                        }

                        Activity activity = (Activity) f77b.get(chain.getThisObject());
                        String   filename = (String)   f78c.get(chain.getThisObject());
                        if (activity == null || filename == null) return result;

                        // Mark replay active BEFORE showing bar so Prong B won't
                        // suppress an immediate re-layout triggered by setVisibility
                        replayActive = true;
                        replayActivity = new WeakReference<>(activity);
                        showReplayBar(activity, filename);
                        Log.i(TAG, "Prong A: replay bar shown, file=" + filename);
                    } catch (Throwable t) {
                        Log.w(TAG, "Prong A failed: " + t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "Prong A installed on a4.h.e(Object)");
        } catch (Exception e) {
            Log.e(TAG, "installProngA failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong B: AdvancedActivity.K() — suppress GONE when replayActive,
    //          BUT allow K() when the user explicitly stops replay.
    //
    // How to distinguish the two K() callers:
    //   • K() called from a4.h.e() case 5 (file loading):
    //       workspace.c (DataSource) is NULL — load hasn't set it yet.
    //       → Suppress K(), keep the bar visible.
    //   • K() called from t7.e.e() case 1 (user tapped stop/save):
    //       h0.G() ran but never clears workspace.c; DataSource is still set.
    //       → Allow K(), clear replayActive so normal behaviour resumes.
    // -----------------------------------------------------------------------
    private void installProngB() {
        try {
            Class<?> activityClass = loader.loadClass("com.qtrun.nsg.AdvancedActivity");
            Method   kMethod       = activityClass.getDeclaredMethod("K");

            xposed.hook(kMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!replayActive) {
                        // Live mode — always allow K() normally
                        return chain.proceed();
                    }

                    // In replay mode: check whether DataSource is null to decide.
                    boolean dataSourceNull = true; // assume loading phase unless we can check
                    try {
                        Object ws = wsSingleton.get(null);  // Workspace singleton (static field)
                        if (ws != null) {
                            dataSourceNull = (wsDataSource.get(ws) == null);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Prong B: workspace check failed: " + t);
                    }

                    if (dataSourceNull) {
                        // K() is called during file loading — DataSource not yet assigned.
                        // Suppress it, clear FLAG_NOT_TOUCHABLE so UI stays interactive.
                        try {
                            Activity activity = (Activity) chain.getThisObject();
                            activity.getWindow().clearFlags(16);
                        } catch (Throwable t) {
                            Log.w(TAG, "Prong B: clearFlags failed: " + t);
                            return chain.proceed(); // fall back on error
                        }
                        Log.d(TAG, "Prong B: K() suppressed (load phase, DataSource null)");
                        return null; // skip original K()
                    } else {
                        // K() is called because user stopped replay (DataSource still live).
                        // Allow it to run so the bar hides and save-log works normally.
                        replayActive = false;
                        Log.i(TAG, "Prong B: K() allowed (stop replay, DataSource set), replayActive=false");
                        return chain.proceed();
                    }
                }
            });

            Log.i(TAG, "Prong B installed on AdvancedActivity.K()");
        } catch (Exception e) {
            Log.e(TAG, "installProngB failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong C: t7.h0.E() — start-new-test, the primary "end replay" signal.
    //
    // When menu_open_new_test is tapped in replay mode, NSG calls:
    //   J() → h0.D(false) → h0.E()
    // It NEVER calls K(), so Prong B never fires and replayActive stays true.
    // Hook h0.E() before it runs: clear replayActive and hide the bar.
    // h0.E() runs on the main thread, so we can touch views directly.
    // -----------------------------------------------------------------------
    private void installProngC() {
        try {
            Class<?> h0Class  = loader.loadClass("t7.h0");
            Method   eMethod  = h0Class.getDeclaredMethod("E");

            xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (replayActive) {
                        replayActive = false;
                        Log.i(TAG, "Prong C: h0.E() while replay active — hiding bar, replayActive=false");
                        hideReplayBar();
                    }
                    return chain.proceed();
                }
            });

            Log.i(TAG, "Prong C installed on t7.h0.E()");
        } catch (Exception e) {
            Log.e(TAG, "installProngC failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong D: t7.x0.c(Activity) — graceful-exit path in replay mode.
    //
    // In live mode:  menu_exit → h0.A()==true → t7.b runnable → activity.I() → x0.c()
    // In replay mode: menu_exit → h0.A()==false → x0.c() DIRECTLY (no I() call)
    //
    // Fix: before x0.c() proceeds, show the spinner bar so the user sees the
    //      normal "Stopping…" loading animation during the ~250 ms before finish().
    // -----------------------------------------------------------------------
    private void installProngD() {
        try {
            Class<?> x0Class   = loader.loadClass("t7.x0");
            Class<?> actClass  = loader.loadClass("android.app.Activity");
            Method   cMethod   = x0Class.getDeclaredMethod("c", actClass);

            xposed.hook(cMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (replayActive) {
                        replayActive = false; // clear first so Prong B doesn't suppress K()
                        try {
                            Activity activity = (Activity) chain.getArg(0);
                            if (activity != null && !activity.isFinishing()) {
                                showExitSpinner(activity);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Prong D: showExitSpinner failed: " + t);
                        }
                        Log.i(TAG, "Prong D: x0.c() in replay mode — spinner shown, replayActive=false");
                    }
                    return chain.proceed();
                }
            });

            Log.i(TAG, "Prong D installed on t7.x0.c(Activity)");
        } catch (Exception e) {
            Log.e(TAG, "installProngD failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Hide the replay bar using the stored Activity reference. */
    private void hideReplayBar() {
        Activity activity = replayActivity.get();
        if (activity == null || activity.isFinishing()) return;
        Runnable run = () -> {
            try {
                String pkg     = "com.qtrun.QuickTest";
                int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
                if (layoutId == 0) return;
                View layout = activity.findViewById(layoutId);
                if (layout != null) layout.setVisibility(View.GONE);
                Log.d(TAG, "hideReplayBar: bar hidden");
            } catch (Throwable t) {
                Log.w(TAG, "hideReplayBar failed: " + t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            new Handler(Looper.getMainLooper()).post(run);
        }
    }

    private void showReplayBar(Activity activity, String filename) {
        try {
            String pkg     = "com.qtrun.QuickTest";
            int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
            int progressId = activity.getResources().getIdentifier("wait_progress_bar",    "id", pkg);
            int tipsId     = activity.getResources().getIdentifier("wait_progress_tips",   "id", pkg);

            if (layoutId == 0 || progressId == 0 || tipsId == 0) {
                Log.w(TAG, "showReplayBar: view ids not found");
                return;
            }

            View layout = activity.findViewById(layoutId);
            if (layout == null) { Log.w(TAG, "showReplayBar: layout not found"); return; }

            View     progressBar = layout.findViewById(progressId);
            TextView tipsView    = (TextView) layout.findViewById(tipsId);
            if (progressBar == null || tipsView == null) {
                Log.w(TAG, "showReplayBar: child views not found");
                return;
            }

            // Hide spinner, update label, show container
            progressBar.setVisibility(View.GONE);
            tipsView.setText(filename + " \u2014 Replaying");
            layout.setVisibility(View.VISIBLE);

        } catch (Throwable t) {
            Log.w(TAG, "showReplayBar failed: " + t);
        }
    }

    /** Show the loading spinner bar (matching the live-test exit UX) when exiting from replay. */
    private void showExitSpinner(Activity activity) {
        try {
            String pkg     = "com.qtrun.QuickTest";
            int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
            int progressId = activity.getResources().getIdentifier("wait_progress_bar",    "id", pkg);
            int tipsId     = activity.getResources().getIdentifier("wait_progress_tips",   "id", pkg);

            if (layoutId == 0 || progressId == 0 || tipsId == 0) {
                Log.w(TAG, "showExitSpinner: view ids not found");
                return;
            }

            View layout = activity.findViewById(layoutId);
            if (layout == null) { Log.w(TAG, "showExitSpinner: layout not found"); return; }

            View     progressBar = layout.findViewById(progressId);
            TextView tipsView    = (TextView) layout.findViewById(tipsId);
            if (progressBar == null || tipsView == null) {
                Log.w(TAG, "showExitSpinner: child views not found");
                return;
            }

            // Show spinner and "Stopping…" label — mirrors what AdvancedActivity.I() does
            progressBar.setVisibility(View.VISIBLE);
            int stoppingId = activity.getResources().getIdentifier(
                    "tips_stop_test_waiting", "string", pkg);
            if (stoppingId != 0) {
                tipsView.setText(activity.getString(stoppingId));
            } else {
                tipsView.setText("Stopping…");
            }
            layout.setVisibility(View.VISIBLE);

            Log.d(TAG, "showExitSpinner: spinner shown");
        } catch (Throwable t) {
            Log.w(TAG, "showExitSpinner failed: " + t);
        }
    }
}
