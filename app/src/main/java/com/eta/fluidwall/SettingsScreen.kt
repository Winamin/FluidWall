package com.eta.fluidwall

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eta.fluidwall.core.ColorKMeans
import com.eta.fluidwall.core.ConfigStore
import com.eta.fluidwall.core.FluidConfig
import com.eta.fluidwall.core.FluidTransitionView
import com.eta.fluidwall.core.WallpaperBridge
import com.eta.fluidwall.core.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun FluidWallApp() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf(ConfigStore.load(ctx)) }
    var tab by remember { mutableIntStateOf(0) }
    val controller = remember { ThemeController(ColorSchemeMode.System) }

    fun update(next: FluidConfig) {
        cfg = next
        ConfigStore.save(ctx, next)
    }

    MiuixTheme(controller = controller) {
        Scaffold(
            topBar = { TopAppBar(title = "流体壁纸", largeTitle = "流体壁纸") },
            bottomBar = {
                NavigationBar {
                    Item(IconSettings, "设置", tab == 0) { tab = 0 }
                    Item(IconInfo, "关于", tab == 1) { tab = 1 }
                }
            },
        ) { padding ->
            if (tab == 0) SettingsTab(cfg, padding, ::update) else AboutTab(padding)
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

@Composable
private fun SettingsTab(cfg: FluidConfig, padding: PaddingValues, update: (FluidConfig) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
    ) {
        PreviewCard(cfg)
        Spacer(Modifier.height(12.dp))

        //SmallTitle("遮罩类型")
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

        //SmallTitle("遮罩类型")
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

@Composable
private fun PreviewCard(cfg: FluidConfig) {
    val ctx = LocalContext.current
    var view by remember { mutableStateOf<FluidTransitionView?>(null) }
    var pal by remember { mutableStateOf<ColorKMeans.Palette?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val bitmaps = withContext(Dispatchers.IO) {
            val home = WallpaperStore.loadLocal(ctx, "home", 1080)
                ?: WallpaperBridge.homeBitmap(ctx, 1080)
                ?: WallpaperStore.load(WallpaperStore.HOME)
            val lock = WallpaperStore.loadLocal(ctx, "lock", 1080)
                ?: WallpaperBridge.lockBitmap(ctx, 1080)
                ?: WallpaperStore.load(WallpaperStore.LOCK)
                ?: home?.let { WallpaperBridge.blurred(it, 14) }
            if (lock == null && home == null) {
                WallpaperBridge.demoLock() to WallpaperBridge.demoHome()
            } else {
                lock to (home ?: lock)
            }
        }
        val lock = bitmaps.first
        val home = bitmaps.second
        val p = withContext(Dispatchers.IO) { (lock ?: home)?.let { ColorKMeans.extract(it) } }
        pal = p
        val v = view ?: return@LaunchedEffect
        v.tint = p?.tint ?: 0xFF6FA8FF.toInt()
        v.setBitmaps(lock, home ?: lock)
        loaded = true
        v.play(1200)
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
                if (loaded) "当前桌面壁纸" else "正在读取壁纸…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Spacer(Modifier.weight(1f))
            val colors = pal?.core
        }
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
    val enabled = remember { ConfigStore.load(ctx).enabled }
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