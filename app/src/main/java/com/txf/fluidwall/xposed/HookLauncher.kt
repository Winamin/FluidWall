package com.txf.fluidwall.xposed

import android.app.Activity
import android.content.pm.ApplicationInfo
import com.txf.fluidwall.core.LauncherLayer
import com.txf.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object HookLauncher {

    private var installed = false

    private val animationClasses = arrayOf(
        "com.miui.home.launcher.compat.UserPresentAnimationCompatPhone",
        "com.miui.home.launcher.compat.UserPresentAnimationCompatFold",
        "com.miui.home.launcher.compat.UserPresentAnimationCompatComplex",
        "com.miui.home.launcher.compat.UserPresentAnimationCompat",
        "com.miui.home.launcher.compat.UserPresentAnimationCompatBase",
    )

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        HookUtil.appInit { ctx ->
            if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_HAS_CODE == 0) {
                XLog.i("native launcher: transition is handled by the wallpaper process")
                return@appInit
            }
            installJavaLauncher(cl)
        }
    }

    private fun installJavaLauncher(cl: ClassLoader) {
        val cls = HookUtil.findClass(
            cl,
            "com.miui.home.launcher.BaseLauncher",
            "com.miui.home.launcher.Launcher",
            "com.android.launcher3.Launcher",
        ) ?: run {
            XLog.i("launcher activity class not found")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(cls, "onResume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    LauncherLayer.onResume(param.thisObject as? Activity)
                }
            })
            XposedHelpers.findAndHookMethod(cls, "onPause", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    LauncherLayer.onPause()
                }
            })
            XposedHelpers.findAndHookMethod(
                cls,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        LauncherLayer.onWindowFocus(param.args[0] as? Boolean ?: return)
                    }
                },
            )
            XLog.i("launcher hooks on ${cls.name}")
        } catch (t: Throwable) {
            XLog.i("launcher hook failed: $t")
        }
        installUnlockAnim(cl)
    }

    /**
     * The launcher owns a small state machine and a set of "user present" animations that start
     * the icon reveal while the keyguard is still leaving. Hooking them gives an unlock
     * timestamp that is early enough to run the wallpaper mask together with the system
     * animation, which no broadcast or keyguard callback can deliver.
     */
    private fun installUnlockAnim(cl: ClassLoader) {
        val sm = HookUtil.findClass(cl, "com.miui.home.launcher.common.UnlockAnimationStateMachine")
        if (sm != null) {
            hookTrigger(sm, "onUserPresent", "anim_user_present")
            hookTrace(sm, "setState")
            hookTrace(sm, "setPrepare")
        } else {
            XLog.i("unlock state machine not found")
        }
        var hooks = 0
        for (name in animationClasses) {
            val c = try {
                XposedHelpers.findClassIfExists(name, cl)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (hookTrigger(c, "showAnimation", "anim_show")) hooks++
        }
        XLog.i("unlock animation classes hooked=$hooks")
    }

    private fun hookTrigger(c: Class<*>, method: String, source: String): Boolean {
        val targets = c.declaredMethods.filter {
            it.name == method && !java.lang.reflect.Modifier.isAbstract(it.modifiers)
        }
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                XLog.i("system unlock anim: $source")
                LauncherLayer.onSystemUnlockAnim(source)
            }
        }
        var hooked = false
        for (target in targets) {
            try {
                XposedBridge.hookMethod(target, callback)
                hooked = true
            } catch (t: Throwable) {
                XLog.i("hook $target failed: $t")
            }
        }
        if (!hooked) return false
        XLog.i("hooked ${c.simpleName}#$method")
        return true
    }

    private fun hookTrace(c: Class<*>, method: String) {
        try {
            XposedBridge.hookAllMethods(c, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XLog.i("trace ${c.simpleName}#$method")
                }
            })
        } catch (t: Throwable) {
            XLog.i("trace hook $method failed: $t")
        }
    }
}
