package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

public class LteCaMatrixUlInNsaHook {
    private static final String TAG = "NSGBandHook";
    private static final int RAT_NR_NSA = 8;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    public LteCaMatrixUlInNsaHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        installCacheHook();
        installReturnHook();
    }

    private void installCacheHook() {
        try {
            Class<?> pageListManagerClass = ClassMapping.loadClass("ts", loader);
            if (pageListManagerClass == null) {
                Log.w(TAG, "LteCaMatrixUlInNsaHook: page list manager class not found");
                return;
            }
            String methodName = ClassMapping.runtimeMethodName("ts", "m", loader);
            Method method = pageListManagerClass.getDeclaredMethod(methodName, int.class, ArrayList.class);

            xposed.hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    try {
                        int ratMode = (int) chain.getArg(0);
                        if (ratMode == RAT_NR_NSA) {
                            @SuppressWarnings("unchecked")
                            ArrayList<Object> pageList = (ArrayList<Object>) chain.getArg(1);
                            if (pageList != null) {
                                injectLteCaMatrixUl(pageList);
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "LteCaMatrixUlInNsaHook: cache inject failed: " + t);
                    }
                    return chain.proceed();
                }
            });
            Log.i(TAG, "LteCaMatrixUlInNsaHook: cache hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "LteCaMatrixUlInNsaHook: cache hook install failed: " + t);
        }
    }

    private void installReturnHook() {
        try {
            Class<?> pageListManagerClass = ClassMapping.loadClass("ts", loader);
            if (pageListManagerClass == null) {
                return;
            }
            String methodName = ClassMapping.runtimeMethodName("ts", "g", loader);
            Method method = pageListManagerClass.getDeclaredMethod(methodName, int.class);

            xposed.hook(method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    @SuppressWarnings("unchecked")
                    ArrayList<Object> result = (ArrayList<Object>) chain.proceed();
                    try {
                        int ratMode = (int) chain.getArg(0);
                        if (ratMode == RAT_NR_NSA) {
                            injectLteCaMatrixUl(result);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "LteCaMatrixUlInNsaHook: return inject failed: " + t);
                    }
                    return result;
                }
            });
            Log.i(TAG, "LteCaMatrixUlInNsaHook: return hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "LteCaMatrixUlInNsaHook: return hook install failed: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private void injectLteCaMatrixUl(ArrayList<Object> pageList) throws Throwable {
        Class<?> lteCaMatrixUlClass = ClassMapping.loadClass("e8.a", loader);
        if (lteCaMatrixUlClass == null) {
            Log.w(TAG, "LteCaMatrixUlInNsaHook: LTE CA Matrix UL class not found");
            return;
        }

        for (Object page : pageList) {
            if (lteCaMatrixUlClass.isInstance(page)) {
                return;
            }
        }

        Class<?> eutraCaMatrixClass = ClassMapping.loadClass("g8.i", loader);
        int insertIndex = -1;
        if (eutraCaMatrixClass != null) {
            for (int i = 0; i < pageList.size(); i++) {
                if (eutraCaMatrixClass.isInstance(pageList.get(i))) {
                    insertIndex = i + 1;
                    break;
                }
            }
        }

        Object lteCaMatrixUlPage = lteCaMatrixUlClass.getDeclaredConstructor().newInstance();

        if (insertIndex >= 0 && insertIndex <= pageList.size()) {
            pageList.add(insertIndex, lteCaMatrixUlPage);
        } else {
            pageList.add(lteCaMatrixUlPage);
        }
    }
}
