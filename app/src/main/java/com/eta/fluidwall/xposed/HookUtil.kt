package com.eta.fluidwall.xposed

import android.app.Application
import android.content.Context
import com.eta.fluidwall.core.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object HookUtil {

    fun findClass(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            val c = try {
                XposedHelpers.findClassIfExists(n, cl)
            } catch (t: Throwable) {
                null
            }
            if (c != null) return c
        }
        return null
    }

    fun appInit(cb: (Context) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        try {
                            cb(app)
                        } catch (t: Throwable) {
                            XLog.i("appInit callback failed: $t")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            XLog.i("hook Application.onCreate failed: $t")
        }
    }
}
