package com.nsgmod.band;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
 *   a4.h.e() case 5: calls AdvancedActivity.K() -> sets layout GONE, then
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
 *   • DataSource == null  -> called from a4.h.e() case 5 during file loading
 *     (workspace.c() clears it first; workspace.f() sets it after K()).
 *     -> Suppress K(), keep bar visible.
 *   • DataSource != null  -> called from t7.e.e() case 1 (user stopped replay).
 *     g0.G() never clears the DataSource, so it remains set.
 *     -> Allow K(), clear replayActive so save-log / live mode resume normally.
 *
 * Prong C: t7.g0.E() before-hook (start-new-test).
 *   When menu_open_new_test is tapped in replay mode, NSG calls g0.D(false) then
 *   g0.E() directly — it NEVER calls K(). So replayActive would stay true forever.
 *   Prong C detects g0.E() while replayActive==true, clears the flag, and hides
 *   the bar on the main thread. This is the primary "end replay" signal.
 *   g0.E() is also called from the "Discard" dialog path, so all stop-replay
 *   cases are covered.
 *   (v4.8.4: t7.h0 — renamed to t7.g0 in v4.8.6)
 *
 * Prong D: t7.w0.c(Activity) before-hook (graceful exit).
 *   In replay mode g0.A() returns false (no live test), so menu_exit takes the
 *   fast-exit branch: w0.c() is called directly and I() (which shows the loading
 *   spinner) is never called. The user sees the replay bar vanish with no spinner.
 *   Prong D intercepts w0.c() — when replayActive, shows wait_progress_bar
 *   (spinner + "Stopping…") before proceeding, matching the live-test exit UX.
 *   replayActive is cleared here so Prong B does not interfere with subsequent K().
 *   (v4.8.4: t7.x0 — renamed to t7.w0 in v4.8.6)
 *
 * Prong E: t7.p.a(MenuItem) intercept — allow "load logfile" while replay bar is visible.
 *   NSG's sidebar handler (t7.p.a, AdvancedActivity's DrawerLayout listener) guards
 *   the file picker launch with:
 *     if (advancedActivity.findViewById(R.id.wait_progress_layout).getVisibility() != 0)
 *   Since the replay bar keeps wait_progress_layout VISIBLE, this guard silently
 *   blocks the file picker while a replay is active and the bar is not dismissed.
 *   Prong E intercepts t7.p.a() when replayActive == true and the selected item is
 *   menu_load_logfile: it directly invokes advancedActivity.K.c() with MIME type "*&#47;*"
 *   (respecting the Application.d() subscription guard) and closes the drawer,
 *   bypassing the visibility guard entirely.
 *   (v4.8.4: t7.q — renamed to t7.p in v4.8.6)
 *
 * Prong F: t7.e.b(Object) before-hook — restore spinner visibility before second load.
 *   t7.e.b() case 4 is NSG's "Loading…" phase: it sets wait_progress_layout VISIBLE
 *   and calls progressBar.setIndeterminate(true), but does NOT call
 *   progressBar.setVisibility(VISIBLE). On first load progressBar starts VISIBLE
 *   (NSG default), so the spinner shows. But showReplayBar() sets progressBar GONE
 *   to hide it during replay. On a second load the layout is already VISIBLE and
 *   progressBar is still GONE — so the spinner never appears during loading.
 *   Prong F fires before case 4 executes: when replayActive==true it restores
 *   progressBar to VISIBLE so the loading spinner shows normally.
 */
public class ReplayStatusBarHook {

    private static final String TAG = "NSGBandHook";
    private static final String REPLAY_TAG = "NSGBandHook:ReplayBar";

    /**
     * True while a log file is loaded and being replayed.
     * Set to true by Prong A when load succeeds.
     * Cleared by Prong C when g0.E() fires (start-new-test path).
     * Also cleared by Prong B as a fallback if K() is called with DataSource set.
     */
    private static volatile boolean replayActive = false;

    /**
     * True when the user has dismissed the replay bar for the current session.
     * Reset to false each time a new log file is loaded (Prong A), so the bar
     * reappears for every new replay and can be dismissed again independently.
     */
    private static volatile boolean replayBarDismissed = false;

    /** Weak reference to the Activity currently showing the replay bar (set by Prong A). */
    private static volatile WeakReference<Activity> replayActivity = new WeakReference<>(null);

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // a4.h fields — runtime names are a/b/c on qtrun, b/d/c on gplay
    private Field f76a;  // int   — switch case
    private Field f77b;  // Object — AdvancedActivity
    private Field f78c;  // Object — filename String

    // Workspace fields (used to detect live->replay transition)
    private Field wsSingleton;
    private Field wsDataSource;

    // Prong E: t7.q menu handler bypass
    private Field advActivityKField;    // AdvancedActivity.K — the logfile picker (d.d)
    private Method pickerCMethod;       // d.d.c(Object) [qtrun] / d.d.a(Object) [gplay]
    private Method appDMethod;          // com.qtrun.sys.Application.d() — subscription check
    private Method drawerDMethod;       // DrawerLayout.d(boolean) — closeDrawers()

    // Prong F: t7.e.b(Object) — runtime field names for the discriminator and Activity
    private Field teDiscriminator;      // t7.e.a (int) [qtrun] / c6.f.b (int) [gplay]
    private Field teActivity;           // t7.e.b (AdvancedActivity) [qtrun] / c6.f.c [gplay]

    // a4.h / c6.h synthetic switch case that signals a successful log load.
    private int loadCompleteCase;

    private boolean reflectionReady = false;

    public ReplayStatusBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> hClass  = ClassMapping.loadClass("a4.h", loader);
            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            if (hClass == null || wsClass == null) {
                Log.i(REPLAY_TAG, "initReflection: a4.h or Workspace missing, skipping");
                return;
            }
String hDiscriminator = ClassMapping.runtimeFieldName("a4.h", "a", loader);
            String hActivity      = ClassMapping.runtimeFieldName("a4.h", "b", loader);
            String hFilename      = ClassMapping.runtimeFieldName("a4.h", "c", loader);
            f76a = hClass.getDeclaredField(hDiscriminator); f76a.setAccessible(true);  // int discriminator
            f77b = hClass.getDeclaredField(hActivity);      f77b.setAccessible(true);  // AdvancedActivity
            f78c = hClass.getDeclaredField(hFilename);      f78c.setAccessible(true);  // filename String
String wsSingletonName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader);
            String wsDataSourceName = ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "c", loader);
            wsSingleton  = wsClass.getField(wsSingletonName);
            wsDataSource = wsClass.getField(wsDataSourceName);
// Prong E reflection
            Class<?> advActivityClass = ClassMapping.loadClass("com.qtrun.nsg.AdvancedActivity", loader);
            String advKFieldName = ClassMapping.runtimeFieldName("com.qtrun.nsg.AdvancedActivity", "K", loader);
            advActivityKField = advActivityClass.getDeclaredField(advKFieldName);
            advActivityKField.setAccessible(true);
Class<?> ddClass = ClassMapping.loadClass("d.d", loader);
            pickerCMethod = ClassMapping.getDeclaredMethod(ddClass, "d.d", "c", loader, Object.class);
            pickerCMethod.setAccessible(true);
Class<?> appClass = ClassMapping.loadClass("com.qtrun.sys.Application", loader);
            appDMethod = ClassMapping.getDeclaredMethod(appClass, "com.qtrun.sys.Application", "d", loader);
            // d() is public static synchronized — accessible without setAccessible
Class<?> drawerClass = ClassMapping.loadClass("androidx.drawerlayout.widget.DrawerLayout", loader);
            drawerDMethod = ClassMapping.getMethod(drawerClass, "androidx.drawerlayout.widget.DrawerLayout",
                    "d", loader, boolean.class);
// Prong F reflection — t7.e discriminator / activity fields
            Class<?> teClass = ClassMapping.loadClass("t7.e", loader);
            if (teClass == null) {
                Log.i(REPLAY_TAG, "initReflection: t7.e not available, skipping Prong F");
                return;
            }
            String teDiscName = ClassMapping.runtimeFieldName("t7.e", "a", loader);
            String teActName  = ClassMapping.runtimeFieldName("t7.e", "b", loader);
            teDiscriminator = teClass.getDeclaredField(teDiscName); teDiscriminator.setAccessible(true);
            teActivity      = teClass.getDeclaredField(teActName);  teActivity.setAccessible(true);
// a4.h / c6.h switch case for successful log load (qtrun=5, gplay=0).
            loadCompleteCase = ClassMapping.runtimeIntConstant("a4.h|LOAD_COMPLETE_CASE", 5, loader);
reflectionReady = true;
            Log.i(REPLAY_TAG, "initReflection: reflection ready");
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "initReflection failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(REPLAY_TAG, "install skipped — reflection not ready");
            return;
        }
        installProngA();
        installProngB();
        installProngC();
        installProngD();
        installProngE();
        installProngF();
        installProngG();
        Log.i(REPLAY_TAG, "installed (7 prongs), loadCompleteCase=" + loadCompleteCase);
    }

    // -----------------------------------------------------------------------
    // Prong A: a4.h.e(Object) — after-hook for file load completion (case 5)
    // -----------------------------------------------------------------------
    private void installProngA() {
        try {
            Class<?> hClass  = ClassMapping.loadClass("a4.h", loader);
            Method   eMethod = ClassMapping.getMethod(hClass, "a4.h", "e", loader, Object.class);
xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        int switchCase = f76a.getInt(chain.getThisObject());
if (switchCase != loadCompleteCase) return result;

                        Object dataSourceArg = chain.getArg(0);
                        if (dataSourceArg == null) {
                            // Load failed — make sure flag is clear
replayActive = false;
                            return result;
                        }

                        Activity activity = (Activity) f77b.get(chain.getThisObject());
                        String   filename = (String)   f78c.get(chain.getThisObject());
if (activity == null || filename == null) return result;

                        // Mark replay active BEFORE showing bar so Prong B won't
                        // suppress an immediate re-layout triggered by setVisibility.
                        // Also reset dismissed flag so the bar shows for each new load.
                        replayActive = true;
                        replayBarDismissed = false;
                        replayActivity = new WeakReference<>(activity);
showReplayBar(activity, filename);
                    } catch (Throwable t) {
                        Log.w(REPLAY_TAG, "Prong A failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngA failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong B: AdvancedActivity.K() — suppress GONE when replayActive,
    //          BUT allow K() when the user explicitly stops replay.
    //
    // How to distinguish the two K() callers:
    //   • K() called from a4.h.e() case 5 (file loading):
    //       workspace.c (DataSource) is NULL — load hasn't set it yet.
    //       -> Suppress K(), keep the bar visible.
    //   • K() called from t7.e.e() case 1 (user tapped stop/save):
    //       g0.G() ran but never clears workspace.c; DataSource is still set.
    //       -> Allow K(), clear replayActive so normal behaviour resumes.
    // -----------------------------------------------------------------------
    private void installProngB() {
        try {
            Class<?> activityClass = ClassMapping.loadClass("com.qtrun.nsg.AdvancedActivity", loader);
            Method   kMethod       = ClassMapping.getDeclaredMethod(activityClass,
                    "com.qtrun.nsg.AdvancedActivity", "K", loader);
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
                        Log.w(REPLAY_TAG, "Prong B: workspace check failed: " + t);
                    }
if (dataSourceNull) {
                        // K() is called during file loading — DataSource not yet assigned.
                        // Suppress it, clear FLAG_NOT_TOUCHABLE so UI stays interactive.
                        try {
                            Activity activity = (Activity) chain.getThisObject();
                            activity.getWindow().clearFlags(16);
} catch (Throwable t) {
                            Log.w(REPLAY_TAG, "Prong B: clearFlags failed: " + t);
                            return chain.proceed(); // fall back on error
                        }
                        return null; // skip original K()
                    } else {
                        // K() is called because user stopped replay (DataSource still live).
                        // Allow it to run so the bar hides and save-log works normally.
                        replayActive = false;
return chain.proceed();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngB failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong C: t7.g0.E() — start-new-test, the primary "end replay" signal.
    //
    // When menu_open_new_test is tapped in replay mode, NSG calls:
    //   J() -> g0.D(false) -> g0.E()
    // It NEVER calls K(), so Prong B never fires and replayActive stays true.
    // Hook g0.E() before it runs: clear replayActive and hide the bar.
    // g0.E() runs on the main thread, so we can touch views directly.
    // (v4.8.4: t7.h0 — renamed to t7.g0 in v4.8.6)
    // -----------------------------------------------------------------------
    private void installProngC() {
        try {
            Class<?> h0Class  = ClassMapping.loadClass("t7.g0", loader);
            if (h0Class == null) {
                Log.i(REPLAY_TAG, "installProngC: t7.g0 not available, skipping Prong C");
                return;
            }
            Method   eMethod  = ClassMapping.getDeclaredMethod(h0Class, "t7.g0", "E", loader);
xposed.hook(eMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
if (replayActive) {
                        replayActive = false;
hideReplayBar();
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngC failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong D: t7.w0.c(Activity) — graceful-exit path in replay mode.
    //
    // In live mode:  menu_exit -> g0.A()==true -> t7.b runnable -> activity.I() -> w0.c()
    // In replay mode: menu_exit -> g0.A()==false -> w0.c() DIRECTLY (no I() call)
    //
    // Fix: before w0.c() proceeds, show the spinner bar so the user sees the
    //      normal "Stopping…" loading animation during the ~250 ms before finish().
    // (v4.8.4: t7.x0 — renamed to t7.w0 in v4.8.6)
    // -----------------------------------------------------------------------
    private void installProngD() {
        try {
            Class<?> x0Class   = ClassMapping.loadClass("t7.w0", loader);
            Class<?> actClass  = ClassMapping.loadClass("android.app.Activity", loader);
            if (x0Class == null || actClass == null) {
                Log.i(REPLAY_TAG, "installProngD: t7.w0 or Activity missing, skipping Prong D");
                return;
            }
            Method   cMethod   = ClassMapping.getDeclaredMethod(x0Class, "t7.w0", "c", loader, actClass);
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
                            Log.w(REPLAY_TAG, "Prong D: showExitSpinner failed: " + t);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngD failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong E: t7.p.a(MenuItem) — bypass visibility guard for "load logfile"
    //          while the replay bar is displayed.
    //
    // NSG's guard: if (wait_progress_layout.getVisibility() != 0) { open picker }
    // The replay bar keeps that layout VISIBLE, so the picker is silently blocked.
    // We intercept the menu handler and, when replayActive==true and the item is
    // menu_load_logfile, directly call advancedActivity.K.c(new String[]{"*/*"})
    // (mirroring what NSG would do) then close the drawer and return true.
    // (v4.8.4: t7.q — renamed to t7.p in v4.8.6; t7.p is AdvancedActivity's
    //  NavigationView.OnNavigationItemSelectedListener)
    // -----------------------------------------------------------------------
    private void installProngE() {
        try {
            Class<?> tqClass      = ClassMapping.loadClass("t7.p", loader);
            Class<?> menuItemClass = ClassMapping.loadClass("android.view.MenuItem", loader);
            if (tqClass == null || menuItemClass == null) {
                Log.i(REPLAY_TAG, "installProngE: t7.p or MenuItem missing, skipping Prong E");
                return;
            }
            Method   aMethod       = ClassMapping.getDeclaredMethod(tqClass, "t7.p", "a", loader, menuItemClass);
            aMethod.setAccessible(true);
xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (!replayActive) {
                        return chain.proceed();
                    }
                    try {
                        MenuItem item = (MenuItem) chain.getArg(0);
                        if (item == null) return chain.proceed();

                        // Resolve menu_load_logfile resource id at runtime
                        // We need an Activity context — grab it from the stored weak ref.
                        Activity activity = replayActivity.get();
                        if (activity == null || activity.isFinishing()) return chain.proceed();

                        int loadLogfileId = activity.getResources().getIdentifier(
                                "menu_load_logfile", "id", "com.qtrun.QuickTest");
if (loadLogfileId == 0 || item.getItemId() != loadLogfileId) {
                            return chain.proceed(); // not our item — proceed normally
                        }

                        // It IS menu_load_logfile and replay bar is visible: bypass guard.

                        boolean subscribed = false;
                        try {
                            subscribed = (boolean) appDMethod.invoke(null);
                        } catch (Throwable t) {
                            Log.w(REPLAY_TAG, "Prong E: Application.d() failed: " + t);
                        }
if (subscribed) {
                            Object kPicker = advActivityKField.get(activity);
                            if (kPicker != null) {
pickerCMethod.invoke(kPicker, new Object[]{new String[]{"*/*"}});
                            } else {
                                Log.w(REPLAY_TAG, "Prong E: advancedActivity.K is null");
                                return chain.proceed(); // fall back
                            }
                        } else {
                            // Not subscribed — show the upgrade dialog via w0.e(activity)
                            try {
                                Class<?> w0Class  = ClassMapping.loadClass("t7.w0", loader);
                                Class<?> advClass = ClassMapping.loadClass("com.qtrun.nsg.AdvancedActivity", loader);
                                Method   eMethod  = ClassMapping.getDeclaredMethod(w0Class, "t7.w0", "e", loader, advClass);
                                eMethod.setAccessible(true);
eMethod.invoke(null, activity);
                            } catch (Throwable t) {
                                Log.w(REPLAY_TAG, "Prong E: w0.e() fallback failed: " + t);
                            }
                        }

                        // Close the drawer (d(false) == closeDrawers())
                        try {
                            String drawerFieldName = ClassMapping.runtimeFieldName("t7.p", "a", loader);
                            Field drawerField = chain.getThisObject().getClass().getDeclaredField(drawerFieldName);
                            drawerField.setAccessible(true);
                            Object drawerLayout = drawerField.get(chain.getThisObject());
                            if (drawerLayout != null) {
drawerDMethod.invoke(drawerLayout, false);
                            }
                        } catch (Throwable t) {
                            Log.w(REPLAY_TAG, "Prong E: closeDrawers failed: " + t);
                        }

                        return true; // consumed — skip original a() body
                    } catch (Throwable t) {
                        Log.w(REPLAY_TAG, "Prong E failed: " + t);
                        return chain.proceed(); // fail safe
                    }
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngE failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong F: t7.e.b(Object) — restore progressBar visibility before second load.
    //
    // t7.e.b() case 4 is the "Loading…" phase. It sets wait_progress_layout VISIBLE
    // and ensures the ProgressBar is indeterminate — but it does NOT call
    // progressBar.setVisibility(VISIBLE). On the first load the bar defaults to
    // VISIBLE, so the spinner shows. After showReplayBar() the spinner is GONE.
    // On a second load it stays GONE throughout the loading phase (no spinner).
    //
    // Fix: before case 4 executes, if replayActive==true restore progressBar to
    // VISIBLE so NSG's loading phase shows the spinner normally.
    // -----------------------------------------------------------------------
    private void installProngF() {
        try {
            Class<?> teClass  = ClassMapping.loadClass("t7.e", loader);
            if (teClass == null) {
                Log.i(REPLAY_TAG, "installProngF: t7.e not available, skipping Prong F");
                return;
            }
            Method   bMethod  = ClassMapping.getDeclaredMethod(teClass, "t7.e", "b", loader, Object.class);
            bMethod.setAccessible(true);
xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    if (replayActive) {
                        try {
                            int discriminator = teDiscriminator.getInt(chain.getThisObject());
                            Object arg = chain.getArg(0);
if (discriminator == 4 && arg != null) {
                                // About to show the "Loading…" bar — restore spinner visibility
                                Activity activity = (Activity) teActivity.get(chain.getThisObject());
                                if (activity != null && !activity.isFinishing()) {
                                    restoreProgressBarVisibility(activity);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(REPLAY_TAG, "Prong F: pre-restore failed: " + t);
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngF failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Prong G: AdvancedActivity.I(int) — hide X button when NSG shows
    // progress messages ("Stopping…", "Saving…", etc.) that reuse the
    // wait_progress_layout.
    //
    // NSG's exit/stop/save paths call I(int) to show progress. The X button
    // added by showReplayBar() remains in the layout, so it must be hidden.
    // showReplayBar() will restore it when a new replay starts.
    // -----------------------------------------------------------------------
    private void installProngG() {
        try {
            Class<?> advClass = ClassMapping.loadClass("com.qtrun.nsg.AdvancedActivity", loader);
            Method iMethod = ClassMapping.getDeclaredMethod(advClass,
                    "com.qtrun.nsg.AdvancedActivity", "I", loader, int.class);
            iMethod.setAccessible(true);
xposed.hook(iMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        Activity activity = (Activity) chain.getThisObject();
                        String pkg = "com.qtrun.QuickTest";
                        int layoutId = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
                        if (layoutId == 0) return result;
                        View layout = activity.findViewById(layoutId);
                        if (layout instanceof ViewGroup) {
                            View xBtn = findViewWithTagRecursive((ViewGroup) layout, "nsgmod_replay_x");
                            if (xBtn != null) {
xBtn.setVisibility(View.GONE);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(REPLAY_TAG, "Prong G failed: " + t);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(REPLAY_TAG, "installProngG failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Restore ProgressBar visibility inside wait_progress_layout before a second load. */
    private void restoreProgressBarVisibility(Activity activity) {
        try {
            String pkg     = "com.qtrun.QuickTest";
            int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
            int progressId = activity.getResources().getIdentifier("wait_progress_bar",    "id", pkg);
            if (layoutId == 0 || progressId == 0) {
                Log.w(REPLAY_TAG, "restoreProgressBarVisibility: view ids not found");
                return;
            }
            View layout = activity.findViewById(layoutId);
            if (layout == null) return;
            View pb = layout.findViewById(progressId);
            if (pb != null) {
                activity.runOnUiThread(() -> pb.setVisibility(View.VISIBLE));
}
        } catch (Throwable t) {
            Log.w(REPLAY_TAG, "restoreProgressBarVisibility failed: " + t);
        }
    }

    /** Hide the replay bar using the stored Activity reference. */
    private void hideReplayBar() {
        Activity activity = replayActivity.get();
        if (activity == null || activity.isFinishing()) {
return;
        }
        Runnable run = () -> {
            try {
                String pkg     = "com.qtrun.QuickTest";
                int layoutId   = activity.getResources().getIdentifier("wait_progress_layout", "id", pkg);
                int progressId = activity.getResources().getIdentifier("wait_progress_bar",    "id", pkg);
                if (layoutId == 0) return;
                View layout = activity.findViewById(layoutId);
                if (layout != null) {
layout.setVisibility(View.GONE);
                    // Restore spinner to VISIBLE so NSG's next "Loading…" phase works normally.
                    if (progressId != 0) {
                        View pb = layout.findViewById(progressId);
                        if (pb != null) pb.setVisibility(View.VISIBLE);
                    }
                }
            } catch (Throwable t) {
                Log.w(REPLAY_TAG, "hideReplayBar failed: " + t);
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
                Log.w(REPLAY_TAG, "showReplayBar: view ids not found");
                return;
            }

            View layout = activity.findViewById(layoutId);
            if (layout == null) { Log.w(REPLAY_TAG, "showReplayBar: layout not found"); return; }

            View     progressBar = layout.findViewById(progressId);
            TextView tipsView    = (TextView) layout.findViewById(tipsId);
            if (progressBar == null || tipsView == null) {
                Log.w(REPLAY_TAG, "showReplayBar: child views not found");
                return;
            }

            // Hide spinner, update label
            progressBar.setVisibility(View.GONE);
            tipsView.setText(filename + " \u2014 Replaying");

            // Add dismiss X button if not already present (tag used as guard)
            if (layout.getTag() == null) {
                try {
                    LinearLayout ll = (LinearLayout) layout;

                    // Strategy: keep the outer layout VERTICAL so the ProgressBar
                    // (layout_width="match_parent") is never disturbed — NSG's own
                    // "Loading…" phase relies on it being a full-width horizontal bar.
                    //
                    // Instead, remove tipsView from the outer layout, wrap it together
                    // with the X button in a new horizontal LinearLayout, and add that
                    // wrapper back as the second child of the outer layout.
                    ll.removeView(tipsView);

                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    row.setLayoutParams(rowLp);

                    // tipsView fills remaining width inside the row
                    LinearLayout.LayoutParams tipsLp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    tipsLp.gravity = Gravity.CENTER_VERTICAL;
                    tipsView.setLayoutParams(tipsLp);
                    row.addView(tipsView);

                    // X dismiss control — TextView avoids Button's forced minHeight
                    TextView xBtn = new TextView(activity);
                    xBtn.setTag("nsgmod_replay_x");
                    xBtn.setText("\u2715");  // ✕
                    xBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    xBtn.setTextColor(Color.WHITE);
                    xBtn.setPadding(20, 0, 4, 0);
                    xBtn.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    xBtn.setLayoutParams(xLp);
                    xBtn.setOnClickListener(v -> {
                         replayBarDismissed = true;
                         layout.setVisibility(View.GONE);
                         // Restore spinner visibility so NSG's next "Loading…" phase works.
                         progressBar.setVisibility(View.VISIBLE);
                     });
                    row.addView(xBtn);

                    ll.addView(row);
                    layout.setTag("nsgmod_replay"); // mark as initialised
} catch (Throwable t) {
                    Log.w(REPLAY_TAG, "showReplayBar: could not add X button: " + t);
                }
            }

            layout.setVisibility(View.VISIBLE);
// Ensure the X button is visible when showing the replay bar
            View xBtnRestore = findViewWithTagRecursive((ViewGroup) layout, "nsgmod_replay_x");
            if (xBtnRestore != null) {
                xBtnRestore.setVisibility(View.VISIBLE);
            }

        } catch (Throwable t) {
            Log.w(REPLAY_TAG, "showReplayBar failed: " + t);
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
                Log.w(REPLAY_TAG, "showExitSpinner: view ids not found");
                return;
            }

            View layout = activity.findViewById(layoutId);
            if (layout == null) { Log.w(REPLAY_TAG, "showExitSpinner: layout not found"); return; }

            View     progressBar = layout.findViewById(progressId);
            TextView tipsView    = (TextView) layout.findViewById(tipsId);
            if (progressBar == null || tipsView == null) {
                Log.w(REPLAY_TAG, "showExitSpinner: child views not found");
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
// Hide the X dismiss button if it was added by showReplayBar()
            View xBtn = findViewWithTagRecursive((ViewGroup) layout, "nsgmod_replay_x");
            if (xBtn != null) {
                xBtn.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            Log.w(REPLAY_TAG, "showExitSpinner failed: " + t);
        }
    }

    /** Recursively searches for a view with the given tag inside a ViewGroup. */
    private View findViewWithTagRecursive(ViewGroup root, Object tag) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (tag.equals(child.getTag())) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewWithTagRecursive((ViewGroup) child, tag);
                if (found != null) return found;
            }
        }
        return null;
    }
}
