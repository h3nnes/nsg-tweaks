package com.nsgmod.band;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class MainHook extends XposedModule {

    private static final String TAG = "NSGBandHook";
    private static final String TARGET_PACKAGE = "com.qtrun.QuickTest";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Log.i(TAG, "module loaded in process: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        Log.i(TAG, "hooking " + TARGET_PACKAGE);
        BandColumnHook hook = new BandColumnHook(this, param.getClassLoader());
        hook.install();
        SignalingShareHook shareHook = new SignalingShareHook(this, param.getClassLoader());
        shareHook.install();
        ScrollBarHook scrollBarHook = new ScrollBarHook(this, param.getClassLoader());
        scrollBarHook.install();
    }
}
