package com.nsgmod.band;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Hooks CirclePageIndicator.onTouchEvent to allow tapping on specific bottom dots
 * to jump directly to a page, instead of just swiping prev/next.
 *
 * Works for all technologies and adapter types.
 */
public class PageDotJumpHook {
    private static final String TAG = "NSGBandHook";
    private static final long LONG_PRESS_MS = 300L;

    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final WeakHashMap<View, Long> downTimes = new WeakHashMap<>();
    /** Timestamp of the most recent short-tap page jump per indicator view. */
    private final WeakHashMap<View, Long> recentShortTaps = new WeakHashMap<>();

    // reflection fields on CirclePageIndicator
    private Field viewPagerField;    // e
    private Field currentPageField;  // g
    private Field radiusField;       // a
    private Field draggingField;     // q
    private Field orientationField;  // k
    private Field centeredField;     // l

    // ViewPager / adapter methods
    private Method setCurrentItemMethod;
    private Method getAdapterMethod;
    private Method getCountMethod;
    private Field adapterTechField;  // h on x6.b (optional, for logging only)

    private boolean reflectionReady = false;

    public PageDotJumpHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> indicatorClass = loader.loadClass(
                    "com.qtrun.widget.viewpagerindicator.CirclePageIndicator");

            for (Field f : indicatorClass.getDeclaredFields()) {
                f.setAccessible(true);
                String name = f.getName();
                Class<?> type = f.getType();

                if ("e".equals(name)) {
                    viewPagerField = f;
                } else if ("g".equals(name) && type == int.class) {
                    currentPageField = f;
                } else if ("a".equals(name) && type == float.class) {
                    radiusField = f;
                } else if ("q".equals(name) && type == boolean.class) {
                    draggingField = f;
                } else if ("k".equals(name) && type == int.class) {
                    orientationField = f;
                } else if ("l".equals(name) && type == boolean.class) {
                    centeredField = f;
                }
            }

            if (viewPagerField != null) {
                Class<?> viewPagerClass = viewPagerField.getType();
                getAdapterMethod = viewPagerClass.getMethod("getAdapter");
                setCurrentItemMethod = viewPagerClass.getMethod("setCurrentItem", int.class);
            }

            // Resolve adapter count method from w1.a (obfuscated: method is "c" not "getCount")
            try {
                Class<?> w1aClass = loader.loadClass("w1.a");
                getCountMethod = w1aClass.getMethod("c");
            } catch (Exception e) {
                Log.w(TAG, "Could not find w1.a.c() count method");
            }

            // Adapter tech field: only x6.b has int h. x6.d has a different type for h.
            // This is optional — used only for debug logging.
            try {
                Class<?> x6bClass = loader.loadClass("x6.b");
                adapterTechField = x6bClass.getDeclaredField("h");
                adapterTechField.setAccessible(true);
            } catch (Exception e) {
                Log.w(TAG, "Could not find adapter tech field h in x6.b");
            }

            reflectionReady = viewPagerField != null
                    && currentPageField != null
                    && radiusField != null
                    && draggingField != null
                    && orientationField != null
                    && centeredField != null
                    && setCurrentItemMethod != null
                    && getAdapterMethod != null;

            if (!reflectionReady) {
                Log.w(TAG, "PageDotJumpHook: reflection partially failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "PageDotJumpHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "PageDotJumpHook install skipped — reflection not ready");
            return;
        }
        try {
            Class<?> indicatorClass = loader.loadClass(
                    "com.qtrun.widget.viewpagerindicator.CirclePageIndicator");
            Method onTouchEventMethod = indicatorClass.getDeclaredMethod(
                    "onTouchEvent", MotionEvent.class);

            xposed.hook(onTouchEventMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object indicator = chain.getThisObject();
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    View indicatorView = (View) indicator;
                    int action = event.getActionMasked();

                    if (action == MotionEvent.ACTION_DOWN) {
                        downTimes.put(indicatorView, System.currentTimeMillis());
                        return chain.proceed();
                    }

                    if (action != MotionEvent.ACTION_UP) {
                        return chain.proceed();
                    }

                    try {
                        Long downTime = downTimes.remove(indicatorView);
                        if (downTime == null) {
                            return chain.proceed();
                        }

                        long elapsed = System.currentTimeMillis() - downTime;
                        if (elapsed >= LONG_PRESS_MS) {
                            return chain.proceed();
                        }

                        boolean dragging = (boolean) draggingField.get(indicator);
                        if (dragging) {
                            return chain.proceed();
                        }

                        int orientation = (int) orientationField.get(indicator);
                        if (orientation != 0) {
                            return chain.proceed();
                        }

                        Object viewPager = viewPagerField.get(indicator);
                        if (viewPager == null) {
                            return chain.proceed();
                        }

                        Object adapter = getAdapterMethod.invoke(viewPager);
                        if (adapter == null) {
                            return chain.proceed();
                        }

                        // Read tech for logging only, don't block on it
                        try {
                            adapterTechField.getInt(adapter);
                        } catch (Exception e) {
                            // NormalActivity (x6.d) doesn't have int h field — that's fine
                        }

                        float touchX = event.getX();
                        float radius = (float) radiusField.get(indicator);
                        int currentPage = (int) currentPageField.get(indicator);
                        boolean centered = (boolean) centeredField.get(indicator);

                        int paddingLeft = indicatorView.getPaddingLeft();
                        int paddingRight = indicatorView.getPaddingRight();
                        int width = indicatorView.getWidth();

                        // Get adapter count with fallback
                        int count = 0;
                        if (getCountMethod != null) {
                            try {
                                count = (int) getCountMethod.invoke(adapter);
                            } catch (Exception e) {
                                // try dynamic lookup below
                            }
                        }
                        if (count <= 0) {
                            try {
                                Method dynamicCount = adapter.getClass().getMethod("c");
                                count = (int) dynamicCount.invoke(adapter);
                            } catch (Exception e1) {
                                try {
                                    Method getCount = adapter.getClass().getMethod("getCount");
                                    count = (int) getCount.invoke(adapter);
                                } catch (Exception e2) {
                                    Log.w(TAG, "Could not get adapter count: " + e2);
                                    return chain.proceed();
                                }
                            }
                        }

                        if (count <= 0) {
                            return chain.proceed();
                        }

                        float centeringOffset = 0;
                        if (centered) {
                            centeringOffset = ((width - paddingLeft - paddingRight) / 2f)
                                    - (count * 3 * radius / 2f);
                        }

                        float dotIndexF = (touchX - paddingLeft - radius - centeringOffset)
                                / (3 * radius);
                        int dotIndex = Math.round(dotIndexF);

                        if (dotIndex < 0) dotIndex = 0;
                        if (dotIndex >= count) dotIndex = count - 1;

                        if (dotIndex != currentPage) {
                            setCurrentItemMethod.invoke(viewPager, dotIndex);
                        }

                        // Record this short tap so we can suppress the spurious
                        // long-click that Android posts during ACTION_DOWN.
                        recentShortTaps.put(indicatorView, System.currentTimeMillis());

                        // Consume the event (prevent original prev/next logic).
                        return true;
                    } catch (Exception e) {
                        Log.w(TAG, "PageDotJumpHook intercept failed: " + e);
                        return chain.proceed();
                    }
                }
            });

            // ---- Guard against spurious long-clicks after short tap jumps ----
            // NSG sets an OnLongClickListener on the CirclePageIndicator that opens
            // the reorder dialog. When we consume ACTION_UP after a short tap,
            // Android's framework CheckForLongPress runnable still fires ~400ms
            // later and triggers the listener.
            //
            // Fix: intercept setOnLongClickListener calls on CirclePageIndicator
            // instances and wrap the listener with a guard that skips the call
            // if a short tap was recorded recently.
            try {
                Class<?> indicatorCls = loader.loadClass(
                        "com.qtrun.widget.viewpagerindicator.CirclePageIndicator");
                Method setOnLongClickListenerMethod = View.class.getDeclaredMethod(
                        "setOnLongClickListener", View.OnLongClickListener.class);
                setOnLongClickListenerMethod.setAccessible(true);

                xposed.hook(setOnLongClickListenerMethod).intercept(new Hooker() {
                    @Override
                    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                        Object view = chain.getThisObject();
                        if (view == null || !indicatorCls.isInstance(view)) {
                            return chain.proceed();
                        }
                        final View.OnLongClickListener originalListener =
                                (View.OnLongClickListener) chain.getArg(0);
                        if (originalListener == null) {
                            return chain.proceed();
                        }
                        // Wrap the listener with a short-tap guard
                        View.OnLongClickListener wrappedListener = new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                Long tapTime = recentShortTaps.get(v);
                                if (tapTime != null) {
                                    long elapsed = System.currentTimeMillis() - tapTime;
                                    if (elapsed < 500L) {
                                        // Spurious long-click after short tap — swallow it
                                        return true;
                                    }
                                }
                                // Genuine long-click — delegate
                                return originalListener.onLongClick(v);
                            }
                        };
                        // Call the real method with our wrapped listener instead of proceeding
                        setOnLongClickListenerMethod.invoke(view, wrappedListener);
                        return null;
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "setOnLongClickListener guard hook failed: " + e);
            }

            Log.i(TAG, "PageDotJumpHook installed");
        } catch (Exception e) {
            Log.e(TAG, "PageDotJumpHook install failed: " + e);
        }
    }
}
