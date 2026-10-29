package com.txf.fluidwall.core

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ScopeRestartState(
    val running: Boolean = false,
    val message: String = "点击下方按钮申请 Root，重启作用域并返回桌面。",
)

/** Application lifetime keeps a pending Root request single-flight across navigation/recreation. */
internal object ScopeRestarter {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ScopeRestartState())
    val state = mutableState.asStateFlow()

    fun restart() {
        if (!busy.compareAndSet(false, true)) return
        mutableState.value = ScopeRestartState(true, "正在申请 Root，请在授权弹窗中允许…")
        worker.launch {
            val message = try {
                val result = RootScopeShell().restart(Process.myUid() / 100_000) {
                    mutableState.value = ScopeRestartState(true, "已获 Root，正在重启系统桌面和壁纸…")
                }
                when {
                    result.homeStarted && "com.miui.miwallpaper" in result.stopped ->
                        "系统桌面已重启，壁纸进程已结束并交由系统重新加载。"
                    result.homeStarted -> "系统桌面已重启；壁纸进程当前未运行。"
                    result.stopped.isNotEmpty() -> "已结束作用域进程，系统将在需要时重新加载。"
                    else -> "未找到运行中的作用域；当前设备可能未安装系统桌面或壁纸。"
                }
            } catch (e: RootRestartException) {
                when (e.reason) {
                    RootRestartFailure.UNAVAILABLE -> "未找到可用的 Root，请先安装并启用 Root 管理器。"
                    RootRestartFailure.DENIED -> "未获得 Root 权限，请在 Root 管理器中允许 FluidWall 后重试。"
                    RootRestartFailure.TIMEOUT -> "Root 授权或重启超时，请检查 Root 管理器后重试。"
                    RootRestartFailure.COMMAND -> "部分作用域未能重启，请重试或手动重启手机。"
                }
            } catch (e: Exception) {
                XLog.i("scope restart failed: $e")
                "重启未完成，请检查 Root 授权后重试。"
            }
            XLog.i("scope restart: $message")
            mutableState.value = ScopeRestartState(false, message)
            busy.set(false)
        }
    }
}
