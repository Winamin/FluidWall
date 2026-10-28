package com.txf.fluidwall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.txf.fluidwall.core.ColorKMeans
import com.txf.fluidwall.core.ConfigStore
import com.txf.fluidwall.core.FluidConfig
import com.txf.fluidwall.core.FluidTransitionView
import com.txf.fluidwall.core.HookStatus
import com.txf.fluidwall.core.LogStore
import com.txf.fluidwall.core.WallpaperBridge
import com.txf.fluidwall.core.WallpaperStore
import com.txf.fluidwall.core.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun FluidWallApp() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(ConfigStore.load(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    val title = when (tab) {
        1 -> "关于"
        2 -> "日志"
        else -> "流体壁纸"
    }

    fun update(next: FluidConfig) {
        cfg = next
        ConfigStore.save(ctx, next)
    }

    MiuixTheme(controller = controller) {
        Scaffold(
            topBar = { TopAppBar(title = title, largeTitle = title) },
            bottomBar = {
                NavigationBar {
                    Item(IconSettings, "设置", tab == 0) { tab = 0 }
                    Item(IconInfo, "关于", tab == 1) { tab = 1 }
                    Item(IconLog, "日志", tab == 2) { tab = 2 }
                }
            },
        ) { padding ->
            when (tab) {
                0 -> SettingsTab(cfg, padding, ::update, onOpenLog = { tab = 2 })
                1 -> AboutTab(padding)
                else -> LogTab(padding)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Item(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = active,
        onClick = onClick,
        icon = icon,
        label = label,
    )
}

private val IconSettings: ImageVector
    get() = materialIcon("settings") {
        path(strokeWidth = 1.9f) {
            moveTo(12f, 8.2f)
            arcToRelative(3.8f, 3.8f, 0f, true, false, 0f, 7.6f)
            arcToRelative(3.8f, 3.8f, 0f, true, false, 0f, -7.6f)
        }
        path(strokeWidth = 1.9f) {
            moveTo(12f, 2.9f)
            verticalLineTo(5.1f)
            moveTo(12f, 18.9f)
            verticalLineTo(21.1f)
            moveTo(5.6f, 5.6f)
            lineTo(7.1f, 7.1f)
            moveTo(16.9f, 16.9f)
            lineTo(18.4f, 18.4f)
            moveTo(2.9f, 12f)
            horizontalLineTo(5.1f)
            moveTo(18.9f, 12f)
            horizontalLineTo(21.1f)
        }
    }

private val IconInfo: ImageVector
    get() = materialIcon("info") {
        path(strokeWidth = 1.9f) {
            moveTo(12f, 3.8f)
            arcToRelative(8.2f, 8.2f, 0f, true, false, 0f, 16.4f)
            arcToRelative(8.2f, 8.2f, 0f, true, false, 0f, -16.4f)
        }
        path(strokeWidth = 1.9f) {
            moveTo(12f, 11f)
            verticalLineTo(16.6f)
            moveTo(12f, 7.4f)
            verticalLineTo(7.9f)
        }
    }

private val IconLog: ImageVector
    get() = materialIcon("log") {
        path(strokeWidth = 1.9f) {
            moveTo(6.5f, 3.8f)
            horizontalLineTo(17.5f)
            verticalLineTo(20.2f)
            horizontalLineTo(6.5f)
            close()
        }
        path(strokeWidth = 1.7f) {
            moveTo(9.2f, 8f)
            horizontalLineTo(14.8f)
            moveTo(9.2f, 12f)
            horizontalLineTo(14.8f)
            moveTo(9.2f, 16f)
            horizontalLineTo(12.8f)
        }
    }

private val IconGitHub: ImageVector
    get() = ImageVector.Builder(
        name = "GitHub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            curveTo(6.477f, 2f, 2f, 6.484f, 2f, 12.017f)
            curveToRelative(0f, 4.428f, 2.865f, 8.184f, 6.839f, 9.507f)
            curveToRelative(0.5f, 0.092f, 0.682f, -0.217f, 0.682f, -0.482f)
            curveToRelative(0f, -0.237f, -0.008f, -0.865f, -0.013f, -1.699f)
            curveToRelative(-2.782f, 0.605f, -3.369f, -1.343f, -3.369f, -1.343f)
            curveToRelative(-0.454f, -1.156f, -1.11f, -1.464f, -1.11f, -1.464f)
            curveToRelative(-0.907f, -0.621f, 0.069f, -0.608f, 0.069f, -0.608f)
            curveToRelative(1.003f, 0.071f, 1.531f, 1.031f, 1.531f, 1.031f)
            curveToRelative(0.892f, 1.529f, 2.341f, 1.088f, 2.91f, 0.832f)
            curveToRelative(0.091f, -0.647f, 0.349f, -1.089f, 0.635f, -1.339f)
            curveToRelative(-2.22f, -0.253f, -4.555f, -1.111f, -4.555f, -4.946f)
            curveToRelative(0f, -1.093f, 0.39f, -1.986f, 1.029f, -2.686f)
            curveToRelative(-0.103f, -0.254f, -0.446f, -1.272f, 0.098f, -2.653f)
            curveToRelative(0f, 0f, 0.839f, -0.269f, 2.75f, 1.026f)
            curveTo(8.045f, 6.452f, 9.02f, 6.3f, 10.005f, 6.296f)
            curveToRelative(0.98f, 0.004f, 1.967f, 0.133f, 2.886f, 0.406f)
            curveToRelative(1.899f, -1.295f, 2.737f, -1.026f, 2.737f, -1.026f)
            curveToRelative(0.545f, 1.381f, 0.202f, 2.399f, 0.099f, 2.653f)
            curveToRelative(0.641f, 0.7f, 1.028f, 1.593f, 1.028f, 2.686f)
            curveToRelative(0f, 3.845f, -2.341f, 4.69f, -4.57f, 4.938f)
            curveToRelative(0.359f, 0.309f, 0.679f, 0.919f, 0.679f, 1.852f)
            curveToRelative(0f, 1.336f, -0.012f, 2.415f, -0.012f, 2.743f)
            curveToRelative(0f, 0.267f, 0.18f, 0.578f, 0.688f, 0.481f)
            curveTo(19.138f, 20.201f, 22f, 16.445f, 22f, 12.017f)
            curveTo(22f, 6.484f, 17.522f, 2f, 12f, 2f)
            close()
        }
    }.build()

private fun materialIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        block()
    }.build()

private fun ImageVector.Builder.path(
    strokeWidth: Float,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
) {
    addPath(
        pathData = androidx.compose.ui.graphics.vector.PathBuilder().apply(block).nodes,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        fill = null,
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
        style = MiuixTheme.textStyles.title4,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
    )
}

private data class PreviewState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val found: Boolean = false,
    val detail: String = "",
)

private data class PreviewLoad(
    val lock: Bitmap?,
    val home: Bitmap?,
    val palette: ColorKMeans.Palette?,
    val found: Boolean,
    val detail: String,
)

@Composable
private fun SettingsTab(
    cfg: FluidConfig,
    padding: PaddingValues,
    update: (FluidConfig) -> Unit,
    onOpenLog: () -> Unit,
) {
    val ctx = LocalContext.current
    var preview by remember { mutableStateOf(PreviewState()) }
    var status by remember { mutableStateOf(HookStatus.read(ctx)) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        WallpaperStore.requestExport(ctx)
        while (true) {
            val latest = HookStatus.read(ctx)
            if (latest.wallpaperExport?.ok == true &&
                latest.wallpaperExport.at != status.wallpaperExport?.at
            ) reloadToken++
            status = latest
            delay(3000L)
        }
    }

    fun reload() {
        WallpaperStore.requestExport(ctx)
        reloadToken++
        status = HookStatus.read(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        WallpaperIssueCard(status, preview, onOpenLog, ::reload)
        Spacer(Modifier.height(if (hasWallpaperIssue(status, preview)) 12.dp else 0.dp))

        PreviewCard(cfg, reloadToken) { preview = it }
        Spacer(Modifier.height(12.dp))

        Card {
            SectionTitle("开始")
            SwitchPreference(
                title = "解锁时播放",
                summary = "每次解锁时触发流体过渡",
                checked = cfg.enabled,
                onCheckedChange = { update(cfg.copy(enabled = it)) },
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SliderPreference(
                title = "动画时长",
                summary = "从锁屏扫到桌面的总时长",
                value = cfg.durationMs.toFloat(),
                onValueChange = { update(cfg.copy(durationMs = it.toInt())) },
                valueRange = 300f..3000f,
                valueText = "${cfg.durationMs} ms",
            )
        }
        Spacer(Modifier.height(12.dp))

        Card {
            SectionTitle("遮罩类型")
            SliderPreference(
                title = "噪声尺寸",
                summary = "调小呈大色块，调大相反",
                value = cfg.scale,
                onValueChange = { update(cfg.copy(scale = it)) },
                valueRange = 1f..10f,
                valueText = fmt(cfg.scale),
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SliderPreference(
                title = "边缘软硬",
                summary = "边界过渡的柔和程度",
                value = cfg.soft,
                onValueChange = { update(cfg.copy(soft = it)) },
                valueRange = 0.03f..0.6f,
                valueText = fmt(cfg.soft),
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SliderPreference(
                title = "中心扩散",
                summary = "从中心向外铺开的倾向",
                value = cfg.radial,
                onValueChange = { update(cfg.copy(radial = it)) },
                valueRange = 0f..1f,
                valueText = fmt(cfg.radial),
            )
        }
        Spacer(Modifier.height(12.dp))

        Card {
            SectionTitle("质感")
            SliderPreference(
                title = "扭曲强度",
                summary = "边缘液体的形变幅度",
                value = cfg.warp,
                onValueChange = { update(cfg.copy(warp = it)) },
                valueRange = 0f..0.2f,
                valueText = fmt(cfg.warp),
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SliderPreference(
                title = "色晕强度",
                summary = "边缘光晕的亮度",
                value = cfg.bleed,
                onValueChange = { update(cfg.copy(bleed = it)) },
                valueRange = 0f..1.5f,
                valueText = fmt(cfg.bleed),
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SliderPreference(
                title = "颗粒",
                summary = "画面噪点，结尾淡出",
                value = cfg.grain,
                onValueChange = { update(cfg.copy(grain = it)) },
                valueRange = 0f..0.08f,
                valueText = fmt(cfg.grain),
            )
        }
    }
}

private fun hasWallpaperIssue(status: HookStatus.Snapshot, preview: PreviewState): Boolean =
    status.wallpaperHook?.ok == false ||
        status.wallpaperExport?.ok == false ||
        (preview.loaded && !preview.found)

@Composable
private fun WallpaperIssueCard(
    status: HookStatus.Snapshot,
    preview: PreviewState,
    onOpenLog: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!hasWallpaperIssue(status, preview)) return
    val ctx = LocalContext.current
    val hook = status.wallpaperHook
    val export = status.wallpaperExport
    val hookFailed = hook?.ok == false
    val exportFailed = export?.ok == false
    val missing = preview.loaded && !preview.found
    val title = when {
        missing && hookFailed -> "壁纸 Hook 失败，且找不到壁纸"
        missing && exportFailed -> "壁纸导出失败，且找不到壁纸"
        missing && hook == null && export == null -> "等待壁纸进程回报"
        missing -> "找不到壁纸"
        exportFailed -> "壁纸导出失败"
        else -> "壁纸 Hook 失败"
    }
    val reasons = buildList {
        if (missing) {
            add(preview.detail.ifBlank { "系统壁纸服务、模块缓存和共享目录都没有返回壁纸，预览显示的是演示图。" })
        }
        if (hook != null && !hook.ok) add("壁纸 Hook：${hook.detail.ifBlank { "初始化失败" }}")
        if (export != null && !export.ok) add("壁纸导出：${export.detail.ifBlank { "导出失败" }}")
        if (missing && hook == null && export == null) {
            add("尚未收到壁纸进程的 Hook 回报。请确认 LSPosed 作用域勾选了「系统桌面」和「壁纸」，然后重启桌面或手机。")
        }
        if (!missing && (hookFailed || exportFailed)) {
            add("预览已有可用壁纸，但最新的 Hook 或导出检查未通过，建议查看日志确认原因。")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.errorContainer,
            contentColor = MiuixTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            reasons.forEach { reason ->
                Text(
                    text = "· $reason",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
            }
            /*
            if (preview.loading) {
                Text(
                    text = "正在重新检测，结果出来前会一直保留提示。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }

             */
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOpenLog) {
                    Text("查看日志", style = MiuixTheme.textStyles.button)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { copyLogs(ctx) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("复制日志", style = MiuixTheme.textStyles.button)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = if (preview.loading) "检测中…" else "重新检测",
                    onClick = onRetry,
                    enabled = !preview.loading,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    cfg: FluidConfig,
    reloadToken: Int,
    onState: (PreviewState) -> Unit,
) {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf<FluidTransitionView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(false) }
    var lastDetail by remember { mutableStateOf("") }
    val onStateNow by rememberUpdatedState(onState)

    LaunchedEffect(reloadToken) {
        val previousLoaded = loaded
        val previousFound = found
        val previousDetail = lastDetail
        onStateNow(
            PreviewState(
                loading = true,
                loaded = previousLoaded,
                found = previousFound,
                detail = previousDetail,
            ),
        )
        val result = withContext(Dispatchers.IO) {
            val home = WallpaperStore.loadLocal(ctx, "home", 1080)
                ?: WallpaperBridge.homeBitmap(ctx, 1080)
                ?: WallpaperStore.load(WallpaperStore.HOME)
            val lock = WallpaperStore.loadLocal(ctx, "lock", 1080)
                ?: WallpaperBridge.lockBitmap(ctx, 1080)
                ?: WallpaperStore.load(WallpaperStore.LOCK)
                ?: home?.let { WallpaperBridge.blurred(it, 14) }
            val hasWallpaper = lock != null || home != null
            val finalLock = lock ?: if (hasWallpaper) null else WallpaperBridge.demoLock()
            val finalHome = home ?: lock ?: if (hasWallpaper) null else WallpaperBridge.demoHome()
            val palette = (finalLock ?: finalHome)?.let { ColorKMeans.extract(it) }
            if (!hasWallpaper) {
                XLog.i("preview wallpaper not found: system, cache and shared storage are empty")
            }
            PreviewLoad(
                lock = finalLock,
                home = finalHome,
                palette = palette,
                found = hasWallpaper,
                detail = if (hasWallpaper) {
                    ""
                } else {
                    "系统壁纸服务、模块缓存和共享目录都没有返回壁纸，预览显示的是演示图。"
                },
            )
        }
        val v = view
        if (v != null) {
            v.tint = result.palette?.tint ?: 0xFF6FA8FF.toInt()
            v.setBitmaps(result.lock, result.home ?: result.lock)
            v.play(1200)
        }
        loaded = true
        found = result.found
        lastDetail = result.detail
        onStateNow(
            PreviewState(
                loading = false,
                loaded = true,
                found = result.found,
                detail = result.detail,
            ),
        )
    }

    LaunchedEffect(cfg) {
        val v = view ?: return@LaunchedEffect
        v.scale = cfg.scale
        v.soft = cfg.soft
        v.warp = cfg.warp
        v.bleed = cfg.bleed
        v.radial = cfg.radial
        v.grain = cfg.grain
    }

    Card {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
        ) {
            AndroidView(
                factory = { c -> FluidTransitionView(c).also { view = it } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            Button(
                onClick = { view?.play(cfg.durationMs.toLong()) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Text("重播", style = MiuixTheme.textStyles.button)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !loaded -> "正在读取壁纸…"
                    found -> "当前桌面壁纸"
                    else -> "未找到壁纸，正在预览演示图"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = if (loaded && !found) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onBackgroundVariant
                },
            )
        }
    }
}

@Composable
private fun LogTab(padding: PaddingValues) {
    val ctx = LocalContext.current
    var logText by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        logText = withContext(Dispatchers.IO) { LogStore.read(ctx) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "运行日志",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "包含设置页、桌面 Hook 和壁纸进程通过模块转发的最新日志。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refresh++ }) {
                        Text("刷新", style = MiuixTheme.textStyles.button)
                    }
                    Button(
                        onClick = { copyLogs(ctx) },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("复制日志", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = logText.ifBlank { "正在读取日志…" },
                        style = MiuixTheme.textStyles.footnote1,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
}

private fun copyLogs(ctx: Context) {
    try {
        val text = LogStore.read(ctx)
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(ctx, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("FluidWall 日志", text))
        Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show()
    } catch (t: Throwable) {
        Toast.makeText(ctx, "复制失败：$t", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AboutTab(padding: PaddingValues) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (t: Throwable) {
            "1.0"
        }
    }
    val muted = MiuixTheme.colorScheme.onBackgroundVariant
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Fluid Wallpaper · $version",
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "解锁的一瞬间，用分形噪声与 Voronoi 花纹生成一张会流动的遮罩" +
                            "让锁屏壁纸像被颜料冲开，从边缘一路铺成桌面壁纸",
                    style = MiuixTheme.textStyles.body2,
                    color = muted,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.Sink,
            showIndication = true,
            onClick = {
                uriHandler.openUri("https://github.com/Winamin/FluidWall")
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = IconGitHub,
                    contentDescription = "GitHub",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "GitHub 仓库",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Winamin/FluidWall",
                        style = MiuixTheme.textStyles.footnote1,
                        color = muted
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AboutEntry(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MiuixTheme.textStyles.subtitle)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

private fun fmt(v: Float): String = "%.2f".format(v)
