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
 * further ABOVE the seekbar reduces the effective scrubbing speed:
 *
 *   Zone 1 (|dy| < THRESHOLD_1):  1:1  — normal speed
 *   Zone 2 (|dy| < THRESHOLD_2):  1:5  — 5× slower
 *   Zone 3 (|dy| >= THRESHOLD_2): 1:10 — 10× slower
 *
 * Only upward movement triggers the finer zones (dy < 0 = finger above bar).
 * Downward movement is treated as Zone 1 (normal speed).
 *
 * Implementation: hook k8.f.I() (onCreateView) after-hook, then attach a
 * custom OnTouchListener to the SeekBar. The listener tracks the touch-down
 * position and progress, scales the horizontal delta, and calls setProgress()
 * manually.  Returning true from onTouch suppresses the SeekBar's built-in
 * thumb drag so we are the only source of progress updates during the drag.
 * The existing OnSeekBarChangeListener fires normally via setProgress().
 */
public class GranularSeekBarHook {

    private static final String TAG = "NSGBandHook_Granular";

    /** Vertical pixels upward before Zone 2 activates (5× slower). */
    private static final float THRESHOLD_1_DP = 60f;

    /** Vertical pixels upward before Zone 3 activates (10× slower). */
    private static final float THRESHOLD_2_DP = 150f;

    private final XposedInterface xposed;
    private final ClassLoader     loader;

    // k8.f field: SeekBar X (runtime name matches JADX name)
    private Field seekBarField;
    // k8.f method I (onCreateView)
    private Method onCreateViewMethod;

    private boolean reflectionReady = false;

    public GranularSeekBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> fragmentClass = loader.loadClass("k8.f");
            Class<?> inflaterClass = loader.loadClass("android.view.LayoutInflater");
            Class<?> viewGroupClass = loader.loadClass("android.view.ViewGroup");
            Class<?> bundleClass   = loader.loadClass("android.os.Bundle");

            seekBarField      = fragmentClass.getDeclaredField("X");
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
    // Touch listener
    // -----------------------------------------------------------------------

    private void attachGranularListener(SeekBar seekBar) {
        // Convert dp thresholds to px once, using the SeekBar's display metrics.
        float density = seekBar.getContext().getResources().getDisplayMetrics().density;
        float thresh1 = THRESHOLD_1_DP * density;
        float thresh2 = THRESHOLD_2_DP * density;

        seekBar.setOnTouchListener(new GranularTouchListener(seekBar, thresh1, thresh2));
    }

    // -----------------------------------------------------------------------
    // Inner class — kept static to avoid capturing GranularSeekBarHook
    // -----------------------------------------------------------------------

    private static final class GranularTouchListener implements View.OnTouchListener {

        private final SeekBar seekBar;
        private final float   thresh1;  // px — Zone 1→2 boundary
        private final float   thresh2;  // px — Zone 2→3 boundary

        /** Raw Y coordinate of finger at touch-down (in seekbar's own coordinate space). */
        private float touchDownY   = 0f;
        /** SeekBar progress at touch-down (integer, 0..max). */
        private int   touchDownProgress = 0;
        /** SeekBar width in px (set once on first DOWN event). */
        private float seekBarWidth = 0f;

        GranularTouchListener(SeekBar seekBar, float thresh1, float thresh2) {
            this.seekBar = seekBar;
            this.thresh1 = thresh1;
            this.thresh2 = thresh2;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                touchDownY        = event.getY();
                touchDownProgress = seekBar.getProgress();
                seekBarWidth      = seekBar.getWidth();
                // Pass the DOWN event through so the SeekBar draws the pressed/thumb state.
                // We do NOT call setProgress here — let the SeekBar handle the initial press.
                seekBar.onTouchEvent(event);
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                float dy = event.getY() - touchDownY;  // negative = finger moved UP
                float dx = event.getX() - getThumbOffset(seekBar); // horizontal travel from thumb centre

                // Determine divisor based on upward vertical offset only.
                float divisor = 1f;
                if (dy < -thresh2) {
                    divisor = 10f;  // Zone 3: very precise
                } else if (dy < -thresh1) {
                    divisor = 5f;   // Zone 2: fine
                }
                // dy >= 0 (finger moved down or stayed) → divisor stays 1 (Zone 1: normal)

                int max        = seekBar.getMax();
                float rawDelta = (dx / seekBarWidth) * max;          // full-speed delta
                float scaledDelta = rawDelta / divisor;              // granularity-scaled
                int newProgress = Math.round(touchDownProgress + scaledDelta);
                newProgress = Math.max(0, Math.min(max, newProgress));

                seekBar.setProgress(newProgress);
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                seekBar.onTouchEvent(event);
                return true;
            }

            return false;
        }

        /**
         * Returns the X coordinate (in the seekbar's local space) of the thumb centre
         * at the current progress. Used as the reference point for horizontal delta so
         * the calculation is anchored to the thumb, not to the touch-down position.
         */
        private float getThumbOffset(SeekBar sb) {
            int max = sb.getMax();
            if (max == 0) return 0f;
            float fraction = (float) sb.getProgress() / max;
            return fraction * sb.getWidth();
        }
    }
}
