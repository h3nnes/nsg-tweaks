package com.nsgmod.band;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.4 (com.qtrun.QuickTest) to add a draggable vertical scrollbar
 * overlay on the popup's FrameLayout, synced bidirectionally with the ScrollView
 * inside the signaling message detail popup.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Finds the ScrollView inside the popup's content FrameLayout.
 *   - Adds a custom VerticalScrollBar view on top (as last child of FrameLayout).
 *   - Syncs ScrollView ↔ scrollbar bidirectionally with a drag-flag guard.
 *   - Guarded by a tag on the FrameLayout so it only applies once per popup instance.
 */
public class ScrollBarHook {

    private static final String TAG = "NSGBandHook";
    private static final String SCROLLBAR_TAG = "nsg_scrollbar_added";

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
    // Adds a draggable vertical scrollbar overlay synced with the popup's ScrollView.
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

                        // Add scrollbar on top of everything in the FrameLayout
                        final FrameLayout frame = (FrameLayout) contentView;

                        // Guard: only apply once per FrameLayout instance
                        if (SCROLLBAR_TAG.equals(frame.getTag(SCROLLBAR_TAG.hashCode()))) {
                            return result;
                        }

                        // Hide the stock ScrollView scrollbar so only the custom draggable one is visible.
                        // SCROLLBARS_INSIDE_OVERLAY prevents the ScrollView from reserving gutter space.
                        scrollView.setVerticalScrollBarEnabled(false);
                        scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

                        Context context = contentView.getContext();

                        // Build the custom vertical scrollbar
                        final VerticalScrollBar scrollBar = new VerticalScrollBar(context, scrollView);

                        final int barWidthPx = Math.round(8 * context.getResources().getDisplayMetrics().density);

                        // Placeholder layout params — final width/height/position set after layout.
                        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                barWidthPx,
                                FrameLayout.LayoutParams.MATCH_PARENT
                        );
                        scrollBar.setLayoutParams(lp);
                        // Keep it hidden until positionScrollBar() places and sizes it correctly.
                        scrollBar.setVisibility(View.INVISIBLE);

                        // Sync ScrollView → scrollbar
                        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
                            if (scrollBar.isDragging()) return;
                            scrollBar.updateFromScroll();
                        });

                        frame.addView(scrollBar);
                        scrollBar.bringToFront();

                        // Recompute thumb metrics when the ScrollView child (the message TextView)
                        // changes height, e.g. when NSG asynchronously loads the message text.
                        View scrollChild = scrollView.getChildAt(0);
                        if (scrollChild != null) {
                            scrollChild.getViewTreeObserver().addOnGlobalLayoutListener(
                                    new ViewTreeObserver.OnGlobalLayoutListener() {
                                        @Override
                                        public void onGlobalLayout() {
                                            scrollBar.updateFromScroll();
                                        }
                                    });
                        }

                        final float density = context.getResources().getDisplayMetrics().density;

                        // Position once after the current hook chain has finished adding sibling
                        // views (Share/Copy container, search bar). The persistent listener below
                        // then keeps it in sync with any later layout changes.
                        frame.post(() -> positionScrollBar(frame, scrollBar, barWidthPx, density));

                        // Keep the scrollbar positioned correctly across layout changes (search bar
                        // height settling, rotation, etc.). The helper is idempotent.
                        frame.getViewTreeObserver().addOnGlobalLayoutListener(
                                new ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        positionScrollBar(frame, scrollBar, barWidthPx, density);
                                    }
                                });

                        // Mark as done on the FrameLayout
                        frame.setTag(SCROLLBAR_TAG.hashCode(), SCROLLBAR_TAG);
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
    // VerticalScrollBar
    // -----------------------------------------------------------------------

    /**
     * A lightweight vertical scrollbar view that draws its own track and thumb,
     * sized proportionally to the viewport/content ratio of a linked ScrollView,
     * and supports direct touch dragging to scroll the linked view.
     */
    private static class VerticalScrollBar extends View {

        private static final int THUMB_COLOR = 0xA0FFFFFF;
        private static final int TRACK_COLOR = 0x30FFFFFF;

        private final ScrollView scrollView;
        private final Paint thumbPaint;
        private final Paint trackPaint;
        private final RectF thumbRect = new RectF();

        private final float minThumbHeight;
        private final float thumbRadius;

        private boolean dragging = false;
        private float dragStartY;
        private float dragStartThumbTop;

        private float thumbTop;
        private float thumbHeight;

        public VerticalScrollBar(Context context, ScrollView scrollView) {
            super(context);
            this.scrollView = scrollView;

            // Make sure the view actually receives touch events and keeps them
            // instead of letting the parent ScrollView/FrameLayout intercept them.
            setClickable(true);
            setFocusable(false);

            float density = context.getResources().getDisplayMetrics().density;
            minThumbHeight = 32 * density;
            thumbRadius = 8 * density;

            thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            thumbPaint.setColor(THUMB_COLOR);
            thumbPaint.setStyle(Paint.Style.FILL);

            trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            trackPaint.setColor(TRACK_COLOR);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(2 * density);
        }

        public boolean isDragging() {
            return dragging;
        }

        /**
         * Re-reads the linked ScrollView metrics and updates the thumb geometry.
         * Called from the ScrollView's scroll-changed listener (except while dragging).
         */
        public void updateFromScroll() {
            updateThumbMetrics();
            invalidate();
        }

        private void updateThumbMetrics() {
            int viewportH = scrollView.getHeight();
            int contentH = scrollView.getChildCount() > 0 ? scrollView.getChildAt(0).getHeight() : 0;
            int maxScroll = Math.max(0, contentH - viewportH);

            int trackH = getHeight();
            if (trackH <= 0) {
                setVisibility(INVISIBLE);
                return;
            }

            if (maxScroll <= 0 || viewportH <= 0) {
                thumbHeight = 0;
                thumbTop = 0;
                setVisibility(INVISIBLE);
                return;
            }

            setVisibility(VISIBLE);

            thumbHeight = Math.max(minThumbHeight, (float) viewportH * viewportH / contentH);
            if (thumbHeight > trackH) {
                thumbHeight = trackH;
            }

            int scrollY = scrollView.getScrollY();
            float available = trackH - thumbHeight;
            thumbTop = (maxScroll > 0) ? (float) scrollY / maxScroll * available : 0;
            thumbTop = Math.max(0, Math.min(thumbTop, available));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateThumbMetrics();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    dragging = true;
                    dragStartY = event.getY();
                    dragStartThumbTop = thumbTop;
                    // Keep the parent from stealing drag events (e.g. ScrollView scrolling).
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;

                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }

                    int viewportH = scrollView.getHeight();
                    int contentH = scrollView.getChildCount() > 0 ? scrollView.getChildAt(0).getHeight() : 0;
                    int maxScroll = Math.max(0, contentH - viewportH);
                    int trackH = getHeight();

                    float available = trackH - thumbHeight;
                    if (available <= 0 || maxScroll <= 0) return true;

                    float deltaY = event.getY() - dragStartY;
                    float newThumbTop = dragStartThumbTop + deltaY;
                    newThumbTop = Math.max(0, Math.min(newThumbTop, available));

                    int targetScroll = Math.round((newThumbTop / available) * maxScroll);
                    targetScroll = Math.max(0, Math.min(targetScroll, maxScroll));

                    scrollView.scrollTo(0, targetScroll);
                    thumbTop = newThumbTop;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    // Let the next scroll-changed listener sync the final thumb position
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            int trackH = getHeight();
            if (width <= 0 || trackH <= 0 || thumbHeight <= 0) return;

            // Draw subtle vertical track line down the center
            float centerX = width / 2f;
            canvas.drawLine(centerX, 0, centerX, trackH, trackPaint);

            // Draw rounded thumb
            thumbRect.set(0, thumbTop, width, thumbTop + thumbHeight);
            canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Computes and applies the custom scrollbar's position/size inside the popup FrameLayout.
     * Accounts for the FrameLayout's padding and for any top UI injected by sibling hooks
     * (Share/Copy container, search bar). Returns true when the scrollbar was successfully
     * positioned with non-zero dimensions.
     */
    private static boolean positionScrollBar(FrameLayout frame, VerticalScrollBar scrollBar,
                                               int barWidthPx, float density) {
        int parentW = frame.getWidth();
        int frameH = frame.getHeight();
        if (parentW == 0 || frameH == 0) return false;

        int padTop = frame.getPaddingTop();
        int padBottom = frame.getPaddingBottom();
        int padRight = frame.getPaddingRight();

        int topOffset = 0;
        boolean hasOffset = false;

        // Share/Copy container bottom (inside the padded content area)
        Object shareCopyTag = frame.getTag("nsg_share_copy_container".hashCode());
        if (shareCopyTag instanceof View) {
            View container = (View) shareCopyTag;
            int bottom = container.getBottom();
            if (bottom > 0) {
                topOffset = Math.max(topOffset, bottom);
                hasOffset = true;
            }
        }

        // Search bar height
        Object searchBarHTag = frame.getTag("nsg_search_bar_height".hashCode());
        if (searchBarHTag instanceof Integer) {
            int sbH = (Integer) searchBarHTag;
            if (sbH > 0) {
                topOffset = Math.max(topOffset, sbH);
                hasOffset = true;
            }
        }

        // Extra gap so the scrollbar does not visually touch the buttons above
        int extraGapPx = Math.round(8 * density);
        int effectiveTop = padTop + topOffset + extraGapPx;

        int trackH = frameH - padBottom - effectiveTop;
        if (trackH <= 0) {
            if (hasOffset) {
                scrollBar.setVisibility(View.INVISIBLE);
            }
            return false;
        }

        ViewGroup.LayoutParams slp = scrollBar.getLayoutParams();
        boolean layoutParamsChanged = false;
        if (slp == null) {
            slp = new FrameLayout.LayoutParams(barWidthPx, trackH);
            layoutParamsChanged = true;
        } else if (slp.width != barWidthPx || slp.height != trackH) {
            slp.width = barWidthPx;
            slp.height = trackH;
            layoutParamsChanged = true;
        }
        if (layoutParamsChanged) {
            scrollBar.setLayoutParams(slp);
        }

        // Place at the right edge of the ScrollView content area, inside the FrameLayout padding.
        scrollBar.setX(parentW - padRight - barWidthPx);
        scrollBar.setY(effectiveTop);

        // Keep the scrollbar as the topmost child so it is visible and receives touches first.
        // Only call bringToFront() when it is not already last, otherwise every layout pass
        // would re-trigger a layout and cause an endless loop with the OnGlobalLayoutListener.
        int childCount = frame.getChildCount();
        if (childCount == 0 || frame.getChildAt(childCount - 1) != scrollBar) {
            scrollBar.bringToFront();
        }

        scrollBar.updateFromScroll();

        return true;
    }

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
