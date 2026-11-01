package com.nsgmod.band;

import android.graphics.Color;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.nsgmod.band.R;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Adds "previous message" / "next message" buttons to the signaling message
 * detail popup (bottom-right), so the user can step through messages without
 * dismissing the popup and tapping the list row again.
 *
 * Hook: u7.a.onItemClick (after-hook) — the same entry point used by
 * SignalingShareHook and SignalingSearchHook.
 *
 * Why this is filter-safe:
 *   The clicked AdapterView is passed as argument 0 of onItemClick, and its
 *   adapter only contains the *visible* rows (NSG rebuilds the adapter's key
 *   list whenever the signaling filter changes). Navigation therefore operates
 *   on the filtered list, not the full log.
 *
 * Why navigation tracks the message key, not the row index:
 *   NSG swaps the adapter when data for another module index arrives and
 *   rebuilds the visible-key list when the filter changes. Both rebase row
 *   indices. The adapter's item id is the message's sample key, which is
 *   stable across both events, so it is used as the navigation anchor and the
 *   index is re-resolved on every step.
 *
 * Navigation re-enters NSG's own click handler via performItemClick(), so the
 * normal popup update path (detail text, copy button, freeze-mode seek) runs
 * unchanged.
 */
public class SignalingNavHook {

    private static final String TAG = "NSGBandHook";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // --- u7.a reflection ---
    private Field u7aFieldA;   // int selector: 0 = events, 1 = signaling
    private Field u7aFieldB;   // fragment (u7.f / y80)
    private Field u7fPopupField;

    private boolean reflectionReady = false;

    /**
     * Per-popup navigation state. The popup view hierarchy is created once and
     * reused for every message, so this object also survives across opens.
     */
    private static final class NavState {
        /** Sample key of the message currently shown; -1 when unknown. */
        long currentKey = -1L;
        /** Last known adapter index for currentKey (fast path, revalidated). */
        int knownPosition = -1;
        /** True while a detail text fetch triggered by an arrow press is in flight. */
        volatile boolean navPending = false;
        /** uptimeMillis when navPending was set; used to self-heal a stuck guard. */
        long navPendingSince = 0L;
        WeakReference<AdapterView<?>> listRef = new WeakReference<>(null);
        WeakReference<FrameLayout> frameRef = new WeakReference<>(null);
        Button prevBtn;
        Button nextBtn;
        /** Adapter this state is observing, so an adapter swap can be detected. */
        android.widget.Adapter observedAdapter;
        final android.database.DataSetObserver observer =
                new android.database.DataSetObserver() {
                    @Override
                    public void onChanged() {
                        FrameLayout frame = frameRef.get();
                        if (frame != null) {
                            frame.post(new Runnable() {
                                @Override
                                public void run() {
                                    FrameLayout f = frameRef.get();
                                    if (f != null) {
                                        // Refresh availability without needing a click.
                                        refreshStateOnly(f, NavState.this);
                                    }
                                }
                            });
                        }
                    }

                    @Override
                    public void onInvalidated() {
                        onChanged();
                    }
                };
    }

    public SignalingNavHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> u7aClass = ClassMapping.loadClass("u7.a", loader);
            Class<?> u7fClass = ClassMapping.loadClass("u7.f", loader);
            if (u7aClass == null || u7fClass == null) {
                Log.i(TAG, "SignalingNavHook: u7.a or u7.f missing, skipping");
                return;
            }

            String fieldAName = ClassMapping.runtimeFieldName("u7.a", "a", loader);
            String fieldBName = ClassMapping.runtimeFieldName("u7.a", "b", loader);
            u7aFieldA = u7aClass.getDeclaredField(fieldAName);
            u7aFieldA.setAccessible(true);
            u7aFieldB = u7aClass.getDeclaredField(fieldBName);
            u7aFieldB.setAccessible(true);

            u7fPopupField = findFieldByType(u7fClass, PopupWindow.class);
            if (u7fPopupField == null) {
                throw new NoSuchFieldException("No PopupWindow field found in u7.f");
            }
            u7fPopupField.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "SignalingNavHook: initReflection failed: " + e);
        }
    }

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

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "SignalingNavHook: skipping install — reflection not ready");
            return;
        }
        try {
            java.lang.reflect.Method onItemClick =
                    ClassMapping.loadClass("u7.a", loader)
                            .getMethod("onItemClick",
                                    android.widget.AdapterView.class,
                                    android.view.View.class,
                                    int.class,
                                    long.class);

            xposed.hook(onItemClick).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();

                    try {
                        Object listener = chain.getThisObject();
                        if (((Number) u7aFieldA.get(listener)).intValue() == 0) {
                            return result; // events page, not signaling
                        }

                        Object fragment = u7aFieldB.get(listener);
                        if (fragment == null) return result;

                        PopupWindow popup = (PopupWindow) u7fPopupField.get(fragment);
                        if (popup == null) return result;

                        View contentView = popup.getContentView();
                        if (!(contentView instanceof FrameLayout)) return result;
                        FrameLayout frame = (FrameLayout) contentView;

                        Object adapterViewArg = chain.getArg(0);
                        if (!(adapterViewArg instanceof AdapterView)) return result;
                        AdapterView<?> list = (AdapterView<?>) adapterViewArg;

                        NavState state = getOrCreateState(frame);
                        state.listRef = new WeakReference<>(list);
                        state.frameRef = new WeakReference<>(frame);
                        state.currentKey = (long) chain.getArg(3);
                        state.knownPosition = (int) chain.getArg(2);
                        attachObserver(state, list);

                        if (!isInjected(frame)) {
                            if (!buildButtons(frame, state)) return result;
                        }

                        updateButtonState(frame, state);
                    } catch (Exception e) {
                        Log.w(TAG, "SignalingNavHook post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "SignalingNavHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "SignalingNavHook: install failed: " + e);
        }
    }

    private static boolean isInjected(FrameLayout frame) {
        return Boolean.TRUE.equals(frame.getTag(R.id.nsg_nav_added_tag));
    }

    private static NavState getOrCreateState(FrameLayout frame) {
        Object tag = frame.getTag(R.id.nsg_nav_state_tag);
        if (tag instanceof NavState) return (NavState) tag;
        NavState state = new NavState();
        frame.setTag(R.id.nsg_nav_state_tag, state);
        return state;
    }

    /**
     * Creates the two arrow buttons and pins them to the bottom-right corner.
     * Styling mirrors the prev/next buttons of SignalingSearchHook.
     */
    private boolean buildButtons(final FrameLayout frame, final NavState state) {
        // Set the latch BEFORE touching the view tree so a failure can never
        // leave an untracked pair of buttons behind.
        frame.setTag(R.id.nsg_nav_added_tag, Boolean.TRUE);

        android.content.Context ctx = frame.getContext();
        final float density = ctx.getResources().getDisplayMetrics().density;
        final int btnSize = Math.round(36 * density);
        final int margin = Math.round(2 * density);
        final int edgeMargin = Math.round(2 * density);

        Button prev = makeArrowButton(ctx, "\u25C4", btnSize, margin);
        Button next = makeArrowButton(ctx, "\u25BA", btnSize, margin);
        prev.setContentDescription("Previous message");
        next.setContentDescription("Next message");

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        containerLp.gravity = Gravity.END | Gravity.BOTTOM;
        containerLp.setMargins(0, 0, edgeMargin, edgeMargin);
        container.setLayoutParams(containerLp);
        container.addView(prev);
        container.addView(next);

        frame.addView(container);

        state.prevBtn = prev;
        state.nextBtn = next;

        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(frame, state, -1);
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navigate(frame, state, +1);
            }
        });

        // The buttons float over the bottom-right corner; the decoded text keeps
        // the full popup height (no full-width bottom inset). Only the scroll
        // track is shortened so it does not run underneath them — the value is
        // published as a tag for ScrollBarHook to read.
        frame.setTag(R.id.nsg_nav_bottom_padding_tag, btnSize + margin * 2 + edgeMargin);

        return true;
    }

    private Button makeArrowButton(android.content.Context ctx, String text,
                                   int size, int margin) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTransformationMethod(null);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        btn.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(margin, 0, margin, 0);
        btn.setLayoutParams(lp);
        btn.setPadding(0, 0, 0, 0);
        return btn;
    }

    /**
     * Jumps to the previous/next visible row.
     *
     * The step is resolved through the adapter's item id (the message sample
     * key), never by blind index arithmetic, because the visible list can be
     * rebuilt (filter change) or replaced (module switch) while the popup is
     * open. A press is ignored while a detail fetch is still in flight: NSG
     * loads details on a shared thread pool and the last completion wins, so
     * queueing steps would show the wrong message.
     */
    private void navigate(FrameLayout frame, NavState state, int delta) {
        try {
            AdapterView<?> list = state.listRef.get();
            if (list == null) {
                setEnabled(state.prevBtn, false);
                setEnabled(state.nextBtn, false);
                return;
            }

            android.widget.Adapter adapter = list.getAdapter();
            if (adapter == null) return;
            int count = adapter.getCount();
            if (count <= 0) return;

            // Ignore presses while the popup is still loading the previous
            // message (NSG clears the text to null before the async fetch and
            // runs each fetch on a shared pool; the last completion wins, so
            // queueing steps would show a different message than we track).
            if (state.navPending || isDetailLoading(frame)) {
                releaseStaleNavPending(state);
                return;
            }

            int current = resolvePosition(list, state);
            if (current < 0) return;

            int target = current + delta;
            if (target < 0 || target >= count) {
                updateButtonState(frame, state);
                return;
            }

            long itemId = adapter.getItemId(target);
            if (itemId < 0) return;

            state.knownPosition = target;
            state.currentKey = itemId;

            if (list instanceof ListView) {
                ((ListView) list).setItemChecked(target, true);
                // Do not call setSelection(): it forces a LAYOUT_SPECIFIC pass and
                // yanks the target row to the top of the list while the popup is
                // covering it. The checked state is enough to keep the highlight.
            }
            state.navPending = true;
            state.navPendingSince = android.os.SystemClock.uptimeMillis();
            list.performItemClick(null, target, itemId);
            // Release the guard once NSG's async detail fetch has completed
            // (it clears the text first, then repopulates it).
            releaseNavPendingWhenLoaded(frame, state);
        } catch (Throwable t) {
            Log.w(TAG, "SignalingNavHook: navigate failed: " + t);
        }
    }

    private static void attachObserver(NavState state, AdapterView<?> list) {
        android.widget.Adapter adapter = list.getAdapter();
        if (adapter == state.observedAdapter) return;
        if (state.observedAdapter != null) {
            try {
                state.observedAdapter.unregisterDataSetObserver(state.observer);
            } catch (Throwable ignored) { }
        }
        state.observedAdapter = adapter;
        if (adapter != null) {
            try {
                adapter.registerDataSetObserver(state.observer);
            } catch (Throwable t) {
                Log.w(TAG, "SignalingNavHook: observer registration failed: " + t);
            }
        }
    }

    /** Recomputes button availability without touching navigation state. */
    private static void refreshStateOnly(FrameLayout frame, NavState state) {
        try {
            AdapterView<?> list = state.listRef.get();
            if (list == null || state.prevBtn == null || state.nextBtn == null) return;
            android.widget.Adapter adapter = list.getAdapter();
            if (adapter != null && adapter != state.observedAdapter) {
                attachObserver(state, list);
            }
            int count = adapter == null ? 0 : adapter.getCount();
            int pos = resolvePosition(list, state);
            setEnabled(state.prevBtn, pos > 0);
            setEnabled(state.nextBtn, pos >= 0 && pos < count - 1);
        } catch (Throwable ignored) { }
    }

    /** Clears a stuck in-flight guard (e.g. after a failed detail fetch). */
    private static void releaseStaleNavPending(NavState state) {
        if (!state.navPending) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (state.navPendingSince == 0L) {
            state.navPendingSince = now;
        } else if (now - state.navPendingSince > 3000L) {
            state.navPending = false;
            state.navPendingSince = 0L;
        }
    }

    /** Clears {@link NavState#navPending} once the detail text has been repopulated. */
    private static void releaseNavPendingWhenLoaded(final FrameLayout frame,
                                                    final NavState state) {
        final int id = frame.getContext().getResources()
                .getIdentifier("detail", "id", "com.qtrun.QuickTest");
        if (id == 0) {
            state.navPending = false;
            state.navPendingSince = 0L;
            return;
        }
        final TextView detail = frame.findViewById(id);
        if (detail == null) {
            state.navPending = false;
            state.navPendingSince = 0L;
            return;
        }
        final int[] attempts = {0};
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            @Override
            public void run() {
                try {
                    CharSequence text = detail.getText();
                    boolean loaded = text != null && text.length() > 0;
                    if (loaded || ++attempts[0] > 100) { // ~2 s cap
                        state.navPending = false;
                        state.navPendingSince = 0L;
                        updateStateStatic(frame, state);
                    } else {
                        detail.postDelayed(poll[0], 20);
                    }
                } catch (Throwable t) {
                    state.navPending = false;
                    state.navPendingSince = 0L;
                }
            }
        };
        detail.postDelayed(poll[0], 20);
    }

    private static void updateStateStatic(FrameLayout frame, NavState state) {
        refreshStateOnly(frame, state);
    }

    /** Returns the adapter index of {@link NavState#currentKey}, or -1. */
    private static int resolvePosition(AdapterView<?> list, NavState state) {
        android.widget.Adapter adapter = list.getAdapter();
        if (adapter == null) return -1;
        int count = adapter.getCount();

        // Fast path: the remembered index still holds the same key.
        int known = state.knownPosition;
        if (known >= 0 && known < count && adapter.getItemId(known) == state.currentKey) {
            return known;
        }
        // Slow path: the visible list was rebuilt/reordered.
        for (int i = 0; i < count; i++) {
            if (adapter.getItemId(i) == state.currentKey) {
                state.knownPosition = i;
                return i;
            }
        }
        return -1;
    }

    private static boolean isDetailLoading(FrameLayout frame) {
        int id = frame.getContext().getResources()
                .getIdentifier("detail", "id", "com.qtrun.QuickTest");
        if (id == 0) return false;
        TextView detail = frame.findViewById(id);
        return detail != null && detail.getText() != null && detail.getText().length() == 0;
    }

    private void updateButtonState(FrameLayout frame, NavState state) {
        refreshStateOnly(frame, state);
    }

    private static void setEnabled(Button btn, boolean enabled) {
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1.0f : 0.4f);
    }
}
