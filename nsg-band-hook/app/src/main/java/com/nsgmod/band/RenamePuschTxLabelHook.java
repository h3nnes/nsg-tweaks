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
 *   NR-NSA CA Matrix UL: ma0
 *   NR-SA CA Matrix UL:  eb0
 *
 * Hook: AFTER h0(Context) on both fragments. The label is created by the
 * decompiled code as:
 *   bh0 label = this.X.M(8.0f, 1.0f, 0.0f, 27.0f);
 *   label.f = "TxPower";
 *
 * We only change the visible text field (bh0.f) from "TxPower" to "PUSCH TX";
 * the underlying property key is untouched.
 */
public class RenamePuschTxLabelHook {

    private static final String TAG = "NSGBandHook";

    private static final String TARGET_TEXT = "TxPower";
    private static final String RENAMED_TEXT = "PUSCH TX";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    // yg0.X field — r31 builder on the fragment
    private Field pageBuilderField;

    // r31.b — ArrayList of grid elements (typed Object)
    private Field builderListField;

    // bh0 label class and its text field f
    private Class<?> labelClass;
    private Field labelTextField;

    private boolean ready = false;

    public RenamePuschTxLabelHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> pageClass = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> builderClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            labelClass = Nsg.load(loader, Nsg.GRID_LABEL);

            pageBuilderField = pageClass.getField("X");

            builderListField = builderClass.getDeclaredField("b");
            builderListField.setAccessible(true);

            labelTextField = labelClass.getField("f");

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
        installForClass(Nsg.PAGE_NSA_CA_MATRIX_UL);
        installForClass(Nsg.PAGE_SA_CA_MATRIX_UL);
        Log.i(TAG, "RenamePuschTxLabelHook: installed");
    }

    private void installForClass(String className) {
        try {
            Class<?> targetClass = Nsg.load(loader, className);
            Method buildMethod = targetClass.getMethod("h0", Context.class);

            xposed.hook(buildMethod).intercept(new Hooker() {
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
        try {
            Object builder = pageBuilderField.get(thiz);
            if (builder == null) {
                Log.w(TAG, "RenamePuschTxLabelHook: " + className + ".X is null after h0(), skipping");
                return;
            }

            ArrayList<?> list = (ArrayList<?>) builderListField.get(builder);
            if (list == null) {
                return;
            }

            for (Object elem : list) {
                if (elem == null || !labelClass.isInstance(elem)) {
                    continue;
                }
                Object textObj = labelTextField.get(elem);
                if (textObj == null) {
                    continue;
                }
                String text = textObj.toString();
                if (TARGET_TEXT.equals(text)) {
                    labelTextField.set(elem, RENAMED_TEXT);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "RenamePuschTxLabelHook: renameLabel failed for " + className + ": " + e);
        }
    }
}
