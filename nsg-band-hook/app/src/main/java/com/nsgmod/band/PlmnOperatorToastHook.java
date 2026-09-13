package com.nsgmod.band;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

public class PlmnOperatorToastHook {

    private static final String TAG = "NSGBandHook";
    private static final String CGI_FRAGMENT = "com.qtrun.udv.header.HeaderCGIFragment";

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private Field plmnValueField;
    private Field viewField;
    private Field wsSingletonField;
    private Field wsModuleSlotField;

    private Object attachedView;

    private volatile boolean replayResolved = false;
    private Method testServiceGetInstance;
    private Method replayActiveMethod;

    public PlmnOperatorToastHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
    }

    public void install() {
        try {
            Class<?> cgiClass = ClassMapping.loadClass(CGI_FRAGMENT, loader);
            plmnValueField = cgiClass.getDeclaredField(
                    ClassMapping.runtimeFieldName(CGI_FRAGMENT, "plmnValue", loader));
            plmnValueField.setAccessible(true);

            Class<?> cellBaseClass = ClassMapping.loadClass("v6.a", loader);
            viewField = cellBaseClass.getDeclaredField("a");
            viewField.setAccessible(true);

            Class<?> wsClass = ClassMapping.loadClass("com.qtrun.sys.Workspace", loader);
            wsSingletonField = wsClass.getField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "j", loader));
            wsModuleSlotField = wsClass.getDeclaredField(
                    ClassMapping.runtimeFieldName("com.qtrun.sys.Workspace", "a", loader));
            wsModuleSlotField.setAccessible(true);

            Class<?> dsClass = ClassMapping.loadClass("com.qtrun.sys.DataSource", loader);
            Method bMethod = ClassMapping.getDeclaredMethod(cgiClass, CGI_FRAGMENT, "b", loader,
                    dsClass, long.class, short.class, Object.class);
            bMethod.setAccessible(true);

            xposed.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    try {
                        attachListener(chain.getThisObject());
                    } catch (Throwable t) {
                        Log.e(TAG, "PlmnOperatorToastHook attach error", t);
                    }
                    return result;
                }
            });

            Log.i(TAG, "PlmnOperatorToastHook installed");
        } catch (Throwable t) {
            Log.e(TAG, "PlmnOperatorToastHook install failed: " + t);
        }
    }

    private void attachListener(Object fragment) throws Throwable {
        Object cell = plmnValueField.get(fragment);
        if (cell == null) return;
        Object viewObj = viewField.get(cell);
        if (!(viewObj instanceof View)) return;
        if (viewObj == attachedView) return;
        attachedView = viewObj;
        ((View) viewObj).setOnLongClickListener(v -> {
            try {
                showOperatorToast();
            } catch (Throwable t) {
                Log.e(TAG, "PlmnOperatorToastHook long press error", t);
            }
            return true;
        });
    }

    private void showOperatorToast() {
        if (isReplaying()) return;
        Context ctx = getAppContext();
        if (ctx == null) return;
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;
        int subId = resolveSubId(ctx, getViewedPhysicalSlot());
        String name;
        if (subId >= 0 && Build.VERSION.SDK_INT >= 30) {
            name = tm.createForSubscriptionId(subId).getNetworkOperatorName();
        } else {
            name = tm.getNetworkOperatorName();
        }
        if (name == null || name.isEmpty()) return;
        Toast.makeText(ctx, name, Toast.LENGTH_LONG).show();
    }

    private boolean isReplaying() {
        try {
            if (!replayResolved) {
                Class<?> tsClass = Class.forName("com.qtrun.sys.TestService", false, loader);
                testServiceGetInstance = tsClass.getMethod("o");
                testServiceGetInstance.setAccessible(true);
                Class<?> serviceClass = ClassMapping.loadClass("t7.g0", loader);
                if (serviceClass == null) return false;
                replayActiveMethod = serviceClass.getMethod("y");
                replayActiveMethod.setAccessible(true);
                replayResolved = true;
            }
            Object service = testServiceGetInstance.invoke(null);
            if (service == null) return false;
            return (Boolean) replayActiveMethod.invoke(service);
        } catch (Throwable t) {
            return false;
        }
    }

    private int resolveSubId(Context ctx, int physicalSlot) {
        try {
            SubscriptionManager sm = (SubscriptionManager) ctx.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm != null && physicalSlot >= 0) {
                List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo info : subs) {
                        if (info != null && info.getSimSlotIndex() == physicalSlot) {
                            return info.getSubscriptionId();
                        }
                    }
                }
            }
        } catch (SecurityException se) {
            Log.w(TAG, "PlmnOperatorToastHook: missing permission for active subscriptions");
        } catch (Throwable t) {
            Log.w(TAG, "PlmnOperatorToastHook: subscription lookup failed: " + t);
        }
        try {
            int subId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (subId >= 0) return subId;
        } catch (Throwable ignored) {
        }
        try {
            int subId = SubscriptionManager.getDefaultSubscriptionId();
            if (subId >= 0) return subId;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private int getViewedPhysicalSlot() {
        try {
            if (wsSingletonField == null || wsModuleSlotField == null) return -1;
            Object wsInstance = wsSingletonField.get(null);
            if (wsInstance == null) return -1;
            short moduleShort = wsModuleSlotField.getShort(wsInstance);
            int nsgSlot = moduleShort >> 4;
            int defaultPhysicalSlot = getDefaultDataPhysicalSlot();
            if (defaultPhysicalSlot < 0) return nsgSlot;
            if (nsgSlot == 0) return defaultPhysicalSlot;
            return defaultPhysicalSlot == 0 ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static volatile boolean smResolved = false;
    private static Class<?> smClass;
    private static Method smGetDefaultDataSubId;
    private static Method smGetDefaultSubId;
    private static Method smGetActiveSubInfo;
    private static Method subInfoGetSimSlotIndex;
    private static Object smService;

    private static synchronized void resolveSubscriptionHandles() {
        if (smResolved) return;
        try {
            smClass = Class.forName("android.telephony.SubscriptionManager");
            smGetDefaultDataSubId = smClass.getMethod("getDefaultDataSubscriptionId");
            smGetDefaultSubId     = smClass.getMethod("getDefaultSubscriptionId");
            smGetActiveSubInfo    = smClass.getMethod("getActiveSubscriptionInfo", int.class);
            Context ctx = getAppContext();
            if (ctx == null) return;
            smService = ctx.getSystemService("telephony_subscription_service");
            if (smService == null) return;
            smResolved = true;
        } catch (Throwable ignored) {
        }
    }

    private int getDefaultDataPhysicalSlot() {
        try {
            resolveSubscriptionHandles();
            if (!smResolved) return -1;
            int subId = -1;
            try {
                subId = (Integer) smGetDefaultDataSubId.invoke(null);
            } catch (Throwable ignored) {}
            if (subId < 0) {
                try {
                    subId = (Integer) smGetDefaultSubId.invoke(null);
                } catch (Throwable ignored) {}
            }
            if (subId < 0) return -1;
            Object info = smGetActiveSubInfo.invoke(smService, subId);
            if (info == null) return -1;
            if (subInfoGetSimSlotIndex == null) {
                subInfoGetSimSlotIndex = info.getClass().getMethod("getSimSlotIndex");
            }
            return (Integer) subInfoGetSimSlotIndex.invoke(info);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static volatile Context appContext;

    private static Context getAppContext() {
        if (appContext != null) return appContext;
        synchronized (PlmnOperatorToastHook.class) {
            if (appContext != null) return appContext;
            try {
                Class<?> atCls = Class.forName("android.app.ActivityThread");
                Context ctx = (Context) atCls.getMethod("currentApplication").invoke(null);
                if (ctx != null) appContext = ctx;
                return ctx;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
