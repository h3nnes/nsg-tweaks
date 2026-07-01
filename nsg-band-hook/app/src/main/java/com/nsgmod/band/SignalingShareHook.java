package com.nsgmod.band;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks NSG 4.8.4 (com.qtrun.QuickTest) to add a "Share" button immediately to
 * the LEFT of the existing "Copy" button on the signaling message detail popup.
 *
 * Hook: u7.a.onItemClick (after-hook)
 *   - Lets the original run untouched.
 *   - After: removes the Copy button from its original position, places it and
 *     a new Share button (with identical style) side-by-side in a horizontal
 *     LinearLayout anchored to end|top of the popup FrameLayout.
 */
public class SignalingShareHook {

    private static final String TAG = "NSGBandHook";

    /** Tag set on the popup contentView once the Share button has been injected. */
    private static final String SHARE_BTN_TAG = "nsg_share_btn_added";

    /** Resource ID of the copy button inside the signaling detail popup. */
    private static final int ID_DETAIL_COPY = 0x7f0900dd;

    /** Resource ID of the message TextView inside the signaling detail popup. */
    private static final int ID_DETAIL_TEXT = 0x7f0900dc;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // --- u7.a reflection ---
    private Method onItemClickMethod;   // u7.a.onItemClick(AdapterView, View, int, long)
    private Field  u7aFieldA;           // u7.a.a  (int)  — switch selector
    private Field  u7aFieldB;           // u7.a.b  (u6.a) — associated fragment

    // --- u7.f reflection ---
    private Field  u7fPopupField;       // u7.f.<PopupWindow field> — found by type scan

    private boolean reflectionReady = false;

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
            Log.e(TAG, "SignalingShareHook: initReflection failed: " + e);
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
    // Injects Share button to the LEFT of Copy button inside a LinearLayout
    // anchored to end|top of the popup FrameLayout.
    // -----------------------------------------------------------------------

    private void hookOnItemClick() {
        if (!reflectionReady) {
            Log.e(TAG, "hookOnItemClick skipped — reflection not ready");
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
                                Log.w(TAG, "hookOnItemClick: failed to read msg_title: " + e);
                            }
                            msgTitleRef[0] = t.isEmpty() ? "signaling" : t;
                        }

                        // Guard against double-adding the Share button (UI restructure only once)
                        if (SHARE_BTN_TAG.equals(contentView.getTag())) {
                            return result;
                        }
                        contentView.setTag(SHARE_BTN_TAG);

                        // Find the Copy button
                        Button copyBtn = contentView.findViewById(ID_DETAIL_COPY);
                        if (copyBtn == null) {
                            Log.w(TAG, "hookOnItemClick: detail_copy button not found");
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
                                TextView detailTv = frameLayout.findViewById(ID_DETAIL_TEXT);
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
                                        Class<?> fpClass = cl.loadClass(
                                                "androidx.core.content.FileProvider");
                                        Method fpC = fpClass.getMethod("c",
                                                Context.class, String.class);
                                        Object fpAInstance = fpC.invoke(null, ctx,
                                                "com.qtrun.QuickTest.fileprovider");
                                        Method fpB = fpAInstance.getClass().getMethod("b", File.class);
                                        fileUri = (Uri) fpB.invoke(fpAInstance, tmpFile);
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "share: failed to write/get URI: " + e);
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
                        Log.w(TAG, "hookOnItemClick post-processing failed: " + e);
                    }

                    return result;
                }
            });
            Log.i(TAG, "SignalingShareHook: installed");
        } catch (Exception e) {
            Log.e(TAG, "hookOnItemClick failed: " + e);
        }
    }
}
