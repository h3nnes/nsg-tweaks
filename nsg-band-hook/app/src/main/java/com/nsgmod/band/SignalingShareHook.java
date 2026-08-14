package com.nsgmod.band;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.nsgmod.band.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.4 (com.qtrun.QuickTest) to:
 * 1. Add a "Share" button to the LEFT of the existing "Copy" button on the
 *    signaling message detail popup.
 * 2. Enable text selection (highlighting + copy/share floating toolbar) on the
 *    message TextView, which NSG normally disables by setting
 *    ScrollingMovementMethod and BufferType.NORMAL.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Lets the original run untouched.
 *   - After: removes the Copy button from its original position, places it and
 *     a new Share button (with identical style) side-by-side in a horizontal
 *     LinearLayout anchored to end|top of the popup FrameLayout.
 *   - Polls until NSG's async text-set completes, then converts the buffer to
 *     EDITABLE and forces mSelectionControllerEnabled via reflection.
 *
 * Additional hooks for text selection in a PopupWindow context:
 *   - setMovementMethod: re-applies ArrowKeyMovementMethod after NSG overwrites
 *     it with ScrollingMovementMethod.
 *   - PopupWindow.setFocusable: calls setTouchModal(false) so selection handle
 *     windows can receive touches.
 *   - View.startActionMode: delegates to the Activity's window (the PopupWindow's
 *     window cannot create a floating ActionMode).
 *   - View.hasWindowFocus: forces true for the detail TextView (Editor requires it).
 *   - PopupWindow.showAtLocation: replaces the parent with the Activity's decor
 *     view (valid window token) and adjusts x,y for the coordinate space change.
 *   - PopupWindow.update: applies the same offset during handle drag.
 */
public class SignalingShareHook {

    private static final String TAG = "NSGBandHook";

    /** Tag set on the popup contentView once the Share button has been injected. */
    private static final String SHARE_BTN_TAG = "nsg_share_btn_added";

    private int idDetailCopy = 0x7f0900dd;
    private int idDetailText = 0x7f0900dc;
    private boolean resIdsResolved = false;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // --- u7.a reflection ---
    private Method onItemClickMethod;   // u7.a.onItemClick(AdapterView, View, int, long)
    private Field  u7aFieldA;           // u7.a.a  (int)  — switch selector
    private Field  u7aFieldB;           // u7.a.b  (u6.a) — associated fragment

    // --- u7.f reflection ---
    private Field  u7fPopupField;       // u7.f.<PopupWindow field> — found by type scan

    private boolean reflectionReady = false;

    /** Re-entry guard for the setMovementMethod hook (setTextIsSelectable calls
     *  setMovementMethod internally, which would recurse without this). */
    private static boolean setMMGuard = false;

    /** Re-entry guard for the setFocusable hook. */
    private static boolean setFocusableGuard = false;

    /** Tracks offset [x,y] for handle PopupWindows shown via our showAtLocation hook.
     *  Used by the update() hook to apply the same offset during handle drag. */
    private static final WeakHashMap<PopupWindow, int[]> handleOffsets = new WeakHashMap<>();

    /** Max retries for the text-set polling loop. */
    private static final int POLL_MAX_RETRIES = 20;
    /** Delay between polling attempts (ms). */
    private static final long POLL_DELAY_MS = 50;

    public SignalingShareHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    // -----------------------------------------------------------------------
    // Reflection
    // -----------------------------------------------------------------------

    private void initReflection() {
        try {
            Class<?> u7aClass = ClassMapping.loadClass("u7.a", loader);
            Class<?> u7fClass = ClassMapping.loadClass("u7.f", loader);

            // u7.a.onItemClick
            onItemClickMethod = u7aClass.getMethod("onItemClick",
                    android.widget.AdapterView.class,
                    android.view.View.class,
                    int.class,
                    long.class);

            // u7.a fields: dex names are "a"/"b" on qtrun, "b"/"c" on gplay d6.a
            String u7aFieldAName = ClassMapping.runtimeFieldName("u7.a", "a", loader);
            String u7aFieldBName = ClassMapping.runtimeFieldName("u7.a", "b", loader);
            u7aFieldA = u7aClass.getDeclaredField(u7aFieldAName);
            u7aFieldA.setAccessible(true);
            u7aFieldB = u7aClass.getDeclaredField(u7aFieldBName);
            u7aFieldB.setAccessible(true);

            // u7.f PopupWindow field — find by type scan (actual dex name unknown)
            u7fPopupField = findFieldByType(u7fClass, PopupWindow.class);
            if (u7fPopupField == null) {
                throw new NoSuchFieldException("No PopupWindow field found in u7.f");
            }
            u7fPopupField.setAccessible(true);

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "SignalingShareHook: initReflection failed: " + e);
        }
    }

    private void ensureResIds(Context ctx) {
        if (resIdsResolved) return;
        try {
            int copy = ctx.getResources().getIdentifier(
                    "detail_copy", "id", "com.qtrun.QuickTest");
            int text = ctx.getResources().getIdentifier(
                    "detail", "id", "com.qtrun.QuickTest");
            if (copy != 0) idDetailCopy = copy;
            if (text != 0) idDetailText = text;
            resIdsResolved = true;
        } catch (Throwable t) {
            Log.w(TAG, "SignalingShareHook: ensureResIds failed: " + t);
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
        hookSetMovementMethod();
        hookPopupSetFocusable();
        hookStartActionMode();
        hookHasWindowFocus();
        hookShowAtLocation();
        hookPopupUpdate();
    }

    // -----------------------------------------------------------------------
    // Hook: u7.a.onItemClick — after-hook
    // Injects Share button to the LEFT of Copy button inside a LinearLayout
    // anchored to end|top of the popup FrameLayout. Also polls for the async
    // text-set and forces text selection enablement.
    // -----------------------------------------------------------------------

    private void hookOnItemClick() {
        if (!reflectionReady) {
            Log.e(TAG, "SignalingShareHook: hookOnItemClick skipped — reflection not ready");
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
                        if (!(contentView instanceof FrameLayout)) return result;
                        FrameLayout frameLayout = (FrameLayout) contentView;

                        ensureResIds(contentView.getContext());

                        // Extract message title from the tapped list row view.
                        // Use a String[] holder so the OnClickListener always reads the
                        // latest title even when the PopupWindow is reused across opens.
                        String[] existingRef = (String[]) contentView.getTag(R.id.msg_title_ref_tag);
                        if (existingRef == null) {
                            existingRef = new String[]{"signaling"};
                            contentView.setTag(R.id.msg_title_ref_tag, existingRef);
                        }
                        final String[] msgTitleRef = existingRef;
                        // Always update with this open's title
                        {
                            String t = "";
                            try {
                                View rowView = (View) chain.getArg(1);
                                int titleId = rowView.getContext().getResources()
                                        .getIdentifier("msg_title", "id", "com.qtrun.QuickTest");
                                if (titleId != 0) {
                                    View tv = rowView.findViewById(titleId);
                                    if (tv instanceof TextView) {
                                        t = ((TextView) tv).getText().toString().trim();
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "SignalingShareHook: failed to read msg_title: " + e);
                            }
                            msgTitleRef[0] = t.isEmpty() ? "signaling" : t;
                        }

                        // Enable text selection on the detail TextView.
                        // NSG's async callback clears the text and sets it later,
                        // then overwrites the movement method with ScrollingMovementMethod.
                        // We poll until the text appears, then convert to EDITABLE buffer
                        // type and force mSelectionControllerEnabled via reflection.
                        try {
                            final TextView detailTv =
                                    (TextView) contentView.findViewById(idDetailText);
                            if (detailTv != null) {
                                detailTv.setTextIsSelectable(true);
                                detailTv.setEllipsize(null);
                                final android.os.Handler pollHandler =
                                        new android.os.Handler(android.os.Looper.getMainLooper());
                                final int[] pollRetries = {0};
                                final Runnable pollForText = new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            boolean textReady =
                                                    detailTv.getText().length() > 0
                                                            || pollRetries[0] >= POLL_MAX_RETRIES;
                                            if (textReady) {
                                                // Convert text to Editable buffer type.
                                                // NSG calls setText(String) which uses
                                                // BufferType.NORMAL — the text is NOT
                                                // Editable, so Editor.isTextEditable()
                                                // returns false, which causes
                                                // prepareCursorControllers() to set
                                                // mSelectionControllerEnabled=false,
                                                // completely disabling text selection.
                                                detailTv.setText(detailTv.getText(),
                                                        android.widget.TextView.BufferType.EDITABLE);
                                                detailTv.setTextIsSelectable(true);
                                                detailTv.setEllipsize(null);
                                                detailTv.setFocusable(true);
                                                // Force mSelectionControllerEnabled=true
                                                // on the Editor via reflection.
                                                try {
                                                    Field editorField =
                                                            TextView.class.getDeclaredField(
                                                                    "mEditor");
                                                    editorField.setAccessible(true);
                                                    Object editor = editorField.get(detailTv);
                                                    if (editor != null) {
                                                        Field sceField =
                                                                editor.getClass()
                                                                        .getDeclaredField(
                                                                                "mSelectionControllerEnabled");
                                                        sceField.setAccessible(true);
                                                        sceField.setBoolean(editor, true);
                                                    }
                                                } catch (Exception e) {
                                                    Log.w(TAG,
                                                            "SignalingShareHook:"
                                                            + " force selectionController failed: "
                                                            + e);
                                                }
                                            } else {
                                                pollRetries[0]++;
                                                pollHandler.postDelayed(this, POLL_DELAY_MS);
                                            }
                                        } catch (Exception e) {
                                            Log.w(TAG,
                                                    "SignalingShareHook: poll re-apply failed: " + e);
                                        }
                                    }
                                };
                                pollHandler.postDelayed(pollForText, POLL_DELAY_MS);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "SignalingShareHook: enable text selection failed: " + e);
                        }

                        // Guard against double-adding the Share button (UI restructure only once)
                        if (SHARE_BTN_TAG.equals(contentView.getTag())) {
                            return result;
                        }
                        contentView.setTag(SHARE_BTN_TAG);

                        // Find the Copy button
                        Button copyBtn = contentView.findViewById(idDetailCopy);
                        if (copyBtn == null) {
                            Log.w(TAG, "SignalingShareHook: detail_copy button not found");
                            return result;
                        }

                        // Remove copyBtn from its current parent (the FrameLayout)
                        ViewGroup copyParent = (ViewGroup) copyBtn.getParent();
                        if (copyParent != null) {
                            copyParent.removeView(copyBtn);
                        }

                        final Context ctx = contentView.getContext();
                        final ClassLoader cl = loader;

                        // Create Share button, copying all visual properties from copyBtn
                        Button shareBtn = new Button(ctx);
                        shareBtn.setText("Share");
                        shareBtn.setTransformationMethod(null);  // disable AllCaps transformation
                        shareBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, copyBtn.getTextSize());
                        shareBtn.setTextColor(copyBtn.getTextColors());
                        shareBtn.setTypeface(copyBtn.getTypeface());
                        shareBtn.setPadding(
                                copyBtn.getPaddingLeft(),
                                copyBtn.getPaddingTop(),
                                copyBtn.getPaddingRight(),
                                copyBtn.getPaddingBottom());
                        // Mirror MaterialButton insets and minHeight from copyBtn to suppress
                        // the default 48dp minHeight and 6dp top/bottom insets that inflate the button.
                        try {
                            Class<?> mbClass = Class.forName(
                                    "com.google.android.material.button.MaterialButton");
                            if (mbClass.isInstance(shareBtn) && mbClass.isInstance(copyBtn)) {
                                Method getInsetTop    = mbClass.getMethod("getInsetTop");
                                Method getInsetBottom = mbClass.getMethod("getInsetBottom");
                                Method getInsetLeft   = mbClass.getMethod("getInsetLeft");
                                Method getInsetRight  = mbClass.getMethod("getInsetRight");
                                Method setInsetTop    = mbClass.getMethod("setInsetTop",    int.class);
                                Method setInsetBottom = mbClass.getMethod("setInsetBottom", int.class);
                                Method setInsetLeft   = mbClass.getMethod("setInsetLeft",   int.class);
                                Method setInsetRight  = mbClass.getMethod("setInsetRight",  int.class);
                                setInsetTop.invoke(shareBtn,    getInsetTop.invoke(copyBtn));
                                setInsetBottom.invoke(shareBtn, getInsetBottom.invoke(copyBtn));
                                setInsetLeft.invoke(shareBtn,   getInsetLeft.invoke(copyBtn));
                                setInsetRight.invoke(shareBtn,  getInsetRight.invoke(copyBtn));
                            }
                        } catch (Throwable ignored) { /* non-Material theme — skip */ }
                        shareBtn.setMinimumHeight(copyBtn.getMinimumHeight());
                        if (copyBtn.getBackground() != null
                                && copyBtn.getBackground().getConstantState() != null) {
                            shareBtn.setBackground(
                                    copyBtn.getBackground().getConstantState().newDrawable());
                        }

                        // Share: wrap width, MATCH_PARENT height so it matches Copy button height
                        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.MATCH_PARENT);
                        int gap4dp = Math.round(4 * ctx.getResources().getDisplayMetrics().density);
                        btnLp.setMargins(0, 0, gap4dp, 0);  // right margin = gap between Share and Copy
                        btnLp.gravity = Gravity.CENTER_VERTICAL;
                        shareBtn.setLayoutParams(btnLp);
                        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        copyLp.gravity = Gravity.CENTER_VERTICAL;
                        copyBtn.setLayoutParams(copyLp);

                        // Make Copy button visible (was "invisible" in XML)
                        copyBtn.setVisibility(View.VISIBLE);

                        // Create horizontal container anchored to end|top of the FrameLayout
                        LinearLayout container = new LinearLayout(ctx);
                        container.setOrientation(LinearLayout.HORIZONTAL);
                        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT);
                        containerLp.gravity = Gravity.END | Gravity.TOP;
                        container.setLayoutParams(containerLp);

                        // Share first (left), then Copy (right)
                        container.addView(shareBtn);
                        container.addView(copyBtn);

                        frameLayout.addView(container);

                        // Store container reference so SignalingSearchHook can push it
                        // below the search bar once the search bar's height is known.
                        frameLayout.setTag(
                                "nsg_share_copy_container".hashCode(),
                                container);

                        // Wire up Share button click
                        shareBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                TextView detailTv = frameLayout.findViewById(idDetailText);
                                String messageText = detailTv != null
                                        ? detailTv.getText().toString()
                                        : "";

                                Uri fileUri = null;
                                try {
                                    File infoDir = ctx.getExternalFilesDir("info");
                                    if (infoDir != null) {
                                        infoDir.mkdirs();
                                        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                                .format(new Date());
                                        // Sanitize title: replace chars unsafe in filenames
                                        String safeTitle = msgTitleRef[0].replaceAll("[^a-zA-Z0-9_\\-]", "_");
                                        File tmpFile = new File(infoDir, safeTitle + "_" + ts + ".txt");
                                        try (FileOutputStream fos = new FileOutputStream(tmpFile);
                                             OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                                            w.write(messageText);
                                        }
                                        Class<?> fpClass = ClassMapping.loadClass(
                                                "androidx.core.content.FileProvider", cl);
                                        if (fpClass == null) {
                                            Log.w(TAG, "SignalingShareHook: FileProvider not available, cannot share file");
                                            return;
                                        }
                                        Method fpC = fpClass.getMethod("c",
                                                Context.class, String.class);
                                        Object strategy = fpC.invoke(null, ctx,
                                                "com.qtrun.QuickTest.fileprovider");
                                        try {
                                            Method fpB = strategy.getClass().getMethod("b", File.class);
                                            fileUri = (Uri) fpB.invoke(strategy, tmpFile);
                                        } catch (NoSuchMethodException nsme) {
                                            fileUri = buildFileProviderUri(strategy, tmpFile);
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "SignalingShareHook: share failed to write/get URI: " + e);
                                }

                                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                                if (fileUri != null) {
                                    shareIntent.setType("text/plain");
                                    shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                                    shareIntent.setClipData(new ClipData(
                                            null,
                                            new String[]{"text/plain"},
                                            new ClipData.Item(fileUri)));
                                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                } else {
                                    shareIntent.setType("text/plain");
                                    shareIntent.putExtra(Intent.EXTRA_TEXT, messageText);
                                }
                                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Signaling Message");

                                Intent chooser = Intent.createChooser(shareIntent, "Share Signaling Message");
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                ctx.startActivity(chooser);
                            }
                        });

                    } catch (Exception e) {
                        Log.w(TAG, "SignalingShareHook: hookOnItemClick post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "SignalingShareHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "SignalingShareHook: hookOnItemClick failed: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private Uri buildFileProviderUri(Object strategy, File file) throws Exception {
        Class<?> sc = strategy.getClass();
        Field af = sc.getDeclaredField("a");
        Field bf = sc.getDeclaredField("b");
        af.setAccessible(true);
        bf.setAccessible(true);
        String authority = (String) af.get(strategy);
        HashMap<String, File> roots = (HashMap<String, File>) bf.get(strategy);
        String canonicalPath = file.getCanonicalPath();
        String strippedCanonical = stripTrailingSlash(canonicalPath);
        Map.Entry<String, File> best = null;
        for (Map.Entry<String, File> entry : roots.entrySet()) {
            String rootPath = entry.getValue().getPath();
            String strippedRoot = stripTrailingSlash(rootPath);
            if (strippedCanonical.startsWith(strippedRoot + "/")
                    && (best == null || rootPath.length() > best.getValue().getPath().length())) {
                best = entry;
            }
        }
        if (best == null) return null;
        String rootPath = best.getValue().getPath();
        String subPath = rootPath.endsWith("/")
                ? canonicalPath.substring(rootPath.length())
                : canonicalPath.substring(rootPath.length() + 1);
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .encodedPath(Uri.encode(best.getKey()) + "/"
                        + Uri.encode(subPath, "/"))
                .build();
    }

    private static String stripTrailingSlash(String s) {
        return (s.length() > 0 && s.charAt(s.length() - 1) == '/')
                ? s.substring(0, s.length() - 1)
                : s;
    }

    // -----------------------------------------------------------------------
    // Hook: TextView.setMovementMethod — after-hook
    // NSG's async callback (u7.a line 76-78) calls
    // setMovementMethod(ScrollingMovementMethod) AFTER our hookOnItemClick runs,
    // overwriting the ArrowKeyMovementMethod that setTextIsSelectable(true)
    // installs. This hook re-applies ArrowKeyMovementMethod whenever
    // setMovementMethod is called on the signaling detail TextView.
    // -----------------------------------------------------------------------

    private void hookSetMovementMethod() {
        try {
            Method smmMethod = TextView.class.getMethod("setMovementMethod",
                    android.text.method.MovementMethod.class);
            xposed.hook(smmMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (setMMGuard) return result;
                    try {
                        Object thiz = chain.getThisObject();
                        if (thiz instanceof TextView) {
                            TextView tv = (TextView) thiz;
                            if (tv.getId() == idDetailText) {
                                setMMGuard = true;
                                try {
                                    tv.setMovementMethod(
                                            android.text.method.ArrowKeyMovementMethod.getInstance());
                                } finally {
                                    setMMGuard = false;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            Log.i(TAG, "SignalingShareHook: setMovementMethod hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookSetMovementMethod failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: PopupWindow.setFocusable — after-hook
    // NSG sets setFocusable(true) on the signaling detail PopupWindow (u7.f line 106).
    // We keep focusable=true so the TextView can get focus for text selection,
    // but also call setTouchModal(false) via reflection (API 24+) so the popup
    // is NOT touch-modal. This allows selection handle windows (separate windows
    // created by Editor) to receive touch events even though the popup is focusable.
    // -----------------------------------------------------------------------

    private void hookPopupSetFocusable() {
        try {
            Method setFocusableMethod = PopupWindow.class.getMethod(
                    "setFocusable", boolean.class);
            xposed.hook(setFocusableMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (setFocusableGuard) return result;
                    try {
                        boolean arg = (boolean) chain.getArg(0);
                        if (arg) {
                            PopupWindow popup = (PopupWindow) chain.getThisObject();
                            View contentView = popup.getContentView();
                            if (contentView != null
                                    && contentView.findViewById(idDetailText) != null) {
                                setFocusableGuard = true;
                                try {
                                    try {
                                        Method stm = PopupWindow.class.getMethod(
                                                "setTouchModal", boolean.class);
                                        stm.invoke(popup, false);
                                    } catch (NoSuchMethodException nsme) {
                                        Log.w(TAG, "SignalingShareHook: setTouchModal not available (API <24?)");
                                    }
                                } finally {
                                    setFocusableGuard = false;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            Log.i(TAG, "SignalingShareHook: PopupWindow.setFocusable hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookPopupSetFocusable failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: View.startActionMode — before-hook
    // The PopupWindow's window cannot create a floating ActionMode (returns null).
    // Intercept BEFORE the original runs and delegate to the Activity's window.
    // Wrap the callback in CoordinateAdjustingCallback so onGetContentRect
    // returns coordinates in Activity window space (the FloatingToolbar's
    // parent is the decor view, not the TextView).
    // -----------------------------------------------------------------------

    private void hookStartActionMode() {
        try {
            Method sam2Method = View.class.getMethod("startActionMode",
                    ActionMode.Callback.class, int.class);
            xposed.hook(sam2Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    try {
                        View view = (View) chain.getThisObject();
                        if (view.getId() == idDetailText) {
                            ActionMode.Callback cb = (ActionMode.Callback) chain.getArg(0);
                            int type = (int) chain.getArg(1);
                            Activity activity = extractActivity(view.getContext());
                            if (activity != null) {
                                ActionMode.Callback wrapped =
                                        new CoordinateAdjustingCallback(cb, view);
                                return activity.startActionMode(wrapped, type);
                            }
                            Log.w(TAG, "SignalingShareHook: startActionMode — no Activity found, falling through");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "SignalingShareHook: startActionMode intercept error: " + t);
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "SignalingShareHook: startActionMode hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookStartActionMode failed: " + e);
        }
    }

    private static Activity extractActivity(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Hook: PopupWindow.showAtLocation — replace parent token + adjust coords
    // The Editor's selection handles call showAtLocation(mTextView, ...) using the
    // PopupWindow's window token, which is invalid. Replace the parent with the
    // Activity's decor view so the handles get a valid window token.
    // Also adjust x,y: the Editor passes coordinates in the PopupWindow's coord
    // space, but with the decor view as parent, they need to be in the Activity
    // window's coord space. The offset = tvScreen - tvInWindow - decorScreen.
    // -----------------------------------------------------------------------

    private void hookShowAtLocation() {
        try {
            Method sal4 = PopupWindow.class.getMethod("showAtLocation",
                    View.class, int.class, int.class, int.class);
            xposed.hook(sal4).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    View parent = (View) chain.getArg(0);
                    if (parent.getId() == idDetailText) {
                        try {
                            Activity activity = extractActivity(parent.getContext());
                            if (activity != null) {
                                View decorView = activity.getWindow().getDecorView();
                                if (decorView != null && decorView.getWindowToken() != null) {
                                    int origX = (int) chain.getArg(2);
                                    int origY = (int) chain.getArg(3);
                                    int[] tvScreen = new int[2];
                                    parent.getLocationOnScreen(tvScreen);
                                    int[] tvInWindow = new int[2];
                                    parent.getLocationInWindow(tvInWindow);
                                    int[] decorScreen = new int[2];
                                    decorView.getLocationOnScreen(decorScreen);
                                    int offsetX = tvScreen[0] - tvInWindow[0] - decorScreen[0];
                                    int offsetY = tvScreen[1] - tvInWindow[1] - decorScreen[1];
                                    handleOffsets.put((PopupWindow) chain.getThisObject(),
                                            new int[]{offsetX, offsetY});
                                    return chain.proceed(new Object[]{decorView,
                                            chain.getArg(1), origX + offsetX, origY + offsetY});
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "SignalingShareHook: showAtLocation token replace failed: " + t);
                        }
                    }
                    try {
                        return chain.proceed();
                    } catch (android.view.WindowManager.BadTokenException e) {
                        Log.w(TAG, "SignalingShareHook: caught BadTokenException in showAtLocation: " + e.getMessage());
                        return null;
                    }
                }
            });
            Log.i(TAG, "SignalingShareHook: showAtLocation hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookShowAtLocation failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // Hook: PopupWindow.update(int,int,int,int) — apply same offset as showAtLocation
    // The Editor's HandleView calls update() to reposition handles during drag.
    // Since we replaced the parent with the decor view in showAtLocation, the
    // popup is in the Activity's window, but HandleView computes x,y in the
    // PopupWindow's window space. Add the stored offset to convert.
    // -----------------------------------------------------------------------

    private void hookPopupUpdate() {
        try {
            Method updateMethod = PopupWindow.class.getMethod("update",
                    int.class, int.class, int.class, int.class);
            xposed.hook(updateMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    PopupWindow popup = (PopupWindow) chain.getThisObject();
                    int[] offset = handleOffsets.get(popup);
                    if (offset != null) {
                        int x = (int) chain.getArg(0) + offset[0];
                        int y = (int) chain.getArg(1) + offset[1];
                        return chain.proceed(new Object[]{x, y, chain.getArg(2), chain.getArg(3)});
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "SignalingShareHook: PopupWindow.update hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookPopupUpdate failed: " + e);
        }
    }

    // -----------------------------------------------------------------------
    // CoordinateAdjustingCallback — wraps ActionMode.Callback to compute
    // selection bounds in Activity window space.
    // The FloatingActionMode is created on the Activity's window (via delegated
    // startActionMode), so the `view` parameter passed to onGetContentRect is
    // the decor view, not the TextView. The Editor's SelectionActionModeCallback
    // expects the TextView to compute selection-specific bounds — with the
    // decor view, it falls back to returning the full screen bounds, causing
    // the FloatingToolbar to follow the TextView's top off-screen when scrolled.
    // We compute the selection bounds ourselves from the captured TextView,
    // then offset to Activity window (decor view) space.
    // -----------------------------------------------------------------------

    private static class CoordinateAdjustingCallback extends ActionMode.Callback2 {
        private final ActionMode.Callback original;
        private final View textView;

        CoordinateAdjustingCallback(ActionMode.Callback original, View textView) {
            this.original = original;
            this.textView = textView;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return original.onCreateActionMode(mode, menu);
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return original.onPrepareActionMode(mode, menu);
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return original.onActionItemClicked(mode, item);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            original.onDestroyActionMode(mode);
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            // Compute selection bounds in TextView-local coordinate space.
            boolean computed = false;
            if (textView instanceof TextView) {
                try {
                    TextView tv = (TextView) textView;
                    int selStart = tv.getSelectionStart();
                    int selEnd = tv.getSelectionEnd();
                    android.text.Layout layout = tv.getLayout();
                    if (layout != null && selStart >= 0 && selEnd >= 0) {
                        int minSel = Math.min(selStart, selEnd);
                        int maxSel = Math.max(selStart, selEnd);
                        int startLine = layout.getLineForOffset(minSel);
                        int endLine = layout.getLineForOffset(maxSel);
                        float hStart = layout.getPrimaryHorizontal(selStart);
                        float hEnd = layout.getPrimaryHorizontal(selEnd);
                        int left = (int) Math.min(hStart, hEnd);
                        int right = (int) Math.max(hStart, hEnd);
                        int padLeft = tv.getTotalPaddingLeft();
                        int padTop = tv.getTotalPaddingTop();
                        int scrollX = tv.getScrollX();
                        int scrollY = tv.getScrollY();
                        outRect.set(
                                left + padLeft - scrollX,
                                layout.getLineTop(startLine) + padTop - scrollY,
                                Math.max(right, left + 1) + padLeft - scrollX,
                                layout.getLineBottom(endLine) + padTop - scrollY);
                        computed = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "SignalingShareHook: compute selection bounds failed: " + e);
                }
            }
            if (!computed) {
                if (original instanceof ActionMode.Callback2) {
                    ((ActionMode.Callback2) original).onGetContentRect(mode, view, outRect);
                } else {
                    super.onGetContentRect(mode, view, outRect);
                }
            }
            // Offset from TextView space to Activity window (decor view) space
            try {
                int[] tvScreen = new int[2];
                textView.getLocationOnScreen(tvScreen);
                Activity activity = extractActivity(textView.getContext());
                if (activity != null) {
                    int[] decorScreen = new int[2];
                    activity.getWindow().getDecorView().getLocationOnScreen(decorScreen);
                    outRect.offset(tvScreen[0] - decorScreen[0], tvScreen[1] - decorScreen[1]);
                }
            } catch (Exception e) {
                Log.w(TAG, "SignalingShareHook: onGetContentRect offset failed: " + e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hook: View.hasWindowFocus — after-hook
    // The Editor class checks TextView.hasWindowFocus() before starting text
    // selection. A PopupWindow with setFocusable(true) should have window focus,
    // but for unknown reasons it reports hasWindowFocus=false. This hook forces
    // it to return true for the signaling detail TextView, unblocking the
    // Editor's selection pipeline.
    // -----------------------------------------------------------------------

    private void hookHasWindowFocus() {
        try {
            Method hwfMethod = View.class.getMethod("hasWindowFocus");
            xposed.hook(hwfMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        if (!(boolean) result) {
                            View view = (View) chain.getThisObject();
                            if (view.getId() == idDetailText) {
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                }
            });
            Log.i(TAG, "SignalingShareHook: hasWindowFocus hook installed");
        } catch (Exception e) {
            Log.w(TAG, "SignalingShareHook: hookHasWindowFocus failed: " + e);
        }
    }
}
