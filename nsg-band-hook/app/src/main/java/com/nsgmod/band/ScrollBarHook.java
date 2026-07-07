package com.nsgmod.band;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.7 (com.qtrun.QuickTest) to add a draggable vertical scrollbar
 * overlay on the popup's FrameLayout, synced bidirectionally with the ScrollView
 * inside the signaling message detail popup.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Finds the ScrollView inside the popup's content FrameLayout.
 *   - Adds a custom VerticalScrollBar on top (as last child of FrameLayout).
 *   - Syncs ScrollView ↔ scrollbar bidirectionally with a drag-flag guard.
 *   - Handles NSG's async message text loading via a TextView layout listener.
 *   - Coexists with SignalingShareHook (top-right Share+Copy container) and
 *     SignalingSearchHook (search bar, stores "nsg_search_bar_height" tag).
 *   - Guarded by a tag on the FrameLayout so it only applies once per popup instance.
 */
public class ScrollBarHook {

    private static final String TAG = "NSGBandHook";
    private static final String SCROLLBAR_TAG = "nsg_scrollbar_added";
    private static final String SCROLLBAR_VIEW_TAG = "nsg_scrollbar_view";

    private static final String TV_SCROLL_LISTENER_TAG = "nsg_tv_scroll_listener";
    private static final String TV_LAYOUT_LISTENER_TAG = "nsg_tv_layout_listener";
    private static final String TV_ATTACH_LISTENER_TAG = "nsg_tv_attach_listener";

    /** Resource ID of the message TextView inside the signaling detail popup. */
    private static final int ID_DETAIL_TEXT = 0x7f0900dc;

    // Scrollbar geometry. The view is wider than the visible bar so the whole view is a touch target.
    private static final float BAR_WIDTH_DP = 20f;      // total touch width
    private static final float VISUAL_WIDTH_DP = 8f;    // width of the drawn track/thumb
    private static final float MIN_THUMB_DP = 12f;      // minimum visual thumb length

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

                        Context context = contentView.getContext();
                        final float density = context.getResources().getDisplayMetrics().density;
                        final int barWidthPx = Math.round(BAR_WIDTH_DP * density);

                        // Find the message TextView for reliable content metrics and TextView-scrolling fallback.
                        final TextView messageTextView = (TextView) frame.findViewById(ID_DETAIL_TEXT);

                        final VerticalScrollBar scrollBar;
                        boolean alreadyAdded = SCROLLBAR_TAG.equals(frame.getTag(SCROLLBAR_TAG.hashCode()));
                        if (alreadyAdded) {
                            Object viewTag = frame.getTag(SCROLLBAR_VIEW_TAG.hashCode());
                            scrollBar = (viewTag instanceof VerticalScrollBar) ? (VerticalScrollBar) viewTag : null;
                        } else {
                            // Hide the stock ScrollView scrollbar so only the custom draggable one is visible.
                            // SCROLLBARS_INSIDE_OVERLAY prevents the ScrollView from reserving gutter space.
                            scrollView.setVerticalScrollBarEnabled(false);
                            scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

                            // Build the custom vertical scrollbar
                            scrollBar = new VerticalScrollBar(context, scrollView, messageTextView, density, barWidthPx);

                            // Default layout params — final width/height/position set after layout.
                            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                                    barWidthPx,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            );
                            scrollBar.setLayoutParams(lp);

                            // Sync ScrollView → scrollbar (except while the user is dragging the scrollbar)
                            scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
                                if (scrollBar.isDragging()) return;
                                syncScrollBarFromScroll(scrollBar, scrollView);
                            });

                            frame.addView(scrollBar);
                            scrollBar.bringToFront();

                            // Recompute scrollbar state when the ScrollView child (the message TextView)
                            // changes height, e.g. when NSG asynchronously loads the message text.
                            View scrollChild = scrollView.getChildAt(0);
                            if (scrollChild != null) {
                                scrollChild.getViewTreeObserver().addOnGlobalLayoutListener(
                                        new ViewTreeObserver.OnGlobalLayoutListener() {
                                            @Override
                                            public void onGlobalLayout() {
                                                syncScrollBarFromScroll(scrollBar, scrollView);
                                            }
                                        });
                            }

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
                            frame.setTag(SCROLLBAR_VIEW_TAG.hashCode(), scrollBar);
                        }

                        // The popup content view is reused across multiple onItemClick calls, and its
                        // ViewTreeObserver is recreated when the popup is (re)shown. Re-attach the
                        // message TextView scroll listeners every hook invocation so scrolling keeps
                        // syncing even after the popup is dismissed and reshown.
                        if (scrollBar != null && messageTextView != null) {
                            attachTextViewScrollListeners(messageTextView, scrollBar, scrollView);
                        }

                        if (alreadyAdded) {
                            return result;
                        }
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
    // TextView scroll listener management
    // -----------------------------------------------------------------------

    /**
     * Attaches (or re-attaches) scroll and layout listeners to the message TextView.
     * The listeners are stored as tags so they can be managed across popup re-shows:
     * the ViewTreeObserver is recreated when the popup content view is detached/attached,
     * so the scroll listener is added once per attach cycle and its tag is cleared on detach.
     */
    private static void attachTextViewScrollListeners(final TextView messageTextView,
                                                      final VerticalScrollBar scrollBar,
                                                      final ScrollView scrollView) {
        // ViewTreeObserver.OnScrollChangedListener is more reliable than
        // setOnScrollChangeListener on some builds, and it works on the TextView's own observer.
        if (messageTextView.getTag(TV_SCROLL_LISTENER_TAG.hashCode()) == null) {
            ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
                if (scrollBar.isDragging()) return;
                syncScrollBarFromScroll(scrollBar, scrollView);
            };
            messageTextView.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            messageTextView.setTag(TV_SCROLL_LISTENER_TAG.hashCode(), scrollListener);
        }

        // API 23+ listener as a fallback / redundancy.
        messageTextView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollBar.isDragging()) return;
            syncScrollBarFromScroll(scrollBar, scrollView);
        });

        // Recompute metrics after layout/content changes. Post a runnable so the TextView's
        // Layout is fully populated before we read content height / max scroll.
        Object oldLayout = messageTextView.getTag(TV_LAYOUT_LISTENER_TAG.hashCode());
        if (oldLayout instanceof ViewTreeObserver.OnGlobalLayoutListener) {
            messageTextView.getViewTreeObserver().removeOnGlobalLayoutListener(
                    (ViewTreeObserver.OnGlobalLayoutListener) oldLayout);
        }
        ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                syncScrollBarFromScroll(scrollBar, scrollView);
                messageTextView.post(() -> syncScrollBarFromScroll(scrollBar, scrollView));
            }
        };
        messageTextView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        messageTextView.setTag(TV_LAYOUT_LISTENER_TAG.hashCode(), layoutListener);

        // Clean up tags (and the global layout listener) when the TextView is detached so the
        // scroll listener is re-added the next time the popup is shown.
        if (messageTextView.getTag(TV_ATTACH_LISTENER_TAG.hashCode()) == null) {
            View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) { }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    Object l = v.getTag(TV_LAYOUT_LISTENER_TAG.hashCode());
                    if (l instanceof ViewTreeObserver.OnGlobalLayoutListener) {
                        v.getViewTreeObserver().removeOnGlobalLayoutListener(
                                (ViewTreeObserver.OnGlobalLayoutListener) l);
                    }
                    v.setTag(TV_SCROLL_LISTENER_TAG.hashCode(), null);
                    v.setTag(TV_LAYOUT_LISTENER_TAG.hashCode(), null);
                    v.removeOnAttachStateChangeListener(this);
                    v.setTag(TV_ATTACH_LISTENER_TAG.hashCode(), null);
                }
            };
            messageTextView.addOnAttachStateChangeListener(attachListener);
            messageTextView.setTag(TV_ATTACH_LISTENER_TAG.hashCode(), attachListener);
        }
    }

    // -----------------------------------------------------------------------
    // VerticalScrollBar
    // -----------------------------------------------------------------------

    /**
     * A custom vertical scrollbar drawn directly on a Canvas. The track and thumb
     * are rendered as rounded rectangles, giving full control over positioning and
     * avoiding the measurement/rotation quirks of a vertical SeekBar.
     *
     * The view is wider than the visible bar so the whole view acts as a touch target,
     * while the visible track/thumb is drawn centered with a narrower width.
     */
    private static class VerticalScrollBar extends View {

        private final ScrollView scrollView;
        private final TextView messageTextView;
        private final int barWidthPx;       // total touch width of the view
        private final int visualWidthPx;    // width of the actually drawn track/thumb
        private final int minThumbLengthPx;
        private int thumbLengthPx = 0;
        private boolean dragging = false;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF thumbRect = new RectF();

        public VerticalScrollBar(Context context, ScrollView scrollView, TextView messageTextView,
                                 float density, int barWidthPx) {
            super(context);
            this.scrollView = scrollView;
            this.messageTextView = messageTextView;
            this.barWidthPx = barWidthPx;
            this.visualWidthPx = Math.round(VISUAL_WIDTH_DP * density);
            this.minThumbLengthPx = Math.round(MIN_THUMB_DP * density);
            trackPaint.setColor(0x30FFFFFF);
            thumbPaint.setColor(0xA0FFFFFF);
            setClickable(true);
            setFocusable(false);
        }

        public boolean isDragging() {
            return dragging;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Center the visible track inside the wider touch view.
            float left = (w - visualWidthPx) / 2f;
            trackRect.set(left, 0, left + visualWidthPx, h);
            syncThumbRect();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRoundRect(trackRect, visualWidthPx / 2f, visualWidthPx / 2f, trackPaint);
            canvas.drawRoundRect(thumbRect, visualWidthPx / 2f, visualWidthPx / 2f, thumbPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    requestParentDisallowIntercept(true);
                    updateScrollFromTouch(event.getY());
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    requestParentDisallowIntercept(true);
                    updateScrollFromTouch(event.getY());
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        updateScrollFromTouch(event.getY());
                    }
                    dragging = false;
                    requestParentDisallowIntercept(false);
                    return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateScrollFromTouch(float y) {
            if (scrollView == null) return;
            ScrollMetrics m = computeMetrics();
            if (m.maxScroll <= 0) return;

            int trackH = getHeight();
            if (trackH <= 0) return;
            recomputeThumbLength(m);
            if (thumbLengthPx >= trackH) return;

            float relativeY = y - thumbLengthPx / 2f;
            relativeY = Math.max(0, Math.min(relativeY, trackH - thumbLengthPx));
            int scrollY = Math.round((relativeY / (trackH - thumbLengthPx)) * m.maxScroll);
            scrollY = Math.max(0, Math.min(scrollY, m.maxScroll));
            scrollActiveView(m, scrollY);
            updateThumbRect(m, scrollY);
        }

        private void scrollActiveView(ScrollMetrics m, int scrollY) {
            if (m.useTextView && messageTextView != null) {
                messageTextView.scrollTo(0, scrollY);
            } else {
                scrollView.scrollTo(0, scrollY);
            }
        }

        private void recomputeThumbLength() {
            recomputeThumbLength(computeMetrics());
        }

        private void recomputeThumbLength(ScrollMetrics m) {
            int trackH = getHeight();
            if (trackH <= 0 || !m.scrollable) {
                thumbLengthPx = minThumbLengthPx;
                return;
            }
            int length = Math.round((float) m.viewportH / m.contentH * trackH);
            thumbLengthPx = Math.max(minThumbLengthPx, Math.min(length, trackH));
        }

        private void syncThumbRect() {
            syncThumbRect(computeMetrics());
        }

        private void syncThumbRect(ScrollMetrics m) {
            if (!m.scrollable) return;
            recomputeThumbLength(m);
            int trackH = getHeight();
            if (trackH <= thumbLengthPx) return;
            updateThumbRect(m, m.scrollY);
        }

        private void updateThumbRect(ScrollMetrics m, int scrollY) {
            int trackH = getHeight();
            if (trackH <= thumbLengthPx || m.maxScroll <= 0) return;
            scrollY = Math.max(0, Math.min(scrollY, m.maxScroll));
            float thumbTop = (float) scrollY / m.maxScroll * (trackH - thumbLengthPx);
            float left = (getWidth() - visualWidthPx) / 2f;
            thumbRect.set(left, thumbTop, left + visualWidthPx, thumbTop + thumbLengthPx);
            invalidate();
        }

        /**
         * Computes the current scroll metrics, choosing between the ScrollView and the message
         * TextView as the source of truth. NSG sets ScrollingMovementMethod on the detail TextView
         * and scrolls that view directly, leaving ScrollView.getScrollY() at zero, so the TextView
         * is preferred whenever it is scrollable.
         */
        private ScrollMetrics computeMetrics() {
            ScrollMetrics m = new ScrollMetrics();

            int svContentH = 0;
            int svViewportH = 0;
            if (scrollView.getChildCount() > 0) {
                svContentH = scrollView.getChildAt(0).getHeight();
                svViewportH = scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
            }
            int svMaxScroll = Math.max(0, svContentH - svViewportH);
            int svScrollY = scrollView.getScrollY();

            int tvContentH = 0;
            int tvViewportH = 0;
            int tvMaxScroll = 0;
            int tvScrollY = 0;
            if (messageTextView != null) {
                tvViewportH = messageTextView.getHeight();
                tvContentH = computeTextViewContentHeight();
                tvMaxScroll = Math.max(0, tvContentH - tvViewportH);
                tvScrollY = messageTextView.getScrollY();
            }

            // Prefer the message TextView as the scroll source when it can scroll via its
            // MovementMethod. NSG uses ScrollingMovementMethod, so the TextView handles scrolling
            // internally and the parent ScrollView stays at scrollY == 0.
            boolean textViewActive = messageTextView != null
                    && messageTextView.getMovementMethod() instanceof ScrollingMovementMethod
                    && tvMaxScroll > 0;
            if (textViewActive) {
                m.useTextView = true;
                m.scrollY = tvScrollY;
                m.maxScroll = tvMaxScroll;
                m.contentH = tvContentH;
                m.viewportH = tvViewportH;
            } else if (svMaxScroll > 0) {
                m.useTextView = false;
                m.scrollY = svScrollY;
                m.maxScroll = svMaxScroll;
                m.contentH = svContentH;
                m.viewportH = svViewportH;
            } else {
                m.useTextView = false;
            }

            m.scrollable = m.maxScroll > 0 && m.viewportH > 0 && m.contentH > 0;
            return m;
        }

        private int computeTextViewContentHeight() {
            if (messageTextView == null) return 0;
            Layout layout = messageTextView.getLayout();
            if (layout != null) {
                return layout.getHeight();
            }
            // Fallback estimate before the first layout pass builds the Layout.
            int lineCount = messageTextView.getLineCount();
            if (lineCount > 0) {
                return messageTextView.getLineHeight() * lineCount
                        + messageTextView.getCompoundPaddingTop()
                        + messageTextView.getCompoundPaddingBottom();
            }
            return 0;
        }

        private void requestParentDisallowIntercept(boolean disallow) {
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
            }
        }

        /** Simple container for the active scroll-source dimensions and position. */
        private static class ScrollMetrics {
            int scrollY;
            int maxScroll;
            int contentH;
            int viewportH;
            boolean useTextView;
            boolean scrollable;
        }
    }

    // -----------------------------------------------------------------------
    // Sync
    // -----------------------------------------------------------------------

    /**
     * Reads the current scroll position from the active scroll source and updates the custom
     * scrollbar thumb. Hides the scrollbar when the content is not scrollable.
     */
    private static void syncScrollBarFromScroll(VerticalScrollBar scrollBar, ScrollView scrollView) {
        VerticalScrollBar.ScrollMetrics m = scrollBar.computeMetrics();
        if (!m.scrollable) {
            scrollBar.setVisibility(View.INVISIBLE);
            return;
        }
        scrollBar.setVisibility(View.VISIBLE);
        scrollBar.syncThumbRect(m);
    }

    // -----------------------------------------------------------------------
    // Positioning helper
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

        // Make sure the scrollbar reflects the current scroll state / visibility.
        ScrollView scrollView = scrollBar.scrollView;
        if (scrollView != null) {
            syncScrollBarFromScroll(scrollBar, scrollView);
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
