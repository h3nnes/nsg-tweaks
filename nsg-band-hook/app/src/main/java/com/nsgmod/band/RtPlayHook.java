package com.nsgmod.band;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.graphics.drawable.DrawableCompat;

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
    private Method propB;     // Property.b(long) → Iterator  (positioned at key ≤ arg)
    private Method iterEnd;   // Iterator.end()Z
    private Method iterKey;   // Iterator.key()J
    private Method iterValue; // Iterator.value()Object  (cast to Date)
    private Method iterNext;  // Iterator.next()V  (advance forward one record)

    // ── ma.a reflection ───────────────────────────────────────────────────────
    private Method maAMMethod; // ma.a.m(Date) → String  (format timestamp label)

    private boolean reflectionReady = false;

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
            propB     = propClass.getDeclaredMethod("b", long.class); propB.setAccessible(true);
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
    }

    // ── Button injection ──────────────────────────────────────────────────────

    private void attachButton(Object fragment, View rootView) {
        // Locate the forward (+0.5s) button by its hardcoded resource ID, then
        // insert our button immediately before it in the same LinearLayout row.
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

        // Find the forward button's position
        int fwdIndex = -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) == forwardBtn) { fwdIndex = i; break; }
        }
        if (fwdIndex < 0) { Log.w(TAG, "forward btn not found in parent children"); return; }

        // Create the RT-Play button.
        //
        // IMPORTANT: new MaterialButton(context) calls ThemeEnforcement.checkMaterialTheme()
        // which throws IllegalArgumentException for contexts that don't carry the
        // isMaterialTheme attribute — even if the Activity visually uses Material styling.
        // The XML inflation path used for forwardBtn bypasses this check; the programmatic
        // constructor path does not.  The exception would be swallowed by the outer
        // catch(Throwable) and the button would silently never appear.
        //
        // Safe fix: AppCompatButton (no theme-enforcement guard) + clone forwardBtn's
        // already-validated background drawable, mutate it, and tint it turquoise.
        Drawable clonedBg = null;
        Drawable fwdBg = forwardBtn.getBackground();
        if (fwdBg != null && fwdBg.getConstantState() != null) {
            clonedBg = fwdBg.getConstantState()
                            .newDrawable(forwardBtn.getContext().getResources())
                            .mutate();
            DrawableCompat.setTint(clonedBg, 0xFF00BCD4); // turquoise
        }

        AppCompatButton btn = new AppCompatButton(forwardBtn.getContext());
        btn.setText("RT-Play");
        btn.setTextColor(Color.WHITE);
        btn.setTextAppearance(android.R.style.TextAppearance_Small);
        btn.setAllCaps(false);
        if (clonedBg != null) {
            btn.setBackground(clonedBg);
        } else {
            // Fallback: solid turquoise — always visible
            btn.setBackgroundColor(0xFF00BCD4);
        }

        float dp = forwardBtn.getContext().getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (40 * dp));
        lp.setMarginEnd((int) (8 * dp));
        btn.setLayoutParams(lp);

        // Build the controller and wire the toggle
        RtPlayController controller = new RtPlayController(
                fragment, btn,
                wsJ, wsG, wsI, wsC, wsF,
                wsGMethod, wsIMethod,
                attrD, propB,
                iterEnd, iterKey, iterValue, iterNext,
                fragmentYField, advActAField, fragmentZField,
                fragmentI0Method, maAMMethod);
        btn.setOnClickListener(v -> controller.toggle());

        // Insert before the forward button
        row.addView(btn, fwdIndex);
        Log.i(TAG, "RT-Play button inserted at index " + fwdIndex);
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
        private final Method propB;
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

                    long curKey   = wsG.getLong(ws);
                    Date curDate  = (Date) wsI.get(ws);
                    if (curDate == null) { stop(); return; }

                    // Get the Common::Timestamp Property
                    Object attr = wsF.get(ws);
                    if (attr == null) { stop(); return; }
                    Object prop = attrD.get(attr);
                    if (prop == null) { stop(); return; }

                    // Create iterator at the current key, then step one record forward
                    Object it = propB.invoke(prop, curKey);
                    iterNext.invoke(it);

                    if ((Boolean) iterEnd.invoke(it)) {
                        // Reached end of log — stop playback
                        Log.i(TAG, "RT-Play: end of log reached");
                        stop(); return;
                    }

                    long nextKey  = (Long)  iterKey.invoke(it);
                    Date nextDate = (Date)  iterValue.invoke(it);

                    // Real-time delta between this record and the next
                    long deltaMs = nextDate.getTime() - curDate.getTime();
                    if (deltaMs < 0)      deltaMs = 0;
                    if (deltaMs > 10_000) deltaMs = 10_000; // safety cap: max 10 s gap

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

                    // Schedule next step after the real-time interval
                    handler.postDelayed(this, Math.max(deltaMs, 16L));

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
                Field attrD, Method propB,
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
            this.propB            = propB;
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

        private void stop() {
            playing = false;
            handler.removeCallbacks(stepRunnable);
            // button.setText must run on UI thread; handler is main-looper so this is safe
            button.post(() -> button.setText(LABEL_OFF));
            Log.i(TAG, "RT-Play stopped");
        }
    }
}
