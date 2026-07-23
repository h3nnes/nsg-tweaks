package com.nsgmod.band;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Renames the "TxPower" row label to "PUSCH TX" on the NR-NSA and NR-SA
 * CA Matrix UL pages, aligning it with the injected PUCCH TX row.
 *
 * Affected fragments:
 *   NR-NSA CA Matrix UL: g8.b
 *   NR-SA CA Matrix UL:  h8.c
 *
 * Hook: AFTER l0(Context) on both fragments. The label is created by the
 * decompiled code as:
 *   v6.e eVarR10 = this.Y.r(8.0f, 1.0f, 0.0f, 27.0f);
 *   eVarR10.f8116f = "TxPower";
 *
 * We only change the visible text field (CharSequence f / f8116f) from
 * "TxPower" to "PUSCH TX"; the underlying property key is untouched.
 */
public class RenamePuschTxLabelHook {

    private static final String TAG = "NSGBandHook";

    private static final String TARGET_TEXT = "TxPower";
    private static final String RENAMED_TEXT = "PUSCH TX";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // v6.b.Y field — k2.a builder on the fragment
    private Field v6bYField;

    // k2.a internal ArrayList field d
    private Field k2aListField;

    // v6.e text field f (CharSequence)
    private Field veTextField;

    // v6.e class (to identify list entries without repeatedly resolving names)
    private Class<?> veClass;

    private boolean ready = false;

    public RenamePuschTxLabelHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> v6bClass = ClassMapping.loadClass("v6.b", loader);
            Class<?> k2aClass = ClassMapping.loadClass("k2.a", loader);
            veClass = ClassMapping.loadClass("v6.e", loader);

            v6bYField = v6bClass.getField("Y");

            k2aListField = k2aClass.getDeclaredField("d");
            k2aListField.setAccessible(true);

            veTextField = veClass.getField("f");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "RenamePuschTxLabelHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "RenamePuschTxLabelHook: skipping install — reflection not ready");
            return;
        }
        installForClass("g8.b"); // NR-NSA CA Matrix UL
        installForClass("h8.c"); // NR-SA CA Matrix UL
        Log.i(TAG, "RenamePuschTxLabelHook: installed");
    }

    private void installForClass(String className) {
        try {
            Class<?> targetClass = ClassMapping.loadClass(className, loader);
            if (targetClass == null) {
                Log.i(TAG, "RenamePuschTxLabelHook: " + className
                        + " not available on this flavor, skipping");
                return;
            }
            Method l0Method = ClassMapping.getMethod(targetClass, className, "l0", loader, Context.class);

            xposed.hook(l0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Object thiz = chain.getThisObject();
                    if (thiz != null) {
                        renameLabel(thiz, className);
                    }
                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "RenamePuschTxLabelHook: failed to hook " + className + ": " + e);
        }
    }

    private void renameLabel(Object thiz, String className) {
        int renamedCount = 0;
        try {
            Object k2aObj = v6bYField.get(thiz);
            if (k2aObj == null) {
                Log.w(TAG, "RenamePuschTxLabelHook: " + className + ".Y is null after l0(), skipping");
                return;
            }

            ArrayList<?> list = (ArrayList<?>) k2aListField.get(k2aObj);
            if (list == null) {
                return;
            }

            for (Object elem : list) {
                if (elem == null || !veClass.isInstance(elem)) {
                    continue;
                }
                Object textObj = veTextField.get(elem);
                if (textObj == null) {
                    continue;
                }
                String text = textObj.toString();
                if (TARGET_TEXT.equals(text)) {
                    veTextField.set(elem, RENAMED_TEXT);
                    renamedCount++;
                }
            }


        } catch (Exception e) {
            Log.w(TAG, "RenamePuschTxLabelHook: renameLabel failed for " + className + ": " + e);
        }
    }
}
