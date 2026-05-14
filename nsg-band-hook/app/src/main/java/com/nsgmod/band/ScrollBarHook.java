package com.nsgmod.band;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.4 (com.qtrun.QuickTest) to add a draggable vertical SeekBar
 * overlay on the popup's FrameLayout, synced bidirectionally with the ScrollView
 * inside the signaling message detail popup.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Finds the ScrollView inside the popup's content FrameLayout.
 *   - Adds a rotated SeekBar on top (as last child of FrameLayout).
 *   - Syncs ScrollView ↔ SeekBar bidirectionally with a drag-flag guard.
 *   - Guarded by a tag on the ScrollView so it only applies once per popup instance.
 */
public class ScrollBarHook {

    private static final String TAG = "NSGBandHook";
    private static final String SEEKBAR_TAG = "nsg_seekbar_added";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // --- u7.a reflection ---
    private Method onItemClickMethod;   // u7.a.onItemClick(AdapterView, View, int, long)
    private Field  u7aFieldA;           // u7.a.a (int)  — switch selector
    private Field  u7aFieldB;           // u7.a.b (u6.a) — associated fragment

    // --- u7.f reflection ---
    private Field  u7fPopupField;       // u7.f.<PopupWindow field> — found by type scan

    private boolean reflectionReady = false;

    public ScrollBarHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    // -----------------------------------------------------------------------
    // Reflection
    // -----------------------------------------------------------------------

    private void initReflection() {
        try {
            Class<?> u7aClass = loader.loadClass("u7.a");
            Class<?> u7fClass = loader.loadClass("u7.f");

            // u7.a.onItemClick
            onItemClickMethod = u7aClass.getMethod("onItemClick",
                    android.widget.AdapterView.class,
                    android.view.View.class,
                    int.class,
                    long.class);

            // u7.a fields: dex names are "a" (int) and "b" (u6.a)
            u7aFieldA = u7aClass.getDeclaredField("a");
            u7aFieldA.setAccessible(true);
            u7aFieldB = u7aClass.getDeclaredField("b");
            u7aFieldB.setAccessible(true);

            // u7.f PopupWindow field — find by type scan (actual dex name unknown)
            u7fPopupField = findFieldByType(u7fClass, PopupWindow.class);
            if (u7fPopupField == null) {
                throw new NoSuchFieldException("No PopupWindow field found in u7.f");
            }
            u7fPopupField.setAccessible(true);

            reflectionReady = true;
            Log.i(TAG, "ScrollBarHook: reflection ready");
        } catch (Exception e) {
            Log.e(TAG, "ScrollBarHook: initReflection failed: " + e);
        }
    }

    /**
     * Scans all declared fields of {@code clazz} (and its superclasses) for the
     * first field whose type is exactly {@code targetType}.
     */
    private static Field findFieldByType(Class<?> clazz, Class<?> targetType) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == targetType) {
                    return f;
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        hookOnItemClick();
    }

    // -----------------------------------------------------------------------
    // Hook: u7.a.onItemClick — after-hook
    // Adds a draggable SeekBar overlay synced with the popup's ScrollView.
    // -----------------------------------------------------------------------

    private void hookOnItemClick() {
        if (!reflectionReady) {
            Log.e(TAG, "hookOnItemClick (ScrollBarHook) skipped — reflection not ready");
            return;
        }
        try {
            xposed.hook(onItemClickMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();

                    try {
                        Object listener = chain.getThisObject(); // u7.a instance

                        // Only handle the non-QQ case (a != 0)
                        int selector = (int) u7aFieldA.get(listener);
                        if (selector == 0) return result;

                        // Get the fragment (u7.f) stored in field b
                        Object fragment = u7aFieldB.get(listener); // typed u6.a, actually u7.f
                        if (fragment == null) return result;

                        // Get the PopupWindow from the fragment
                        PopupWindow popup = (PopupWindow) u7fPopupField.get(fragment);
                        if (popup == null) return result;

                        View contentView = popup.getContentView();
                        if (contentView == null) return result;

                        // contentView is a FrameLayout — find the first ScrollView child
                        final ScrollView scrollView = findScrollView(contentView);
                        if (scrollView == null) {
                            Log.w(TAG, "ScrollBarHook: no ScrollView found in popup content");
                            return result;
                        }

                        // Guard: only apply once per ScrollView instance
                        if (SEEKBAR_TAG.equals(scrollView.getTag())) {
                            return result;
                        }

                        android.content.Context context = contentView.getContext();

                        // Build the SeekBar
                        final SeekBar seekBar = new SeekBar(context);

                        // Rotate -90°: max ends up at top, 0 at bottom
                        seekBar.setRotation(-90f);

                        final int barWidthPx = Math.round(24 * context.getResources().getDisplayMetrics().density);

                        // Placeholder layout params — width corrected in OnGlobalLayoutListener
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                barWidthPx,   // placeholder
                                barWidthPx
                        );
                        seekBar.setLayoutParams(lp);

                        final int SEEK_MAX = 10000;
                        seekBar.setMax(SEEK_MAX);
                        // Start at top (scrollY=0 → progress=SEEK_MAX)
                        seekBar.setProgress(SEEK_MAX);

                        // Drag flag to prevent feedback loop
                        final boolean[] userDragging = {false};

                        // Sync ScrollView → SeekBar
                        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
                            if (userDragging[0]) return;
                            int scrollY = scrollView.getScrollY();
                            int maxScroll = getMaxScroll(scrollView);
                            if (maxScroll <= 0) {
                                seekBar.setProgress(SEEK_MAX);
                                return;
                            }
                            // Inverted: scrollY=0 (top) → SEEK_MAX; scrollY=max → 0
                            int progress = SEEK_MAX - (int) ((float) scrollY / maxScroll * SEEK_MAX);
                            seekBar.setProgress(progress);
                        });

                        // Sync SeekBar → ScrollView
                        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                            @Override public void onStartTrackingTouch(SeekBar sb) { userDragging[0] = true; }
                            @Override public void onStopTrackingTouch(SeekBar sb)  { userDragging[0] = false; }

                            @Override
                            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                                if (!fromUser) return;
                                int maxScroll = getMaxScroll(scrollView);
                                // Inverted: progress=SEEK_MAX (top) → scrollY=0
                                int scrollY = (int) ((float) (SEEK_MAX - progress) / SEEK_MAX * maxScroll);
                                scrollView.scrollTo(0, scrollY);
                            }
                        });

                        // Add SeekBar on top of everything in the FrameLayout
                        final FrameLayout frame = (FrameLayout) contentView;
                        frame.addView(seekBar);

                        // One-shot layout listener: position SeekBar flush to right edge
                        frame.getViewTreeObserver().addOnGlobalLayoutListener(
                                new ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        frame.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                        int parentW = frame.getWidth();
                                        int parentH = frame.getHeight();
                                        if (parentW == 0 || parentH == 0) return;

                                        // Content area excludes the FrameLayout's own padding
                                        int padTop    = frame.getPaddingTop();
                                        int padBottom = frame.getPaddingBottom();
                                        int contentH  = parentH - padTop - padBottom;
                                        if (contentH <= 0) contentH = parentH;

                                        // Width = contentH so after -90° rotation the track spans content height
                                        ViewGroup.LayoutParams slp = seekBar.getLayoutParams();
                                        slp.width  = contentH;
                                        slp.height = barWidthPx;
                                        seekBar.setLayoutParams(slp);

                                        // Center of content area is at y = padTop + contentH/2
                                        // Position so rotation center is at (parentW - barWidthPx/2, padTop + contentH/2)
                                        seekBar.setX((parentW - barWidthPx / 2f) - contentH / 2f);
                                        seekBar.setY(padTop + contentH / 2f - barWidthPx / 2f);
                                    }
                                });

                        // Mark as done on the ScrollView
                        scrollView.setTag(SEEKBAR_TAG);

                        Log.d(TAG, "ScrollBarHook: SeekBar overlay added to popup");
                    } catch (Exception e) {
                        Log.w(TAG, "ScrollBarHook post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "ScrollBarHook: hookOnItemClick installed");
        } catch (Exception e) {
            Log.e(TAG, "ScrollBarHook: hookOnItemClick failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the maximum scrollable distance for the given ScrollView.
     */
    private static int getMaxScroll(ScrollView sv) {
        if (sv.getChildCount() == 0) return 0;
        return Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
    }

    /**
     * Searches the direct children of {@code root} for the first ScrollView.
     * If root itself is a ScrollView, returns it.
     */
    private static ScrollView findScrollView(View root) {
        if (root instanceof ScrollView) {
            return (ScrollView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ScrollView) {
                    return (ScrollView) child;
                }
            }
        }
        return null;
    }
}
