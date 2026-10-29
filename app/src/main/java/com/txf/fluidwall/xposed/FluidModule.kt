package com.txf.fluidwall.xposed

import com.txf.fluidwall.core.XLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Only two processes are hooked:
 *  - com.miui.home        draws the transition inside the wallpaper layer
 *  - com.miui.miwallpaper exports pixels and renders the transition for native OS4 desktops
 * SystemUI is deliberately out of scope; all overlay playback there was removed.
 */
class FluidModule : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.i("module loaded in ${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName ?: return
        val cl = param.classLoader ?: return
        when (pkg) {
            "com.miui.home", "com.android.launcher3", "com.android.launcher" ->
                HookLauncher.install(cl)
            "com.android.wallpaper", "com.miui.wallpaper", "com.miui.miwallpaper" ->
                HookWallpaper.install(cl)
            else -> return
        }
        XLog.i("hooks installed in $pkg")
    }
}
