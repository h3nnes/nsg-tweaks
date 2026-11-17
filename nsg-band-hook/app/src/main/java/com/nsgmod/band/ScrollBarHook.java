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
 * Hooks NSG v4.8.14 (com.qtrun.QuickTest) to add a draggable vertical SeekBar
 * overlay on the popup's FrameLayout, synced bidirectionally with the ScrollView
 * inside the signaling message detail popup.
 *
 * Hook: rp.onItemClick (after-hook; rp = synthetic click listener of the
 * signaling fragment x80, popup in x80.e0)
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

    private static final String LISTENER_CLASS = "rp";   // synthetic OnItemClickListener
    private static final String FRAGMENT_CLASS = "x80";  // signaling message list fragment

    private Method onItemClickMethod;   // rp.onItemClick(AdapterView, View, int, long)
    private Field  listenerSelector;    // rp.a (int): 0 = other list (tp), 1 = signaling
    private Field  listenerFragment;    // rp.b (id0): owning fragment (x80)
    private Field  fragmentPopup;       // x80.e0 (PopupWindow): detail popup

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
            Class<?> listenerClass = Nsg.load(loader, LISTENER_CLASS);
            Class<?> fragmentClass = Nsg.load(loader, FRAGMENT_CLASS);
            if (listenerClass == null || fragmentClass == null) {
                Log.i(TAG, "ScrollBarHook: rp or x80 missing, skipping");
                return;
            }

            onItemClickMethod = listenerClass.getMethod("onItemClick",
                    android.widget.AdapterView.class,
                    android.view.View.class,
                    int.class,
                    long.class);

            listenerSelector = listenerClass.getDeclaredField("a");
            listenerSelector.setAccessible(true);
            listenerFragment = listenerClass.getDeclaredField("b");
            listenerFragment.setAccessible(true);
            fragmentPopup = fragmentClass.getDeclaredField("e0");
            fragmentPopup.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "ScrollBarHook: initReflection failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        hookOnItemClick();
    }

    // -----------------------------------------------------------------------
    // Hook: rp.onItemClick — after-hook
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
                        Object listener = chain.getThisObject(); // rp instance

                        // Only the signaling fragment (a != 0)
                        int selector = (int) listenerSelector.get(listener);
                        if (selector == 0) return result;

                        // Get the fragment (x80) stored in field b (typed id0)
                        Object fragment = listenerFragment.get(listener);
                        if (fragment == null) return result;

                        // Get the PopupWindow from the fragment
                        PopupWindow popup = (PopupWindow) fragmentPopup.get(fragment);
                        if (popup == null) return result;

                        View contentView = popup.getContentView();
                        if (contentView == null) return result;

                        // contentView is a FrameLayout — find the first ScrollView child
                        final ScrollView scrollView = findScrollView(contentView);
                        if (scrollView == null) {
                            Log.w(TAG, "ScrollBarHook: no ScrollView found in popup content");
                            return result;
                        }

                        // Add SeekBar on top of everything in the FrameLayout
                        final FrameLayout frame = (FrameLayout) contentView;

                        // Guard: only apply once per FrameLayout instance
                        if (SEEKBAR_TAG.equals(frame.getTag(SEEKBAR_TAG.hashCode()))) {
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
                        frame.addView(seekBar);

                        // Layout listener: position SeekBar flush to right edge, spanning
                        // only the ScrollView area (i.e. below the search bar injected by
                        // SignalingSearchHook). We use scrollView.getTop()/getHeight() so
                        // that the SeekBar automatically starts where the ScrollView starts,
                        // regardless of what other hooks have placed above it.
                        // The listener is kept alive (not one-shot) until scrollView has a
                        // non-zero top — which happens after SignalingSearchHook's own layout
                        // listener fires and updates the ScrollView padding, triggering a
                        // second layout pass. At that point we position and remove ourselves.
                        frame.getViewTreeObserver().addOnGlobalLayoutListener(
                                new ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        int parentW = frame.getWidth();
                                        if (parentW == 0) return;

                                        // The SeekBar should span only the area below the
                                        // Share/Copy container (stored as a keyed tag on the
                                        // FrameLayout by SignalingShareHook). Its bottom edge
                                        // gives the true top offset for the SeekBar.
                                        int topOffset = 0;
                                        // Share/Copy container bottom
                                        Object shareCopyTag = frame.getTag(
                                                "nsg_share_copy_container".hashCode());
                                        if (shareCopyTag instanceof View) {
                                            int bottom = ((View) shareCopyTag).getBottom();
                                            if (bottom > 0) topOffset = Math.max(topOffset, bottom);
                                        }
                                        // Search bar height (from SignalingSearchHook, may be 0 if not loaded)
                                        Object searchBarHTag = frame.getTag(
                                                "nsg_search_bar_height".hashCode());
                                        if (searchBarHTag instanceof Integer) {
                                            int sbH = (Integer) searchBarHTag;
                                            if (sbH > 0) topOffset = Math.max(topOffset, sbH);
                                        }

                                        // Extra gap so the SeekBar does not visually touch the buttons above
                                        int extraGapPx = Math.round(8 * frame.getContext().getResources().getDisplayMetrics().density);
                                        topOffset += extraGapPx;

                                        int frameH = frame.getHeight();
                                        if (frameH <= 0) return; // not laid out yet

                                        // Reserve room for the arrow buttons injected at the
                                        // bottom-right by SignalingNavHook so the SeekBar track
                                        // (and its touch area) does not overlap them.
                                        Object navPadTag = frame.getTag(
                                                R.id.nsg_nav_bottom_padding_tag);
                                        if (navPadTag instanceof Integer) {
                                            int navPad = (Integer) navPadTag;
                                            if (navPad > 0) topOffset += navPad;
                                        }

                                        int trackH = frameH - topOffset;
                                        if (trackH <= 0) return;

                                        // Once we have stable dimensions, remove ourselves.
                                        frame.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                                        // Width = trackH so after -90° rotation the track spans
                                        // exactly the area below the Share/Copy container.
                                        ViewGroup.LayoutParams slp = seekBar.getLayoutParams();
                                        slp.width  = trackH;
                                        slp.height = barWidthPx;
                                        seekBar.setLayoutParams(slp);

                                        // Rotation center at (parentW - barWidthPx/2, topOffset + trackH/2)
                                        seekBar.setX((parentW - barWidthPx / 2f) - trackH / 2f);
                                        seekBar.setY(topOffset + trackH / 2f - barWidthPx / 2f);
                                    }
                                });

                        // Mark as done on the FrameLayout
                        frame.setTag(SEEKBAR_TAG.hashCode(), SEEKBAR_TAG);
                    } catch (Exception e) {
                        Log.w(TAG, "ScrollBarHook post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "ScrollBarHook: installed");
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
