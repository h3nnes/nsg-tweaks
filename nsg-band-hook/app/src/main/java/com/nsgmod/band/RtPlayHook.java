package com.nsgmod.band;

import android.content.Context;
import android.graphics.PorterDuff;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Date;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds a "RT-Play" toggle button between the "< 0.5s" and "0.5s >" step buttons
 * in the replay playback bottom bar (k8.f / PlaybackControlsFragment).
 *
 * When active, advances the playback head one data record at a time, waiting the
 * exact wall-clock delta between consecutive record timestamps before each step.
 * This gives time-faithful replay: irregular recording intervals are reproduced
 * faithfully (a 433 ms gap plays back as 433 ms, a 730 ms gap as 730 ms, etc.).
 *
 * Behaviour:
 *   - Tap RT-Play → starts real-time playback; button text changes to "■ Stop"
 *   - Tap again    → stops; button returns to "RT-Play"
 *   - End of log   → stops automatically
 *   - Only active when Workspace has a DataSource loaded (replay mode)
 */
public class RtPlayHook {

    private static final String TAG     = "NSGBandHook_RtPlay";
    /** Resource ID of the forward (+0.5s) button — smali-confirmed: 0x7f09022c */
    private static final int    ID_FWD  = 0x7f09022c;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // ── k8.f reflection ──────────────────────────────────────────────────────
    private Method onCreateViewMethod;  // k8.f.I(LayoutInflater,ViewGroup,Bundle)
    private Field  fragmentYField;      // k8.f.Y  (AdvancedActivity.a callback holder)
    private Field  advActAField;        // AdvancedActivity.a.a  (k8.f back-ref)
    private Field  fragmentZField;      // k8.f.Z  (timestamp TextView)
    private Method fragmentI0Method;    // k8.f.i0(float)  sync seekbar thumb

    // ── Workspace reflection ──────────────────────────────────────────────────
    private Field  wsJ;       // static Workspace Workspace.j  (singleton)
    private Field  wsG;       // long   Workspace.g  (current key)
    private Field  wsH;       // long   Workspace.h  (max key)
    private Field  wsI;       // Date   Workspace.i  (current timestamp)
    private Field  wsC;       // DataSource Workspace.c  (null = no data loaded)
    private Field  wsF;       // com.qtrun.sys.a Workspace.f  (Attribute "Common::Timestamp")
    private Method wsGMethod; // Workspace.g(long, Object)  (seek + notify all subscribers)
    private Method wsIMethod; // Workspace.i()F  (normalised position for seekbar)

    // ── com.qtrun.sys.a (Attribute) reflection ────────────────────────────────
    private Field  attrD;     // com.qtrun.sys.a.d  (the Property object)

    // ── com.qtrun.sys.Property / Property.Iterator reflection ─────────────────
    private Constructor<?> iterCtor;        // Iterator(Property) constructor
    private Method iterForwardLong; // Iterator.forward(long) → seek to first record >= T
    private Method iterEnd;   // Iterator.end()Z
    private Method iterKey;   // Iterator.key()J
    private Method iterValue; // Iterator.value()Object  (cast to Date)
    private Method iterNext;  // Iterator.next()V  (step one record forward)

    // ── ma.a reflection ───────────────────────────────────────────────────────
    private Method maAMMethod; // ma.a.m(Date) → String  (format timestamp label)

    private boolean reflectionReady = false;

    /** Listener that stops + removes the RT-Play button when the toggle is turned off. */
    private SharedPreferences.OnSharedPreferenceChangeListener rtPlayPrefListener = null;
    private SharedPreferences rtPlayPrefs = null;

    /**
     * The currently active RtPlayController; replaced each time a new fragment instance is
     * created. Read by the AdvancedActivity.J() hook to stop playback when the replay bar
     * is removed (lock icon click or any other J() caller).
     */
    private static volatile RtPlayController activeController = null;

    public RtPlayHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    // ── Reflection setup ──────────────────────────────────────────────────────

    private void initReflection() {
        try {
            Class<?> fragmentClass  = loader.loadClass("k8.f");
            Class<?> advActAClass   = loader.loadClass("com.qtrun.nsg.AdvancedActivity$a");
            Class<?> workspaceClass = loader.loadClass("com.qtrun.sys.Workspace");
            Class<?> attrClass      = loader.loadClass("com.qtrun.sys.a");
            Class<?> propClass      = loader.loadClass("com.qtrun.sys.Property");
            Class<?> iterClass      = loader.loadClass("com.qtrun.sys.Property$Iterator");
            Class<?> maAClass       = loader.loadClass("ma.a");
            Class<?> dateClass      = loader.loadClass("java.util.Date");
            Class<?> inflaterClass  = loader.loadClass("android.view.LayoutInflater");
            Class<?> viewGroupClass = loader.loadClass("android.view.ViewGroup");
            Class<?> bundleClass    = loader.loadClass("android.os.Bundle");

            // k8.f
            onCreateViewMethod = fragmentClass.getDeclaredMethod(
                    "I", inflaterClass, viewGroupClass, bundleClass);
            fragmentYField    = fragmentClass.getDeclaredField("Y"); fragmentYField.setAccessible(true);
            fragmentZField    = fragmentClass.getDeclaredField("Z"); fragmentZField.setAccessible(true);
            fragmentI0Method  = fragmentClass.getDeclaredMethod("i0", float.class);
            fragmentI0Method.setAccessible(true);

            // AdvancedActivity.a back-ref to k8.f: runtime "a", JADX mangles to "f3807a"
            Field f3807a;
            try   { f3807a = advActAClass.getDeclaredField("a"); }
            catch (NoSuchFieldException e) { f3807a = advActAClass.getDeclaredField("f3807a"); }
            advActAField = f3807a;
            advActAField.setAccessible(true);

            // Workspace
            wsJ      = workspaceClass.getField("j");
            wsG      = workspaceClass.getDeclaredField("g"); wsG.setAccessible(true);
            wsH      = workspaceClass.getDeclaredField("h"); wsH.setAccessible(true);
            wsI      = workspaceClass.getDeclaredField("i"); wsI.setAccessible(true);
            wsC      = workspaceClass.getDeclaredField("c"); wsC.setAccessible(true);
            wsF      = workspaceClass.getDeclaredField("f"); wsF.setAccessible(true);
            wsGMethod = workspaceClass.getDeclaredMethod("g", long.class, Object.class);
            wsGMethod.setAccessible(true);
            wsIMethod = workspaceClass.getDeclaredMethod("i");
            wsIMethod.setAccessible(true);

            // Attribute → Property
            attrD = attrClass.getDeclaredField("d"); attrD.setAccessible(true);

            // Property / Iterator
            iterCtor        = iterClass.getDeclaredConstructor(propClass); iterCtor.setAccessible(true);
            iterForwardLong = iterClass.getDeclaredMethod("forward", long.class); iterForwardLong.setAccessible(true);
            iterEnd   = iterClass.getDeclaredMethod("end");            iterEnd.setAccessible(true);
            iterKey   = iterClass.getDeclaredMethod("key");            iterKey.setAccessible(true);
            iterValue = iterClass.getDeclaredMethod("value");          iterValue.setAccessible(true);
            iterNext  = iterClass.getDeclaredMethod("next");           iterNext.setAccessible(true);

            // ma.a.m(Date) → String
            maAMMethod = maAClass.getDeclaredMethod("m", dateClass); maAMMethod.setAccessible(true);

            reflectionReady = true;
            Log.i(TAG, "reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "initReflection failed: " + e, e);
        }
    }

    // ── Hook installation ─────────────────────────────────────────────────────

    public void install() {
        if (!reflectionReady) { Log.e(TAG, "install skipped — reflection not ready"); return; }

        xposed.hook(onCreateViewMethod).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    if (result instanceof View) {
                        attachButton(chain.getThisObject(), (View) result);
                    } else {
                        Log.w(TAG, "onCreateView returned non-View: " + result);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "attachButton failed: " + t, t);
                }
                return result;
            }
        });

        Log.i(TAG, "installed on k8.f.I (onCreateView)");

        // Stop RT-Play when the replay bar is removed (lock icon click, load new test, etc.)
        // AdvancedActivity.J() is the single method that tears down the playback bar fragment.
        // Without this, the old stepRunnable keeps running after J() destroys the view, and
        // the next attachButton() creates a second loop → double playback speed.
        try {
            Class<?> advActClass = loader.loadClass("com.qtrun.nsg.AdvancedActivity");
            Method jMethod = advActClass.getDeclaredMethod("J");
            xposed.hook(jMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    RtPlayController ctrl = activeController;
                    if (ctrl != null) {
                        ctrl.stop();
                        activeController = null;
                        Log.i(TAG, "RT-Play stopped — replay bar removed by J()");
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "installed before-hook on AdvancedActivity.J()");
        } catch (Throwable t) {
            Log.w(TAG, "AdvancedActivity.J() hook failed: " + t);
        }
    }

    // ── Button injection ──────────────────────────────────────────────────────

    private void attachButton(Object fragment, View rootView) {
        if (!SettingsToggleHook.rtPlayEnabled()) {
            Log.i(TAG, "RT-Play toggle is off — skipping button attachment");
            return;
        }
        View forwardBtn = rootView.findViewById(ID_FWD);
        if (forwardBtn == null) {
            Log.w(TAG, "playback_forward_500ms (0x7f09022c) not found in view tree");
            return;
        }

        ViewGroup parent = (ViewGroup) forwardBtn.getParent();
        if (!(parent instanceof LinearLayout)) {
            Log.w(TAG, "parent is not LinearLayout: " + parent.getClass().getName());
            return;
        }
        LinearLayout row = (LinearLayout) parent;

        int fwdIndex = -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) == forwardBtn) { fwdIndex = i; break; }
        }
        if (fwdIndex < 0) { Log.w(TAG, "forward btn not found in parent children"); return; }

        // Use the same pattern as SignalingShareHook:
        //   1. Remove forwardBtn from the row.
        //   2. Create a horizontal LinearLayout container sized by forwardBtn's own LayoutParams.
        //   3. Add RT-Play button (MATCH_PARENT height) then forwardBtn (MATCH_PARENT height)
        //      inside the container — both take their height from the container which is
        //      sized by forwardBtn's original LayoutParams (84px / 40dp).
        //   4. Put the container back at the same index in the row.
        //
        // This sidesteps the plain Button default minHeight=100 problem: the container
        // height is authoritative (exact px from forwardBtn's LP), and MATCH_PARENT
        // children inside a fixed-height parent resolve correctly.

        // Detach forwardBtn from row
        row.removeViewAt(fwdIndex);

        Context ctx = forwardBtn.getContext();
        float dp = ctx.getResources().getDisplayMetrics().density;

        // Container takes forwardBtn's original LayoutParams (width/margins) but we
        // need WRAP_CONTENT width to accommodate two buttons.
        ViewGroup.LayoutParams fwdRowLp = forwardBtn.getLayoutParams();
        int containerHeight = (fwdRowLp != null && fwdRowLp.height > 0)
                ? fwdRowLp.height   // 84px = 40dp at this device density
                : (int) (40 * dp);

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                containerHeight);
        // Preserve forwardBtn's original end margin
        if (fwdRowLp instanceof LinearLayout.LayoutParams) {
            containerLp.setMarginEnd(((LinearLayout.LayoutParams) fwdRowLp).getMarginEnd());
        }
        container.setLayoutParams(containerLp);

        // Build the RT-Play button — copy visual style from forwardBtn
        Button btn = new Button(ctx);
        btn.setText("RT-Play");
        btn.setTransformationMethod(null); // disable AllCaps
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ((Button) forwardBtn).getTextSize());
        btn.setTextColor(((Button) forwardBtn).getTextColors());
        btn.setTypeface(((Button) forwardBtn).getTypeface());
        btn.setPadding(
                forwardBtn.getPaddingLeft(),
                forwardBtn.getPaddingTop(),
                forwardBtn.getPaddingRight(),
                forwardBtn.getPaddingBottom());
        btn.setMinimumHeight(forwardBtn.getMinimumHeight());
        // Copy background + tint turquoise (same as SignalingShareHook pattern)
        if (forwardBtn.getBackground() != null
                && forwardBtn.getBackground().getConstantState() != null) {
            Drawable clonedBg = forwardBtn.getBackground().getConstantState().newDrawable().mutate();
            clonedBg.setColorFilter(0xFF00BCD4, PorterDuff.Mode.SRC_IN);
            btn.setBackground(clonedBg);
        } else {
            btn.setBackgroundColor(0xFF00BCD4);
        }
        // Mirror MaterialButton insets from forwardBtn so the pill shape matches
        try {
            Class<?> mbClass = Class.forName("com.google.android.material.button.MaterialButton");
            if (mbClass.isInstance(btn) && mbClass.isInstance(forwardBtn)) {
                Method getInsetTop    = mbClass.getMethod("getInsetTop");
                Method getInsetBottom = mbClass.getMethod("getInsetBottom");
                Method getInsetLeft   = mbClass.getMethod("getInsetLeft");
                Method getInsetRight  = mbClass.getMethod("getInsetRight");
                Method setInsetTop    = mbClass.getMethod("setInsetTop",    int.class);
                Method setInsetBottom = mbClass.getMethod("setInsetBottom", int.class);
                Method setInsetLeft   = mbClass.getMethod("setInsetLeft",   int.class);
                Method setInsetRight  = mbClass.getMethod("setInsetRight",  int.class);
                setInsetTop.invoke(btn,    getInsetTop.invoke(forwardBtn));
                setInsetBottom.invoke(btn, getInsetBottom.invoke(forwardBtn));
                setInsetLeft.invoke(btn,   getInsetLeft.invoke(forwardBtn));
                setInsetRight.invoke(btn,  getInsetRight.invoke(forwardBtn));
            }
        } catch (Throwable ignored) { /* non-Material theme — skip */ }

        // RT-Play button: MATCH_PARENT height (container is fixed), gap before forwardBtn.
        // Match the 8dp marginEnd that "< 0.5s" uses, so the gap on both sides of RT-Play
        // is visually identical.
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        btnLp.setMarginEnd((int) (8 * dp));
        btn.setLayoutParams(btnLp);

        // forwardBtn: MATCH_PARENT height inside container
        LinearLayout.LayoutParams fwdNewLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        forwardBtn.setLayoutParams(fwdNewLp);

        container.addView(btn);
        container.addView(forwardBtn);
        row.addView(container, fwdIndex);

        // Effectively-final captures for use inside lambdas
        final int capturedFwdIndex = fwdIndex;
        final ViewGroup.LayoutParams capturedFwdRowLp = fwdRowLp;

        Log.i(TAG, "RT-Play button inserted at index " + fwdIndex);

        RtPlayController controller = new RtPlayController(
                fragment, btn,
                wsJ, wsG, wsI, wsC, wsF,
                wsGMethod, wsIMethod,
                attrD, iterCtor, iterForwardLong,
                iterEnd, iterKey, iterValue, iterNext,
                fragmentYField, advActAField, fragmentZField,
                fragmentI0Method, maAMMethod);
        btn.setOnClickListener(v -> controller.toggle());
        activeController = controller; // expose to J() hook so it can stop the loop

        // Register a preference listener so toggling off immediately stops + removes the button
        try {
            // Unregister any previous listener from a prior fragment instance
            if (rtPlayPrefListener != null && rtPlayPrefs != null) {
                try {
                    rtPlayPrefs.unregisterOnSharedPreferenceChangeListener(rtPlayPrefListener);
                } catch (Throwable t) {
                    Log.w(TAG, "unregister old RT-Play listener failed: " + t);
                }
                rtPlayPrefListener = null;
                rtPlayPrefs = null;
            }
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            android.app.Application app =
                    (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
            if (app != null) {
                SharedPreferences prefs = app.getSharedPreferences(
                        "com.qtrun.QuickTest_preferences", android.content.Context.MODE_PRIVATE);
                rtPlayPrefs = prefs;
                rtPlayPrefListener = (sharedPreferences, key) -> {
                    if (!SettingsToggleHook.PREF_KEY_RT_PLAY.equals(key)) return;
                    if (!sharedPreferences.getBoolean(key, true)) {
                        // Toggle turned off — stop playback and remove button immediately
                        Log.i(TAG, "RT-Play toggle turned off — stopping and removing button");
                        controller.stop();
                        btn.post(() -> {
                            try {
                                android.view.ViewParent p = btn.getParent();
                                if (p instanceof ViewGroup) {
                                    // btn is inside the container LinearLayout;
                                    // remove the entire container from the row, then
                                    // re-insert forwardBtn at its original index so it
                                    // is not lost along with the container.
                                    android.view.ViewParent containerParent = ((ViewGroup) p).getParent();
                                    if (containerParent instanceof ViewGroup) {
                                        ViewGroup containerVg = (ViewGroup) p;
                                        ViewGroup rowVg = (ViewGroup) containerParent;
                                        // Detach forwardBtn from container before removing container
                                        containerVg.removeView(forwardBtn);
                                        // Restore original LayoutParams and re-insert into row
                                        if (capturedFwdRowLp != null) {
                                            forwardBtn.setLayoutParams(capturedFwdRowLp);
                                        }
                                        rowVg.removeView((View) p);
                                        rowVg.addView(forwardBtn, capturedFwdIndex);
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "failed to remove RT-Play button container: " + t);
                            }
                            // Unregister listener after view removal
                            SharedPreferences.OnSharedPreferenceChangeListener selfRef = rtPlayPrefListener;
                            if (selfRef != null) {
                                try {
                                    prefs.unregisterOnSharedPreferenceChangeListener(selfRef);
                                } catch (Throwable t) {
                                    Log.w(TAG, "unregister listener failed: " + t);
                                }
                                // Only null the field if it still points to us (not a new listener)
                                if (rtPlayPrefListener == selfRef) {
                                    rtPlayPrefListener = null;
                                    rtPlayPrefs = null;
                                }
                            }
                        });
                    }
                };
                prefs.registerOnSharedPreferenceChangeListener(rtPlayPrefListener);
                Log.i(TAG, "RT-Play preference listener registered");
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed to register RT-Play preference listener: " + t);
        }
    }

    // =========================================================================
    // RtPlayController — owns the playback state machine for one fragment instance
    // =========================================================================

    private static final class RtPlayController {

        private static final String TAG    = "NSGBandHook_RtPlay";
        private static final String LABEL_OFF = "RT-Play";
        private static final String LABEL_ON  = "■ Stop";

        private final Object  fragment;
        private final Button  button;
        private final Handler handler = new Handler(Looper.getMainLooper());

        // Workspace reflection
        private final Field  wsJ, wsG, wsI, wsC, wsF;
        private final Method wsGMethod, wsIMethod;

        // Attribute / Property / Iterator reflection
        private final Field  attrD;
        private final Constructor<?> iterCtor;
        private final Method iterForwardLong;
        private final Method iterEnd, iterKey, iterValue, iterNext;

        // Fragment reflection
        private final Field  fragmentYField, advActAField, fragmentZField;
        private final Method fragmentI0Method, maAMMethod;

        private volatile boolean playing = false;

        /** The Runnable that advances one step then re-schedules itself. */
        private final Runnable stepRunnable = new Runnable() {
            @Override
            public void run() {
                if (!playing) return;
                try {
                    Object ws = wsJ.get(null);
                    if (ws == null || wsC.get(ws) == null) {
                        Log.w(TAG, "stepRunnable: no workspace/datasource — stopping");
                        stop(); return;
                    }

                    long curKey = wsG.getLong(ws);

                    // Get the Common::Timestamp Property
                    Object attr = wsF.get(ws);
                    if (attr == null) { Log.w(TAG, "tick: attr null"); stop(); return; }
                    Object prop = attrD.get(attr);
                    if (prop == null) { Log.w(TAG, "tick: prop null"); stop(); return; }

                    // Get current Date from Workspace.i — use its real wall-clock ms
                    // to compute the 1-second boundary. Keys are NOT in milliseconds.
                    Date curDate = (Date) wsI.get(ws);
                    if (curDate == null) { Log.w(TAG, "tick: curDate null"); stop(); return; }
                    long targetMs = curDate.getTime() + 1000L;

                    // Position iterator at current key, then step forward until
                    // we find the first record whose Date >= targetMs
                    Object it = iterCtor.newInstance(prop);
                    iterForwardLong.invoke(it, curKey);  // seek to current position first

                    // step past current record (we're already at it)
                    iterNext.invoke(it);

                    long nextKey = -1L;
                    Date nextDate = null;
                    while (!((Boolean) iterEnd.invoke(it))) {
                        Date d = (Date) iterValue.invoke(it);
                        if (d != null && d.getTime() >= targetMs) {
                            nextKey  = (Long) iterKey.invoke(it);
                            nextDate = d;
                            break;
                        }
                        iterNext.invoke(it);
                    }

                    if (nextKey < 0) {
                        Log.i(TAG, "RT-Play: end of log reached");
                        stop(); return;
                    }

                    // Resolve targetFragment the same way onProgressChanged does
                    Object callbackHolder = fragmentYField.get(fragment);
                    Object targetFragment = (callbackHolder != null)
                            ? advActAField.get(callbackHolder)
                            : fragment;

                    // Seek Workspace to next record (notifies all subscribers)
                    wsGMethod.invoke(ws, nextKey, targetFragment);

                    // Sync seekbar thumb
                    float fraction = (Float) wsIMethod.invoke(ws);
                    if (fraction >= 0f) fragmentI0Method.invoke(fragment, fraction);

                    // Sync timestamp label (k8.f.Z TextView)
                    Object tvSrc = (targetFragment != null) ? targetFragment : fragment;
                    Object tvObj = fragmentZField.get(tvSrc);
                    if (tvObj instanceof TextView) {
                        String label = (String) maAMMethod.invoke(null, nextDate);
                        ((TextView) tvObj).setText(label);
                    }

                    // Schedule next step after fixed 1000ms wall-clock tick
                    handler.postDelayed(this, 1000L);

                } catch (Throwable t) {
                    Log.w(TAG, "stepRunnable error: " + t, t);
                    stop();
                }
            }
        };

        RtPlayController(
                Object fragment, Button button,
                Field wsJ, Field wsG, Field wsI, Field wsC, Field wsF,
                Method wsGMethod, Method wsIMethod,
                Field attrD, Constructor<?> iterCtor, Method iterForwardLong,
                Method iterEnd, Method iterKey, Method iterValue, Method iterNext,
                Field fragmentYField, Field advActAField, Field fragmentZField,
                Method fragmentI0Method, Method maAMMethod) {
            this.fragment         = fragment;
            this.button           = button;
            this.wsJ              = wsJ;
            this.wsG              = wsG;
            this.wsI              = wsI;
            this.wsC              = wsC;
            this.wsF              = wsF;
            this.wsGMethod        = wsGMethod;
            this.wsIMethod        = wsIMethod;
            this.attrD            = attrD;
            this.iterCtor         = iterCtor;
            this.iterForwardLong  = iterForwardLong;
            this.iterEnd          = iterEnd;
            this.iterKey          = iterKey;
            this.iterValue        = iterValue;
            this.iterNext         = iterNext;
            this.fragmentYField   = fragmentYField;
            this.advActAField     = advActAField;
            this.fragmentZField   = fragmentZField;
            this.fragmentI0Method = fragmentI0Method;
            this.maAMMethod       = maAMMethod;
        }

        void toggle() {
            if (playing) stop(); else start();
        }

        private void start() {
            playing = true;
            button.setText(LABEL_ON);
            handler.post(stepRunnable);
            Log.i(TAG, "RT-Play started");
        }

        void stop() {
            playing = false;
            handler.removeCallbacks(stepRunnable);
            // button.setText must run on UI thread; handler is main-looper so this is safe
            button.post(() -> button.setText(LABEL_OFF));
            Log.i(TAG, "RT-Play stopped");
        }
    }
}
