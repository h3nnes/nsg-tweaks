package com.nsgmod.band;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.SeekBar;

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
 * further ABOVE the seekbar (negative dy) reduces the effective scrubbing speed:
 *
 *   Zone 1 (finger < 60 dp above bar):   1:1  — normal speed
 *   Zone 2 (60–150 dp above bar):         1:5  — 5× slower / fine
 *   Zone 3 (> 150 dp above bar):          1:10 — 10× slower / very precise
 *
 * Downward movement (dy > 0) is always Zone 1 (normal).
 *
 * Implementation: hook k8.f.I() (onCreateView) after-hook, attach an
 * OnTouchListener to the SeekBar.  We intercept MOVE and UP events and
 * forward synthetic MotionEvents with a scaled X coordinate to
 * seekBar.onTouchEvent().  This makes AbsSeekBar.trackTouchEvent() compute
 * the right progress value with fromUser=true, so NSG's OnSeekBarChangeListener
 * fires during the drag (live data refresh) and the thumb renders correctly.
 *
 * Why NOT call setProgress() directly:
 *   setProgress(int) hardcodes fromUser=false in ProgressBar.setProgressInternal().
 *   NSG's listener guards with `if (!z8) return` where z8=fromUser, so programmatic
 *   setProgress() calls are ignored — data would not refresh during drag.
 *
 * Why NOT forward raw MOVE events:
 *   The raw finger X ignores our granularity scaling, causing normal-speed scrubbing.
 *
 * Why NOT forward raw UP events:
 *   AbsSeekBar.onTouchEvent(UP) calls trackTouchEvent(event) with the raw finger X,
 *   overwriting our scaled progress and causing the thumb to snap to the wrong position.
 *
 * Solution: synthesize MotionEvents with X set to the scaled-progress pixel position.
 */
public class GranularSeekBarHook {

    private static final String TAG = "NSGBandHook_Granular";

    /** Vertical dp upward before Zone 2 activates (5× slower). */
    private static final float THRESHOLD_1_DP = 60f;

    /** Vertical dp upward before Zone 3 activates (10× slower). */
    private static final float THRESHOLD_2_DP = 150f;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    private Field  seekBarField;
    private Method onCreateViewMethod;
    private boolean reflectionReady = false;

    public GranularSeekBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> fragmentClass  = loader.loadClass("k8.f");
            Class<?> inflaterClass  = loader.loadClass("android.view.LayoutInflater");
            Class<?> viewGroupClass = loader.loadClass("android.view.ViewGroup");
            Class<?> bundleClass    = loader.loadClass("android.os.Bundle");

            seekBarField = fragmentClass.getDeclaredField("X");
            seekBarField.setAccessible(true);

            onCreateViewMethod = fragmentClass.getDeclaredMethod(
                    "I", inflaterClass, viewGroupClass, bundleClass);

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
                    SeekBar seekBar = (SeekBar) seekBarField.get(chain.getThisObject());
                    if (seekBar == null) {
                        Log.w(TAG, "seekBar field is null after onCreateView");
                        return result;
                    }
                    attachGranularListener(seekBar);
                    Log.i(TAG, "GranularSeekBar listener attached");
                } catch (Throwable t) {
                    Log.w(TAG, "failed to attach granular listener: " + t);
                }
                return result;
            }
        });

        Log.i(TAG, "installed on k8.f.I (onCreateView)");
    }

    // -----------------------------------------------------------------------
    // Listener attachment
    // -----------------------------------------------------------------------

    private void attachGranularListener(SeekBar seekBar) {
        float density = seekBar.getContext().getResources().getDisplayMetrics().density;
        float thresh1 = THRESHOLD_1_DP * density;
        float thresh2 = THRESHOLD_2_DP * density;
        seekBar.setOnTouchListener(new GranularTouchListener(seekBar, thresh1, thresh2));
    }

    // -----------------------------------------------------------------------
    // Touch listener (static — no reference to outer class)
    // -----------------------------------------------------------------------

    private static final class GranularTouchListener implements View.OnTouchListener {

        private final SeekBar seekBar;
        private final float   thresh1;  // px — Zone 1→2 boundary (upward)
        private final float   thresh2;  // px — Zone 2→3 boundary (upward)

        /** Raw Y of finger at touch-down, in SeekBar local coordinates. */
        private float touchDownY        = 0f;
        /** Raw X of finger at touch-down. */
        private float touchDownX        = 0f;
        /** SeekBar progress at touch-down. */
        private int   touchDownProgress = 0;
        /** Last scaled progress we sent — used for UP event. */
        private int   lastScaledProgress = 0;

        GranularTouchListener(SeekBar seekBar, float thresh1, float thresh2) {
            this.seekBar = seekBar;
            this.thresh1 = thresh1;
            this.thresh2 = thresh2;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    touchDownY        = event.getY();
                    touchDownX        = event.getX();
                    touchDownProgress = seekBar.getProgress();
                    lastScaledProgress = touchDownProgress;
                    // Forward DOWN as-is so SeekBar enters tracking mode
                    // (sets mIsDragging=true, setPressed(true), thumb animates).
                    seekBar.onTouchEvent(event);
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    float dy = event.getY() - touchDownY; // negative = finger moved UP

                    // Divisor based on upward offset only.
                    float divisor = 1f;
                    if      (dy < -thresh2) divisor = 10f;
                    else if (dy < -thresh1) divisor =  5f;

                    int   max         = seekBar.getMax();
                    float barUsable   = seekBar.getWidth()
                                        - seekBar.getPaddingLeft()
                                        - seekBar.getPaddingRight();
                    if (barUsable <= 0f) return true;

                    // Horizontal delta in progress units, then scale.
                    float dxPx        = event.getX() - touchDownX;
                    float dxProgress  = (dxPx / barUsable) * max;
                    float scaledDelta = dxProgress / divisor;

                    int newProgress = Math.round(touchDownProgress + scaledDelta);
                    newProgress = Math.max(0, Math.min(max, newProgress));
                    lastScaledProgress = newProgress;

                    // Synthesize a MOVE event at the pixel X that corresponds to
                    // newProgress.  AbsSeekBar.trackTouchEvent() will compute
                    // progress = scaledX / barUsable * max = newProgress, and fire
                    // onProgressChanged with fromUser=true so NSG refreshes data live.
                    forwardAtProgress(MotionEvent.ACTION_MOVE, event, newProgress);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // Synthesize UP/CANCEL at the last scaled position so SeekBar
                    // finalises at our value (not the raw finger X which would snap
                    // the thumb to the wrong place).
                    forwardAtProgress(action, event, lastScaledProgress);
                    return true;
                }

                default:
                    return false;
            }
        }

        /**
         * Creates a copy of {@code original} with action {@code action} and the X
         * coordinate set to the pixel position that corresponds to {@code progress}
         * on the SeekBar, then forwards it to seekBar.onTouchEvent().
         *
         * AbsSeekBar.trackTouchEvent() computes:
         *   progress = (x - paddingLeft) / usableWidth * max
         * We invert to get:
         *   x = paddingLeft + (progress / max) * usableWidth
         */
        private void forwardAtProgress(int action, MotionEvent original, int progress) {
            int   max       = seekBar.getMax();
            float usable    = seekBar.getWidth()
                              - seekBar.getPaddingLeft()
                              - seekBar.getPaddingRight();
            float scaledX   = seekBar.getPaddingLeft()
                              + (max > 0 ? ((float) progress / max) * usable : 0f);

            // Keep Y at touch-down Y (within the bar) so AbsSeekBar doesn't interpret
            // it as outside the view.
            float safeY = seekBar.getHeight() / 2f;

            MotionEvent synth = MotionEvent.obtain(
                    original.getDownTime(),
                    original.getEventTime(),
                    action,
                    scaledX,
                    safeY,
                    original.getMetaState());
            try {
                seekBar.onTouchEvent(synth);
            } finally {
                synth.recycle();
            }
        }
    }
}
