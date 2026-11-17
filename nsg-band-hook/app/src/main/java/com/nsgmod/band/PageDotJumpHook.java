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
 * <p>NSG v4.8.14 targets: com.qtrun.widget.viewpagerindicator.CirclePageIndicator (real
 * name; fields a radius, e ViewPager my0, g current page, k orientation, l centered,
 * q dragging), my0.getAdapter()/setCurrentItem(int), be0.c() (adapter page count).
 *
 * <p>Works for all technologies and adapter types.
 */
public class PageDotJumpHook {
    private static final String TAG = "NSGBandHook";
    private static final String INDICATOR = "com.qtrun.widget.viewpagerindicator.CirclePageIndicator";
    private static final String PAGER_ADAPTER = "be0";  // abstract PagerAdapter, c() = getCount()
    private static final long LONG_PRESS_MS = 300L;

    private final XposedInterface xposed;
    private final ClassLoader loader;
    private final WeakHashMap<View, Long> downTimes = new WeakHashMap<>();
    /** Timestamp of the most recent short-tap page jump per indicator view. */
    private final WeakHashMap<View, Long> recentShortTaps = new WeakHashMap<>();

    // reflection fields on CirclePageIndicator
    private Field viewPagerField;    // e: my0 (ViewPager)
    private Field currentPageField;  // g: int
    private Field radiusField;       // a: float
    private Field draggingField;     // q: boolean
    private Field orientationField;  // k: int (0 = horizontal)
    private Field centeredField;     // l: boolean

    // ViewPager / adapter methods
    private Method setCurrentItemMethod;  // my0.setCurrentItem(int)
    private Method getAdapterMethod;      // my0.getAdapter() -> be0
    private Method getCountMethod;        // be0.c(): page count

    private boolean reflectionReady = false;

    public PageDotJumpHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> indicatorClass = Nsg.load(loader, INDICATOR);
            Class<?> adapterClass = Nsg.load(loader, PAGER_ADAPTER);
            if (indicatorClass == null || adapterClass == null) {
                Log.i(TAG, "PageDotJumpHook: CirclePageIndicator or be0 not available, skipping");
                return;
            }

            viewPagerField = field(indicatorClass, "e");
            currentPageField = field(indicatorClass, "g");
            radiusField = field(indicatorClass, "a");
            draggingField = field(indicatorClass, "q");
            orientationField = field(indicatorClass, "k");
            centeredField = field(indicatorClass, "l");

            Class<?> viewPagerClass = viewPagerField.getType();
            getAdapterMethod = viewPagerClass.getMethod("getAdapter");
            setCurrentItemMethod = viewPagerClass.getMethod("setCurrentItem", int.class);
            getCountMethod = adapterClass.getMethod("c");

            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "PageDotJumpHook: initReflection failed: " + e);
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    public void install() {
        if (!reflectionReady) {
            Log.e(TAG, "PageDotJumpHook install skipped — reflection not ready");
            return;
        }
        try {
            Class<?> indicatorClass = Nsg.load(loader, INDICATOR);
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

                        float touchX = event.getX();
                        float radius = (float) radiusField.get(indicator);
                        int currentPage = (int) currentPageField.get(indicator);
                        boolean centered = (boolean) centeredField.get(indicator);

                        int paddingLeft = indicatorView.getPaddingLeft();
                        int paddingRight = indicatorView.getPaddingRight();
                        int width = indicatorView.getWidth();

                        int count = (int) getCountMethod.invoke(adapter); // be0.c()

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
                final Class<?> indicatorCls = indicatorClass;
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
                        return chain.proceed(new Object[]{wrappedListener});
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
