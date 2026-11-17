package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Fixes the green carrier-count indicator on the NR-SA CA Matrix DL page (db0).
 *
 * The indicator at row 2 col 0 is bound to
 *   NR5G::Dedicated_Radio_Link::NR_CarrierCount
 * which has no data in NR-SA mode, causing a green "-" display.
 *
 * LTE CA Matrix works because LTE_ServingCarrier_Num actually exists in modem data.
 *
 * v4.8.14 targets:
 *   yg0  grid page fragment: static g0(r31) stores the builder
 *   r31  grid builder; b = ArrayList of grid elements
 *   dh0  multi-attribute value cell: f = ArrayList of l8 bindings, g = display string,
 *        a(long, DataSource, short) = per-tick update
 *   l8   attribute binding: a = key, c = index, d = Property
 *   m8   attribute binding subclass (allocated via Unsafe, constructor stripped)
 *   Workspace.j singleton, .a tick (short), .f(l8, int) → true if the attribute is live
 *
 * Strategy:
 *   1. Hook yg0.g0(r31) to find and track the carrier-count dh0 element
 *   2. Hook dh0.a(long, DataSource, short) — the per-tick update method.
 *      When the tracked element shows "-", compute the carrier count from
 *      SCell ARFCN/PCI array lengths (same logic as db0.j0()) and override.
 */
public class NrSaCarrierCountHook {

    private static final String TAG = "NSGBandHook";
    private static final String CARRIER_COUNT_KEY =
            "NR5G::Dedicated_Radio_Link::NR_CarrierCount";
    private static final String SCELL_ARFCN_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB";
    private static final String SCELL_PCI_KEY =
            "NR5G::Serving_Cell::SCell::NR_SCell_PCI";

    static final Map<Object, Boolean> trackedElements =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final ThreadLocal<Integer> carrierCountInO0;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private static final String ATTR_L8 = "l8";
    private static final String ATTR_M8 = "m8";

    private Field vgBindingsField; // dh0.f: bindings
    private Field vgTextField; // dh0.g: display string

    private Field sysAKeyField;
    private Field sysAPropField;

    private Object workspaceInstance;
    private Field workspaceTickField;
    private Method workspaceHMethod;

    private Method propertyBMethod;
    private Method iteratorEndMethod;
    private Method iteratorValueMethod;

    private Object scellArfcnBinding;
    private Object scellPciBinding;

    private Class<?> sysBClass;
    private Class<?> vgClass;
    private Object unsafe;
    private Method unsafeAllocateInstance;
    private Method unsafePutObject;
    private Method unsafeObjectFieldOffset;

    private Field sysACField;

    private Field k2aListField; // r31.b: ArrayList of grid elements

    private boolean ready = false;

    public NrSaCarrierCountHook(XposedInterface xposed, ClassLoader loader,
                                ThreadLocal<Integer> carrierCountInO0) {
        this.xposed = xposed;
        this.loader = loader;
        this.carrierCountInO0 = carrierCountInO0;
        initReflection();
    }

    private void initReflection() {
        try {
            vgClass = Nsg.load(loader, Nsg.GRID_MULTI);
            vgBindingsField = vgClass.getDeclaredField("f");
            vgBindingsField.setAccessible(true);
            vgTextField = vgClass.getDeclaredField("g");
            vgTextField.setAccessible(true);

            Class<?> sysAClass = Nsg.load(loader, ATTR_L8);
            sysAKeyField = sysAClass.getDeclaredField("a");
            sysAKeyField.setAccessible(true);
            sysAPropField = sysAClass.getDeclaredField("d");
            sysAPropField.setAccessible(true);
            sysACField = sysAClass.getDeclaredField("c");
            sysACField.setAccessible(true);

            Class<?> workspaceClass = Nsg.load(loader, Nsg.WORKSPACE);
            Field wjField = workspaceClass.getDeclaredField("j"); // Workspace.j: singleton
            wjField.setAccessible(true);
            workspaceInstance = wjField.get(null);
            workspaceTickField = workspaceClass.getDeclaredField("a"); // Workspace.a: tick (short)
            workspaceTickField.setAccessible(true);
            workspaceHMethod = workspaceClass.getMethod("f", sysAClass, int.class); // Workspace.f: attribute live?

            Class<?> propertyClass = Nsg.load(loader, Nsg.PROPERTY);
            propertyBMethod = propertyClass.getMethod("b", long.class);

            Class<?> iteratorClass = Nsg.load(loader, Nsg.PROPERTY_ITER);
            iteratorEndMethod = iteratorClass.getMethod("end");
            iteratorValueMethod = iteratorClass.getMethod("value");

            sysBClass = Nsg.load(loader, ATTR_M8);
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field unsafeField;
            try {
                unsafeField = unsafeClass.getDeclaredField("THE_ONE");
            } catch (NoSuchFieldException e2) {
                unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            }
            unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null);
            unsafeAllocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            unsafePutObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            unsafeObjectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);

            long keyOffset = (long) unsafeObjectFieldOffset.invoke(unsafe, sysAKeyField);
            scellArfcnBinding = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            unsafePutObject.invoke(unsafe, scellArfcnBinding, keyOffset, SCELL_ARFCN_KEY);
            sysACField.set(scellArfcnBinding, -1);
            scellPciBinding = unsafeAllocateInstance.invoke(unsafe, sysBClass);
            unsafePutObject.invoke(unsafe, scellPciBinding, keyOffset, SCELL_PCI_KEY);
            sysACField.set(scellPciBinding, -1);

            Class<?> k2aClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            k2aListField = k2aClass.getDeclaredField("b");
            k2aListField.setAccessible(true);

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "NrSaCarrierCountHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "NrSaCarrierCountHook: skipping install — reflection not ready");
            return;
        }
        installGridPageHook();
        installValueCellHook();
        Log.i(TAG, "NrSaCarrierCountHook: installed");
    }

    private void installGridPageHook() {
        try {
            Class<?> v6bClass = Nsg.load(loader, Nsg.GRID_PAGE);
            Class<?> k2aClass = Nsg.load(loader, Nsg.GRID_BUILDER);
            Method k0Method = v6bClass.getDeclaredMethod("g0", k2aClass); // yg0.g0(r31)

            xposed.hook(k0Method).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object k2aArg = chain.getArg(0);
                    Integer carriers = carrierCountInO0.get();
                    if (carriers != null && k2aArg != null) {
                        findAndTrackCarrierCountElement(k2aArg);
                    }
                    return chain.proceed();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCarrierCountHook: yg0.g0 hook failed: " + e);
        }
    }

    private void findAndTrackCarrierCountElement(Object k2aObj) {
        try {
            java.util.ArrayList<?> list =
                    (java.util.ArrayList<?>) k2aListField.get(k2aObj);
            if (list == null) return;

            for (Object elem : list) {
                if (!vgClass.isInstance(elem)) continue;

                java.util.ArrayList<?> bindings =
                        (java.util.ArrayList<?>) vgBindingsField.get(elem);
                if (bindings == null) continue;

                for (Object binding : bindings) {
                    String key = (String) sysAKeyField.get(binding);
                    if (CARRIER_COUNT_KEY.equals(key)) {
                        trackedElements.put(elem, Boolean.TRUE);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "NrSaCarrierCountHook: findAndTrack failed: " + e);
        }
    }

    private void installValueCellHook() {
        try {
            Class<?> vgClass = Nsg.load(loader, Nsg.GRID_MULTI);
            Class<?> dataSourceClass = Nsg.load(loader, Nsg.DATA_SOURCE);
            Method aMethod = vgClass.getMethod("a", long.class, dataSourceClass, short.class); // dh0.a: per-tick update

            xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();

                    if (!trackedElements.containsKey(chain.getThisObject())) {
                        return result;
                    }

                    String displayStr = (String) vgTextField.get(chain.getThisObject());
                    if (!"-".equals(displayStr)) {
                        return result;
                    }

                    long timestamp = (long) chain.getArg(0);
                    short tick = workspaceTickField.getShort(workspaceInstance);

                    int carriers = computeCarrierCount(timestamp, tick);
                    if (carriers > 0) {
                        vgTextField.set(chain.getThisObject(),
                                String.format("%d carrier(s)", carriers));
                        return Boolean.TRUE;
                    }

                    return result;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "NrSaCarrierCountHook: dh0.a hook failed: " + e);
        }
    }

    private int computeCarrierCount(long timestamp, short tick) {
        try {
            int arfcnLen = readArrayLength(scellArfcnBinding, timestamp, tick);
            int pciLen = readArrayLength(scellPciBinding, timestamp, tick);

            // When no SCells are active, the SCell ARFCN/PCI signals are not live
            // in the Workspace and both reads return -1. In that case there are 0 SCells.
            if (arfcnLen == -1 && pciLen == -1) {
                return 1; // PCell only
            }

            // At least one signal is live — take the max valid length
            int scellCount = Math.max(Math.max(arfcnLen, 0), Math.max(pciLen, 0));
            return scellCount + 1;
        } catch (Exception e) {
            Log.w(TAG, "NrSaCarrierCountHook: computeCarrierCount failed: " + e);
            return -1;
        }
    }

    private int readArrayLength(Object binding, long timestamp, short tick) {
        try {
            boolean hasData = (boolean) workspaceHMethod.invoke(
                    workspaceInstance, binding, (int) tick);
            if (!hasData) {
                return -1;
            }

            Object prop = sysAPropField.get(binding);
            if (prop == null) return -1;

            Object iter = propertyBMethod.invoke(prop, timestamp);
            if ((boolean) iteratorEndMethod.invoke(iter)) {
                return 0;
            }

            Object value = iteratorValueMethod.invoke(iter);
            if (value instanceof Object[]) {
                return ((Object[]) value).length;
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
