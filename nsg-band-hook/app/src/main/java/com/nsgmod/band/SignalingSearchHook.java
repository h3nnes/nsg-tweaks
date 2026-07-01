package com.nsgmod.band;

import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.4 (com.qtrun.QuickTest) to inject a search bar UI at the top
 * of the signaling message detail popup.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Finds the FrameLayout content view of the popup.
 *   - Adds a semi-transparent search bar LinearLayout pinned to the top.
 *   - Supports async search (to avoid freezing on long messages) with yellow/orange highlights
 *     and prev/next navigation.
 *   - Aa button toggles case sensitivity.
 *   - Increases ScrollView top padding to avoid the message being hidden under the bar.
 *   - Guarded by a String tag on the ScrollView so the bar is only added once per popup instance.
 */
public class SignalingSearchHook {

    private static final String TAG = "NSGBandHook";
    private static final String SEARCH_BAR_TAG = "nsg_search_bar_added";

    /** Resource ID of the message TextView inside the signaling detail popup. */
    private static final int ID_DETAIL_TEXT = 0x7f0900dc;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // --- u7.a reflection ---
    private Method onItemClickMethod;
    private Field  u7aFieldA;
    private Field  u7aFieldB;

    // --- u7.f reflection ---
    private Field  u7fPopupField;

    private boolean reflectionReady = false;

    public SignalingSearchHook(XposedInterface xposed, ClassLoader loader) {
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

            onItemClickMethod = u7aClass.getMethod("onItemClick",
                    android.widget.AdapterView.class,
                    android.view.View.class,
                    int.class,
                    long.class);

            u7aFieldA = u7aClass.getDeclaredField("a");
            u7aFieldA.setAccessible(true);
            u7aFieldB = u7aClass.getDeclaredField("b");
            u7aFieldB.setAccessible(true);

            u7fPopupField = findFieldByType(u7fClass, PopupWindow.class);
            if (u7fPopupField == null) {
                throw new NoSuchFieldException("No PopupWindow field found in u7.f");
            }
            u7fPopupField.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "SignalingSearchHook: initReflection failed: " + e);
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

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void install() {
        hookOnItemClick();
    }

    // -----------------------------------------------------------------------
    // Search state — held per popup instance, captured by lambdas
    // -----------------------------------------------------------------------

    /**
     * Mutable state object for a single popup's search session.
     * Captured by the TextWatcher and button click listeners.
     */
     private static class SearchState {
        List<int[]> matches = new ArrayList<>(); // each entry: {start, end}
        int currentIndex = 0;
        boolean caseSensitive = false;
        /** Plain text captured at search time, used for span re-application during navigation. */
        String lastFullText = "";
        /** True when search was capped at 200 results. Shown as "(max)" in count label. */
        boolean capped = false;
        /** Previous currentIndex, used by reapplySpans to only swap two spans. */
        int previousIndex = -1;
    }

    // -----------------------------------------------------------------------
    // Hook
    // -----------------------------------------------------------------------

    private void hookOnItemClick() {
        if (!reflectionReady) {
            Log.e(TAG, "hookOnItemClick (SignalingSearchHook) skipped — reflection not ready");
            return;
        }
        try {
            xposed.hook(onItemClickMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();

                    try {
                        Object listener = chain.getThisObject(); // u7.a instance

                        int selector = (int) u7aFieldA.get(listener);
                        if (selector == 0) return result;

                        Object fragment = u7aFieldB.get(listener);
                        if (fragment == null) return result;

                        PopupWindow popup = (PopupWindow) u7fPopupField.get(fragment);
                        if (popup == null) return result;

                        View contentView = popup.getContentView();
                        if (!(contentView instanceof FrameLayout)) return result;
                        final FrameLayout frame = (FrameLayout) contentView;

                        // Find the ScrollView
                        final ScrollView scrollView = findScrollView(frame);
                        if (scrollView == null) {
                            Log.w(TAG, "SignalingSearchHook: no ScrollView found in popup content");
                            return result;
                        }

                        // Retrieve or create per-popup SearchState
                        final int STATE_TAG = "nsg_search_state".hashCode();
                        SearchState existingState = null;
                        Object stateTag = frame.getTag(STATE_TAG);
                        if (stateTag instanceof SearchState) {
                            existingState = (SearchState) stateTag;
                        }
                        final SearchState state = existingState != null ? existingState : new SearchState();

                        // Always reset state on every open — NSG reuses the same view hierarchy
                        // and asynchronously replaces the TextView text after onItemClick returns,
                        // so any cached text from the previous open is now stale.
                        state.matches.clear();
                        state.currentIndex = 0;
                        state.capped = false;
                        state.lastFullText = "";   // CRITICAL: force re-read of text at next search
                        state.previousIndex = -1;

                        // Guard: only build and add the search bar UI once (views are reused)
                        if (SEARCH_BAR_TAG.equals(frame.getTag(SEARCH_BAR_TAG.hashCode()))) {
                            // UI already present — nothing more to do; state is already reset above.
                            return result;
                        }
                        frame.setTag(SEARCH_BAR_TAG.hashCode(), SEARCH_BAR_TAG);
                        frame.setTag(STATE_TAG, state);

                        android.content.Context ctx = frame.getContext();
                        final float density = ctx.getResources().getDisplayMetrics().density;

                        // Find the message TextView
                        final TextView msgTextView = frame.findViewById(ID_DETAIL_TEXT);
                        if (msgTextView == null) {
                            Log.w(TAG, "SignalingSearchHook: message TextView not found");
                            return result;
                        }

                        // --- Background worker for async search ---
                        HandlerThread workerThread = new HandlerThread("nsg-search-worker");
                        workerThread.start();
                        final Handler worker = new Handler(workerThread.getLooper());

                        // ---------------------------------------------------------------
                        // Build the search bar
                        // ---------------------------------------------------------------

                        LinearLayout searchBar = new LinearLayout(ctx);
                        searchBar.setOrientation(LinearLayout.HORIZONTAL);
                        searchBar.setBackgroundColor(0xCC000000); // semi-transparent dark
                        int barPadH = Math.round(4 * density);
                        int barPadV = Math.round(4 * density);
                        searchBar.setPadding(barPadH, barPadV, barPadH, barPadV);
                        searchBar.setMinimumHeight(Math.round(48 * density));

                        // Pin to the top of the FrameLayout
                        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT);
                        barLp.gravity = Gravity.TOP;
                        searchBar.setLayoutParams(barLp);

                        // 1. EditText (fills available width with weight=1)
                        final EditText searchEdit = new EditText(ctx);
                        searchEdit.setHint("Search\u2026");
                        searchEdit.setSingleLine(true);
                        searchEdit.setTextColor(Color.WHITE);
                        searchEdit.setHintTextColor(0xFFAAAAAA);
                        searchEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                        searchEdit.setBackgroundColor(0x33FFFFFF);
                        int editPad = Math.round(4 * density);
                        searchEdit.setPadding(editPad, editPad, editPad, editPad);
                        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                         searchEdit.setLayoutParams(editLp);
                         // Show a search/go action key on the soft keyboard instead of newline
                         searchEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);

                         // 2. Count TextView (e.g. "3 / 12")
                        final TextView countTv = new TextView(ctx);
                        countTv.setText("");
                        countTv.setTextColor(Color.WHITE);
                        countTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                        countTv.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                        int countWidthPx = Math.round(60 * density);
                        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                                countWidthPx, LinearLayout.LayoutParams.MATCH_PARENT);
                        int countMargin = Math.round(4 * density);
                        countLp.setMargins(countMargin, 0, countMargin, 0);
                        countTv.setLayoutParams(countLp);

                        // 3. Aa toggle button
                        final Button aaBtn = new Button(ctx);
                        aaBtn.setText("Aa");
                        aaBtn.setTransformationMethod(null);
                        aaBtn.setTextColor(Color.WHITE); // default: case-insensitive
                        aaBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                        aaBtn.setBackgroundColor(0x44FFFFFF);
                        int aaBtnSize = Math.round(36 * density);
                        LinearLayout.LayoutParams aaLp = new LinearLayout.LayoutParams(aaBtnSize, LinearLayout.LayoutParams.MATCH_PARENT);
                        int aaMargin = Math.round(2 * density);
                        aaLp.setMargins(aaMargin, 0, aaMargin, 0);
                        aaBtn.setLayoutParams(aaLp);
                        aaBtn.setPadding(0, 0, 0, 0);

                        // 4. Prev button ◀
                        final Button prevBtn = new Button(ctx);
                        prevBtn.setText("\u25C4");
                        prevBtn.setTransformationMethod(null);
                        prevBtn.setTextColor(Color.WHITE);
                        prevBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                        prevBtn.setBackgroundColor(0x44FFFFFF);
                        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(aaBtnSize, LinearLayout.LayoutParams.MATCH_PARENT);
                        prevLp.setMargins(aaMargin, 0, aaMargin, 0);
                        prevBtn.setLayoutParams(prevLp);
                        prevBtn.setPadding(0, 0, 0, 0);

                        // 5. Next/Search button — starts as "Search", becomes "▶" after search
                        final Button nextBtn = new Button(ctx);
                        nextBtn.setText("S");
                        nextBtn.setTransformationMethod(null);
                        nextBtn.setTextColor(Color.WHITE);
                        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                        nextBtn.setBackgroundColor(0x44FFFFFF);
                        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(aaBtnSize, LinearLayout.LayoutParams.MATCH_PARENT);
                        nextLp.setMargins(aaMargin, 0, aaMargin, 0);
                        nextBtn.setLayoutParams(nextLp);
                         nextBtn.setPadding(0, 0, 0, 0);

                         // Keyboard Enter / Search action key triggers the same as tapping "S"
                         searchEdit.setOnEditorActionListener((v, actionId, event) -> {
                             boolean isSearch = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;
                             boolean isEnter  = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
                                     && event != null
                                     && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                                     && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
                             if (isSearch || isEnter) {
                                 nextBtn.performClick();
                                 return true;
                             }
                             return false;
                         });

                         searchBar.addView(searchEdit);
                        searchBar.addView(countTv);
                        searchBar.addView(aaBtn);
                        searchBar.addView(prevBtn);
                        searchBar.addView(nextBtn);

                        frame.addView(searchBar);

                        // ---------------------------------------------------------------
                        // Search logic helpers
                        // ---------------------------------------------------------------

                        final Runnable scrollToCurrent = new Runnable() {
                            @Override
                            public void run() {
                                if (state.matches.isEmpty()) return;
                                final int[] match = state.matches.get(state.currentIndex);
                                msgTextView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Layout layout = msgTextView.getLayout();
                                        if (layout == null) return;
                                        int line = layout.getLineForOffset(match[0]);
                                        int y = layout.getLineTop(line);
                                        int scrollTarget = Math.max(0, y - scrollView.getHeight() / 3);
                                        scrollView.smoothScrollTo(0, scrollTarget);
                                    }
                                });
                            }
                        };

                        // Re-apply spans from already-found matches (no indexOf — O(N_matches) only)
                        // Must be called on main thread.
                        final Runnable reapplySpans = new Runnable() {
                            @Override
                            public void run() {
                                if (state.matches.isEmpty()) return;
                                android.text.Spanned spanned = (android.text.Spanned) msgTextView.getText();
                                if (!(spanned instanceof Spannable)) return;
                                Spannable sp = (Spannable) spanned;

                                // Only recolor the two spans that changed: old current → yellow, new current → orange
                                int[] indices = (state.previousIndex >= 0 && state.previousIndex != state.currentIndex)
                                        ? new int[]{state.previousIndex, state.currentIndex}
                                        : new int[]{state.currentIndex};

                                for (int idx : indices) {
                                    if (idx < 0 || idx >= state.matches.size()) continue;
                                    int[] m = state.matches.get(idx);
                                    int bgColor = (idx == state.currentIndex) ? 0xFFFF8C00 : 0xFFFFFF00;
                                    BackgroundColorSpan[] existing = sp.getSpans(m[0], m[1], BackgroundColorSpan.class);
                                    for (BackgroundColorSpan s : existing) sp.removeSpan(s);
                                    sp.setSpan(new BackgroundColorSpan(bgColor), m[0], m[1],
                                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                }
                                state.previousIndex = state.currentIndex;

                                countTv.setText((state.currentIndex + 1) + " / "
                                        + state.matches.size()
                                        + (state.capped ? " (max)" : ""));
                                scrollToCurrent.run();
                            }
                        };

                        // ---------------------------------------------------------------
                        // Wire up controls
                        // ---------------------------------------------------------------

                        // Lightweight TextWatcher: only clears results and resets button — no search
                        searchEdit.addTextChangedListener(new TextWatcher() {
                            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                            @Override
                            public void afterTextChanged(Editable s) {
                                // Only reset state; do NOT call msgTextView.setText here — that is
                                // extremely slow on long messages. Spans will be cleared when search runs.
                                state.matches.clear();
                                state.currentIndex = 0;
                                state.capped = false;
                                countTv.setText("");
                                nextBtn.setText("S");
                            }
                        });

                        aaBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                state.caseSensitive = !state.caseSensitive;
                                aaBtn.setTextColor(state.caseSensitive ? Color.YELLOW : Color.WHITE);
                                // Clear stale results — user must re-search with new sensitivity
                                if (!state.matches.isEmpty()) {
                                    if (!state.lastFullText.isEmpty()) {
                                        msgTextView.setText(state.lastFullText);
                                    }
                                    state.matches.clear();
                                    state.currentIndex = 0;
                                    state.capped = false;
                                    countTv.setText("");
                                    nextBtn.setText("S");
                                }
                            }
                        });

                        prevBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (state.matches.isEmpty()) return;
                                state.currentIndex = (state.currentIndex - 1 + state.matches.size())
                                        % state.matches.size();
                                reapplySpans.run();
                            }
                        });

                        nextBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (!state.matches.isEmpty()) {
                                    // Navigate to next match
                                    state.currentIndex = (state.currentIndex + 1) % state.matches.size();
                                    reapplySpans.run();
                                    return;
                                }

                                // --- Act as "Search" button ---
                                final String query = searchEdit.getText() != null
                                        ? searchEdit.getText().toString() : "";
                                if (query.isEmpty()) return;

                                final boolean caseSensitive = state.caseSensitive;
                                // Use cached plain text if available (avoids reading potentially-spanned TextView)
                                // toString() on a Spannable does strip spans, but state.lastFullText is cheaper
                                // when already populated (e.g. after a previous search in this popup).
                                final String fullText;
                                if (!state.lastFullText.isEmpty()) {
                                    fullText = state.lastFullText;
                                } else {
                                    fullText = msgTextView.getText() != null
                                            ? msgTextView.getText().toString() : "";
                                }

                                // Disable buttons during search
                                nextBtn.setEnabled(false);
                                prevBtn.setEnabled(false);
                                aaBtn.setEnabled(false);
                                countTv.setText("Searching\u2026");

                                worker.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        List<int[]> results = new ArrayList<>();
                                        boolean cappedLocal = false;
                                        try {
                                            String searchIn = caseSensitive ? fullText : fullText.toLowerCase();
                                            String searchFor = caseSensitive ? query : query.toLowerCase();
                                            int idx = 0;
                                             while (results.size() < 200) {
                                                 int pos = searchIn.indexOf(searchFor, idx);
                                                 if (pos < 0) break;
                                                 results.add(new int[]{pos, pos + searchFor.length()});
                                                 idx = pos + 1;
                                             }
                                             cappedLocal = results.size() == 200;
                                        } catch (Exception ex) {
                                            Log.w(TAG, "SignalingSearchHook: search error: " + ex);
                                        }

                                        final List<int[]> finalResults = results;
                                        final boolean capped = cappedLocal;
                                        msgTextView.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    nextBtn.setEnabled(true);
                                                    prevBtn.setEnabled(true);
                                                    aaBtn.setEnabled(true);

                                                    if (finalResults.isEmpty()) {
                                                        countTv.setText("0 / 0");
                                                         nextBtn.setText("S");
                                                         state.matches.clear();
                                                        state.currentIndex = 0;
                                                        return;
                                                    }

                                                    state.lastFullText = fullText;
                                                    state.matches = finalResults;
                                                    state.capped = capped;
                                                    state.currentIndex = 0;
                                                    state.previousIndex = -1;

                                                    // Build spannable
                                                    SpannableStringBuilder ssb = new SpannableStringBuilder(fullText);
                                                    for (int i = 0; i < state.matches.size(); i++) {
                                                        int[] m = state.matches.get(i);
                                                        int bgColor = (i == state.currentIndex) ? 0xFFFF8C00 : 0xFFFFFF00;
                                                        ssb.setSpan(new BackgroundColorSpan(bgColor),
                                                                m[0], m[1],
                                                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                                        ssb.setSpan(new ForegroundColorSpan(Color.BLACK),
                                                                m[0], m[1],
                                                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                                    }
                                                    msgTextView.setText(ssb);
                                                    String countLabel = "1 / " + state.matches.size()
                                                            + (capped ? " (max)" : "");
                                                    countTv.setText(countLabel);
                                                    nextBtn.setText("\u25BA");
                                                    scrollToCurrent.run();
                                                } catch (Exception ex) {
                                                    Log.w(TAG, "SignalingSearchHook: post-search UI error: " + ex);
                                                    nextBtn.setEnabled(true);
                                                    prevBtn.setEnabled(true);
                                                    aaBtn.setEnabled(true);
                                                }
                                            }
                                        });
                                    }
                                });
                            }
                        });

                        // ---------------------------------------------------------------
                        // Measure search bar height, then adjust ScrollView top padding
                        // ---------------------------------------------------------------
                        searchBar.getViewTreeObserver().addOnGlobalLayoutListener(
                                new ViewTreeObserver.OnGlobalLayoutListener() {
                                    @Override
                                    public void onGlobalLayout() {
                                        searchBar.getViewTreeObserver()
                                                .removeOnGlobalLayoutListener(this);
                                        int barH = searchBar.getHeight();
                                        if (barH <= 0) return;

                                        frame.setTag("nsg_search_bar_height".hashCode(), barH);

                                        // Push ScrollView content below the search bar
                                        scrollView.setPadding(
                                                scrollView.getPaddingLeft(),
                                                barH,
                                                scrollView.getPaddingRight(),
                                                scrollView.getPaddingBottom());

                                        // Push Share+Copy container below the search bar.
                                        // SignalingShareHook stored the container reference
                                        // as a keyed tag on the FrameLayout.
                                        Object tag = frame.getTag("nsg_share_copy_container".hashCode());
                                        if (tag instanceof View) {
                                            View shareCopyContainer = (View) tag;
                                            ViewGroup.MarginLayoutParams lp =
                                                    (ViewGroup.MarginLayoutParams) shareCopyContainer.getLayoutParams();
                                            if (lp != null) {
                                                lp.topMargin = barH;
                                                shareCopyContainer.setLayoutParams(lp);
                                            }
                                        }
                                    }
                                    });

                    } catch (Exception e) {
                        Log.w(TAG, "SignalingSearchHook post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "SignalingSearchHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "SignalingSearchHook: hookOnItemClick failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
