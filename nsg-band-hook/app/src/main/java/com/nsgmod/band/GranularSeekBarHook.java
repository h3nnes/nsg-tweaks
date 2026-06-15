package com.nsgmod.band;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds vertical-offset granularity control to the playback SeekBar in
 * k8.f (PlaybackControlsFragment).
 *
 * When the user drags the seekbar thumb left/right, moving the finger
 * further ABOVE the seekbar (negative dy) reduces the effective scrubbing speed
 * AND unlocks sub-step key resolution so finer data records are reachable:
 *
 *   Zone 1 (finger < 60 dp above bar):  1:1  — normal (100-step seekbar)
 *   Zone 2 (60–150 dp above bar):        1:5  — 5× finer key resolution
 *   Zone 3 (> 150 dp above bar):         1:10 — 10× finer key resolution
 *
 * Downward movement (dy > 0) is always Zone 1 (normal).
 *
 * ── Why Zone 1 uses synthetic MotionEvents ──────────────────────────────────
 * SeekBar.max defaults to 100, giving 101 discrete positions. For Zone 1 we
 * pass synthetic MOVE/UP events to seekBar.onTouchEvent() so AbsSeekBar's
 * trackTouchEvent() fires with fromUser=true. NSG's onProgressChanged() guards
 * with "if (!fromUser) return", so direct setProgress() would be ignored.
 *
 * ── Why Zones 2/3 bypass onProgressChanged ──────────────────────────────────
 * onProgressChanged converts integer progress (0–100) back to a key via:
 *   key = workspace.h * (progress / 100)
 * This means it can only address 101 distinct positions in the entire recording,
 * regardless of how slowly the seekbar thumb moves. No matter how fine our
 * thumb movement, if two adjacent integers map to the same key, we never reach
 * the records in between.
 *
 * Fix: for Zones 2/3, compute the target key as a float directly from the
 * full key space (workspace.h), apply the zone divisor there, and call
 * Workspace.g(key, fragment) directly. This bypasses the integer quantisation
 * completely, giving up to workspace.h distinct positions (typically thousands).
 *
 * The seekbar thumb is then synced via k8.f.i0(workspace.i()) so the visual
 * position is always consistent with the actual data position.
 */
public class GranularSeekBarHook {

    private static final String TAG = "NSGBandHook_Granular";

    /** Vertical dp upward before Zone 2 activates (5× finer). */
    private static final float THRESHOLD_1_DP = 60f;

    /** Vertical dp upward before Zone 3 activates (10× finer). */
    private static final float THRESHOLD_2_DP = 150f;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // ── k8.f reflection ─────────────────────────────────────────────────────
    private Field  fragmentSeekBarField;   // k8.f.X  (SeekBar)
    private Field  fragmentYField;         // k8.f.Y  (AdvancedActivity.a callback holder)
    private Field  advActAField;           // AdvancedActivity.a.f3807a  (k8.f back-ref)
    private Field  fragmentZField;         // k8.f.Z  (timestamp TextView)
    private Method fragmentI0Method;       // k8.f.i0(float)
    private Method onCreateViewMethod;     // k8.f.I(LayoutInflater,ViewGroup,Bundle)

    // ── Workspace reflection ─────────────────────────────────────────────────
    private Field  wsJ;   // static Workspace Workspace.j  (singleton)
    private Field  wsH;   // long   Workspace.h            (total key range)
    private Field  wsG;   // long   Workspace.g            (current key)
    private Field  wsI;   // Date   Workspace.i            (current Date)
    private Field  wsC;   // DataSource Workspace.c        (null check)
    private Method wsGMethod;  // Workspace.g(long, Object)  (seek+render convenience)
    private Method wsIMethod;  // Workspace.i()              (float fraction)

    // ── ma.a reflection (timestamp formatter) ────────────────────────────────
    private Method maAMMethod;  // ma.a.m(Date) → String

    private boolean reflectionReady = false;

    public GranularSeekBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> fragmentClass   = loader.loadClass("k8.f");
            Class<?> advActAClass    = loader.loadClass("com.qtrun.nsg.AdvancedActivity$a");
            Class<?> workspaceClass  = loader.loadClass("com.qtrun.sys.Workspace");
            Class<?> inflaterClass   = loader.loadClass("android.view.LayoutInflater");
            Class<?> viewGroupClass  = loader.loadClass("android.view.ViewGroup");
            Class<?> bundleClass     = loader.loadClass("android.os.Bundle");
            Class<?> maAClass        = loader.loadClass("ma.a");
            Class<?> dateClass       = loader.loadClass("java.util.Date");

            // k8.f fields/methods
            fragmentSeekBarField = fragmentClass.getDeclaredField("X");
            fragmentSeekBarField.setAccessible(true);
            fragmentYField = fragmentClass.getDeclaredField("Y");
            fragmentYField.setAccessible(true);
            fragmentZField = fragmentClass.getDeclaredField("Z");
            fragmentZField.setAccessible(true);
            fragmentI0Method = fragmentClass.getDeclaredMethod("i0", float.class);
            fragmentI0Method.setAccessible(true);
            onCreateViewMethod = fragmentClass.getDeclaredMethod(
                    "I", inflaterClass, viewGroupClass, bundleClass);

            // AdvancedActivity.a.f3807a back-reference to k8.f
            // JADX renames it f3807a but runtime name is "a" — check both
            Field f3807aCandidate = null;
            try {
                f3807aCandidate = advActAClass.getDeclaredField("a");
            } catch (NoSuchFieldException e) {
                f3807aCandidate = advActAClass.getDeclaredField("f3807a");
            }
            advActAField = f3807aCandidate;
            advActAField.setAccessible(true);

            // Workspace fields/methods
            wsJ = workspaceClass.getField("j");          // static, public
            wsH = workspaceClass.getDeclaredField("h");  wsH.setAccessible(true);
            wsG = workspaceClass.getDeclaredField("g");  wsG.setAccessible(true);
            wsI = workspaceClass.getDeclaredField("i");  wsI.setAccessible(true);
            wsC = workspaceClass.getDeclaredField("c");  wsC.setAccessible(true);
            wsGMethod = workspaceClass.getDeclaredMethod("g", long.class, Object.class);
            wsGMethod.setAccessible(true);
            wsIMethod = workspaceClass.getDeclaredMethod("i");
            wsIMethod.setAccessible(true);

            // ma.a.m(Date) → formatted timestamp String
            maAMMethod = maAClass.getDeclaredMethod("m", dateClass);
            maAMMethod.setAccessible(true);

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

        xposed.hook(onCreateViewMethod).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    Object fragment = chain.getThisObject();
                    SeekBar seekBar = (SeekBar) fragmentSeekBarField.get(fragment);
                    if (seekBar == null) {
                        Log.w(TAG, "seekBar field is null after onCreateView");
                        return result;
                    }
                    float density = seekBar.getContext().getResources()
                                          .getDisplayMetrics().density;
                    float thresh1 = THRESHOLD_1_DP * density;
                    float thresh2 = THRESHOLD_2_DP * density;
                    seekBar.setOnTouchListener(new GranularTouchListener(
                            seekBar, fragment, thresh1, thresh2,
                            wsJ, wsH, wsG, wsI, wsC, wsGMethod, wsIMethod,
                            fragmentYField, advActAField, fragmentZField,
                            fragmentI0Method, maAMMethod));
                    Log.i(TAG, "GranularSeekBar listener attached");
                } catch (Throwable t) {
                    Log.w(TAG, "failed to attach granular listener: " + t);
                }
                return result;
            }
        });

        Log.i(TAG, "installed on k8.f.I (onCreateView)");
    }

    // =========================================================================
    // Touch listener
    // =========================================================================

    private static final class GranularTouchListener implements View.OnTouchListener {

        private final SeekBar seekBar;
        private final Object  fragment;     // k8.f instance
        private final float   thresh1;
        private final float   thresh2;

        // Workspace reflection handles (passed from outer class)
        private final Field  wsJ, wsH, wsG, wsI, wsC;
        private final Method wsGMethod, wsIMethod;

        // Fragment reflection handles
        private final Field  fragmentYField, advActAField, fragmentZField;
        private final Method fragmentI0Method, maAMMethod;

        // ── per-drag state ───────────────────────────────────────────────────
        private float touchDownY         = 0f;
        private float anchorX            = 0f;
        /**
         * Anchor key in Workspace key-space.
         * MUST be double — workspace.h can be a very large long; float only has
         * 24-bit mantissa (~7 decimal digits) which overflows Math.round() to
         * Integer.MAX_VALUE for typical recording key ranges.
         */
        private double anchorKeyD        = 0.0;
        private float lastDivisor        = 1f;
        /** Last progress value sent (for Zone 1 UP synthesise). */
        private int   lastProgressZone1  = 0;
        /** Last key sent (for Zone 2/3 UP direct seek). */
        private long  lastKeyFine        = 0L;
        /** True when the current drag is in Zone 2 or 3. */
        private boolean inFineZone       = false;

        GranularTouchListener(
                SeekBar seekBar, Object fragment, float thresh1, float thresh2,
                Field wsJ, Field wsH, Field wsG, Field wsI, Field wsC,
                Method wsGMethod, Method wsIMethod,
                Field fragmentYField, Field advActAField, Field fragmentZField,
                Method fragmentI0Method, Method maAMMethod) {
            this.seekBar         = seekBar;
            this.fragment        = fragment;
            this.thresh1         = thresh1;
            this.thresh2         = thresh2;
            this.wsJ             = wsJ;
            this.wsH             = wsH;
            this.wsG             = wsG;
            this.wsI             = wsI;
            this.wsC             = wsC;
            this.wsGMethod       = wsGMethod;
            this.wsIMethod       = wsIMethod;
            this.fragmentYField  = fragmentYField;
            this.advActAField    = advActAField;
            this.fragmentZField  = fragmentZField;
            this.fragmentI0Method = fragmentI0Method;
            this.maAMMethod      = maAMMethod;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();

            switch (action) {

                case MotionEvent.ACTION_DOWN: {
                    touchDownY        = event.getY();
                    anchorX           = event.getX();
                    anchorKeyD        = (double) currentKey();
                    lastDivisor       = 1f;
                    inFineZone        = false;
                    lastProgressZone1 = seekBar.getProgress();
                    lastKeyFine       = currentKey();
                    // Forward DOWN so SeekBar enters drag mode (mIsDragging=true).
                    seekBar.onTouchEvent(event);
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getY() - touchDownY; // negative = finger UP

                    float divisor = 1f;
                    if      (dy < -thresh2) divisor = 10f;
                    else if (dy < -thresh1) divisor =  5f;

                    // Zone changed — re-anchor at current position.
                    if (divisor != lastDivisor) {
                        anchorX    = event.getX();
                        // Anchor key = where we actually are right now.
                        anchorKeyD = (double) currentKey();
                        lastDivisor = divisor;
                    }

                    inFineZone = (divisor > 1f);

                    float barUsable = barUsable();
                    if (barUsable <= 0f) return true;

                    float dxPx = event.getX() - anchorX;

                    if (!inFineZone) {
                        // ── Zone 1: synthetic MotionEvent path ──────────────
                        // Keep using seekbar integer steps via onTouchEvent() so
                        // NSG's onProgressChanged fires with fromUser=true.
                        int    max         = seekBar.getMax();
                        long   maxK        = maxKey();
                        double dxProgress  = (maxK > 0)
                                ? ((double) dxPx / barUsable) * max
                                : 0.0;
                        int    newProgress = (int) Math.round(
                                anchorKeyD / (maxK > 0 ? maxK : 1) * max + dxProgress);
                        newProgress = Math.max(0, Math.min(max, newProgress));
                        lastProgressZone1 = newProgress;
                        forwardAtProgress(MotionEvent.ACTION_MOVE, event, newProgress);
                    } else {
                        // ── Zones 2/3: direct Workspace key seek ────────────
                        // Compute key delta in full key-space, then divide.
                        long   maxK      = maxKey();
                        if (maxK <= 0) return true;
                        double dxKey     = ((double) dxPx / barUsable) * maxK;
                        double scaledKey = anchorKeyD + dxKey / divisor;
                        long   newKey    = Math.max(0, Math.min(maxK, Math.round(scaledKey)));
                        lastKeyFine = newKey;
                        seekWorkspace(newKey);
                        updateSeekBarThumb();
                        updateTimestampLabel();
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!inFineZone) {
                        forwardAtProgress(action, event, lastProgressZone1);
                    } else {
                        // Finalise at the last fine key; send synthetic UP so
                        // SeekBar clears mIsDragging / pressed state cleanly.
                        seekWorkspace(lastKeyFine);
                        updateSeekBarThumb();
                        updateTimestampLabel();
                        float safeY = seekBar.getHeight() / 2f;
                        MotionEvent synth = MotionEvent.obtain(
                                event.getDownTime(), event.getEventTime(),
                                action, scaledXForKey(lastKeyFine), safeY,
                                event.getMetaState());
                        try { seekBar.onTouchEvent(synth); }
                        finally { synth.recycle(); }
                    }
                    return true;
                }

                default:
                    return false;
            }
        }

        // ── Workspace helpers ─────────────────────────────────────────────────

        private long currentKey() {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) return 0L;
                return wsG.getLong(ws);
            } catch (Throwable t) { return 0L; }
        }

        private long maxKey() {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) return 0L;
                return wsH.getLong(ws);
            } catch (Throwable t) { return 0L; }
        }

        /** Calls Workspace.g(key, fragment) — seeks and renders. */
        private void seekWorkspace(long key) {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) { Log.w(TAG, "seekWorkspace: ws null"); return; }
                if (wsC.get(ws) == null) { Log.w(TAG, "seekWorkspace: DataSource null"); return; }
                // Resolve the back-reference fragment (k8.f) the same way
                // onProgressChanged does: fragment.Y.a
                Object callbackHolder = fragmentYField.get(fragment);
                Object targetFragment = (callbackHolder != null)
                        ? advActAField.get(callbackHolder)
                        : fragment;
                wsGMethod.invoke(ws, key, targetFragment);
            } catch (Throwable t) {
                Log.w(TAG, "seekWorkspace failed: " + t, t);
            }
        }

        /** Syncs the SeekBar thumb to the current workspace position. */
        private void updateSeekBarThumb() {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) return;
                float fraction = (float) wsIMethod.invoke(ws);
                if (fraction >= 0f) {
                    fragmentI0Method.invoke(fragment, fraction);
                }
            } catch (Throwable t) {
                Log.w(TAG, "updateSeekBarThumb failed: " + t);
            }
        }

        /** Updates the timestamp TextView (k8.f.Z) to match the current workspace time. */
        private void updateTimestampLabel() {
            try {
                Object ws  = wsJ.get(null);
                if (ws == null) return;
                Object date = wsI.get(ws);
                if (date == null) return;
                Object callbackHolder = fragmentYField.get(fragment);
                Object targetFragment = (callbackHolder != null)
                        ? advActAField.get(callbackHolder)
                        : fragment;
                TextView tv = (TextView) fragmentZField.get(targetFragment);
                if (tv == null) return;
                String text = (String) maAMMethod.invoke(null, date);
                tv.setText(text);
            } catch (Throwable t) {
                Log.w(TAG, "updateTimestampLabel failed: " + t);
            }
        }

        // ── SeekBar / MotionEvent helpers ─────────────────────────────────────

        private float barUsable() {
            return seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight();
        }

        /**
         * Returns the pixel X on the seekbar that corresponds to {@code key}
         * in the full workspace key space.
         */
        private float scaledXForKey(long key) {
            long maxK = maxKey();
            float usable = barUsable();
            return seekBar.getPaddingLeft() + (maxK > 0 ? ((float) key / maxK) * usable : 0f);
        }

        /**
         * Synthesises a MotionEvent with X mapped to {@code progress} (seekbar int)
         * and forwards it to seekBar.onTouchEvent() so trackTouchEvent() fires with
         * fromUser=true. Used for Zone 1 only.
         */
        private void forwardAtProgress(int action, MotionEvent original, int progress) {
            int   max     = seekBar.getMax();
            float usable  = barUsable();
            float scaledX = seekBar.getPaddingLeft()
                            + (max > 0 ? ((float) progress / max) * usable : 0f);
            float safeY   = seekBar.getHeight() / 2f;
            MotionEvent synth = MotionEvent.obtain(
                    original.getDownTime(), original.getEventTime(),
                    action, scaledX, safeY, original.getMetaState());
            try { seekBar.onTouchEvent(synth); }
            finally { synth.recycle(); }
        }
    }
}
