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
        NrNsaBandwidthColumnHook nrNsaBwHook = new NrNsaBandwidthColumnHook(this, param.getClassLoader());
        nrNsaBwHook.install();
        new BandColumnHook(this, param.getClassLoader()).install();
        new SignalingShareHook(this, param.getClassLoader()).install();
        new ScrollBarHook(this, param.getClassLoader()).install();
        new SignalingSearchHook(this, param.getClassLoader()).install();
        LteBandwidthColumnHook lteBwHook = new LteBandwidthColumnHook(this, param.getClassLoader());
        lteBwHook.install();
        new LteRsrpRowHook(this, param.getClassLoader()).install();
        new EutraRsrpRowHook(this, param.getClassLoader()).install();
        new NrSaRsrpRowHook(this, param.getClassLoader(),
                NrSaCsiSnrRowHook.carrierCountInO0).install();
        new NrSaCsiSnrRowHook(this, param.getClassLoader()).install();
        new NrSaModUsageRowHook(this, param.getClassLoader(),
                NrSaCsiSnrRowHook.carrierCountInO0).install();
        new NrSaCellColumnsHook(this, param.getClassLoader()).install();
        new NrSaPucchTxRowHook(this, param.getClassLoader()).install();
        new NrSaMimoFormatHook(this, param.getClassLoader()).install();
        new NrSaBwpIdHook(this, param.getClassLoader()).install();
        new NrSaCellColorHook(this, param.getClassLoader(),
                NrSaCsiSnrRowHook.saK2aCarriers).install();
        new ReplayStatusBarHook(this, param.getClassLoader()).install();
        new GranularSeekBarHook(this, param.getClassLoader()).install();
        new RtPlayHook(this, param.getClassLoader()).install();
        new CellIdMatchHook(this, param.getClassLoader()).install();
        new NrNsaExtCellsHook(this, param.getClassLoader()).install();
        new NrSaCarrierCountHook(this, param.getClassLoader(),
                NrSaCsiSnrRowHook.carrierCountInO0).install();
        new SettingsToggleHook(this, param.getClassLoader()).install();
    }
}
