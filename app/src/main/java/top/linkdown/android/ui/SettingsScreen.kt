package top.linkdown.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import top.linkdown.android.LOGIN_PLATFORMS
import top.linkdown.android.LoginHubActivity
import top.linkdown.android.platformDisplayName
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.settings.AppSettings
import top.linkdown.android.settings.SettingsValues
import top.linkdown.android.update.AppUpdater
import top.linkdown.android.update.UpdateInstallFlow
import top.linkdown.android.update.UpdateOfferDialog
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * 设置页（功能任务）：像素风三行设置——音频格式（mp3/m4a）、画质档（最高/1080P）、
 * 并发数（2/3/4）。复用 PixelPanel/PixelChip/PixelIcon（A 配色），选项即点即存（DataStore 持久化，
 * 经 LinkdownApplication 设置流回写队列/管道/引擎）：
 * - 音频格式 → 下单时固化进项快照（跨重启续传格式一致）
 * - 画质档 → yt-dlp 视频限档 extraOpts（其余引擎忽略）
 * - 并发数 → 队列派发门（运行中变更只影响后续派发）
 * 功能任务 追加 cookie 管理区块：B站/网易云「已存 cookie：有 ✓/无」（回前台刷新）+「去登录账号」
 * 复用 [LoginHubActivity]；其余平台「暂不支持自动登录」说明（设计勘误留痕口径）。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onAudioFormatChange: (String) -> Unit,
    onQualityChange: (String) -> Unit,
    onMaxConcurrencyChange: (Int) -> Unit,
    onAutoCheckUpdateChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PixelTheme.colors
 // —— 检查更新状态（顶层声明：面板与收尾弹窗两处作用域共用）——
    val context = LocalContext.current
    val currentVersion = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var checkStatus by remember { mutableStateOf<String?>(null) }
    var offer by remember { mutableStateOf<AppUpdater.LatestRelease?>(null) }
    var installing by remember { mutableStateOf<AppUpdater.LatestRelease?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize().background(c.background)) {
 // 细棚条（同货架页，矮版 10dp 含 3dp 底缘）
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val h = size.height - 3.dp.toPx()
                    val stripe = 18.dp.toPx()
                    var x = 0f; var i = 0
                    while (x < size.width) {
                        drawRect(if (i % 2 == 0) c.primary else c.awningCream, topLeft = Offset(x, 0f), size = Size(stripe, h))
                        x += stripe; i++
                    }
                    drawRect(c.outline, topLeft = Offset(0f, h), size = Size(size.width, 3.dp.toPx()))
                },
        )
 // 头部：返回 + 标题（同货架页版式）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                "← 返回",
                color = c.darkBrown, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onBack() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            PixelIcon(Sprites.SETTINGS, c.darkBrown, accent = c.gold, iconSize = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("设 置", color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(52.dp)) // 与「返回」等宽占位，保持标题居中
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            PixelPanel {
                PixelPanelLabel(Sprites.NOTE, "音频格式")
                OptionRow(
                    options = SettingsValues.AUDIO_FORMATS,
                    selected = settings.audioFormat,
                    onSelect = onAudioFormatChange,
                )
                OptionNote(if (settings.audioFormat == SettingsValues.AUDIO_M4A) "体积更小 · 音质损耗更低"
                    else "通用性最好 · 各平台都认")
            }
            Spacer(Modifier.height(11.dp))
            PixelPanel {
                PixelPanelLabel(Sprites.CAMERA, "画质档")
                OptionRow(
                    options = SettingsValues.QUALITIES,
                    selected = settings.quality,
                    onSelect = onQualityChange,
                    label = { if (it == SettingsValues.QUALITY_1080P) "1080P" else "最高画质" },
                )
                OptionNote(if (settings.quality == SettingsValues.QUALITY_1080P) "最高不超过 1080P · 更省流量"
                    else "能拿到的最高清晰度")
            }
            Spacer(Modifier.height(11.dp))
            PixelPanel {
                PixelPanelLabel(Sprites.BOX, "同时下载几个")
                OptionRow(
                    options = SettingsValues.CONCURRENCIES.map { it.toString() },
                    selected = settings.maxConcurrency.toString(),
                    onSelect = { onMaxConcurrencyChange(it.toInt()) },
                )
                OptionNote("改动只影响还没开跑的单子，正在下的不受影响")
            }
            Spacer(Modifier.height(11.dp))
 // —— cookie 管理区块（ 设计）：B站/网易云 有✓/无 + 去登录入口（复用登录中心）——
            var cookieStatus by remember { mutableStateOf(emptyMap<String, Boolean>()) }
            LifecycleResumeEffect(Unit) {
 // 回前台刷新（登录中心存完 cookie 回来状态即准）；与 LoginHubActivity 同款口径
                cookieStatus = LOGIN_PLATFORMS.associateWith { CookieStore.existing(it, context.filesDir) != null }
                onPauseOrDispose { }
            }
            PixelPanel {
                PixelPanelLabel(Sprites.KEY, "登录账号")
                LOGIN_PLATFORMS.forEach { platform ->
                    CookieStatusRow(platformDisplayName(platform), cookieStatus[platform] == true)
                }
                Spacer(Modifier.height(7.dp))
                PixelButton(
                    "去登录账号 ▸",
                    fontSize = 15.sp,
                    icon = Sprites.KEY,
                    onClick = { context.startActivity(Intent(context, LoginHubActivity::class.java)) },
                )
                OptionNote("YouTube / Instagram / X / TikTok / SoundCloud 暂不支持自动登录")
            }
            Spacer(Modifier.height(11.dp))
 // —— 检查更新（应用内升级：GitHub Releases 固定名资产直链，境内经镜像前缀代理）——
            PixelPanel {
                PixelPanelLabel(Sprites.SETTINGS, "检查更新")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text("当前版本", color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("v$currentVersion", color = c.textSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(7.dp))
                OptionRow(
                    options = listOf("开", "关"),
                    selected = if (settings.autoCheckUpdate) "开" else "关",
                    onSelect = { onAutoCheckUpdateChange(it == "开") },
                )
                OptionNote("打开应用时自动检查新版本，关闭后仍可在下方手动检查")
                checkStatus?.let {
                    Text(it, color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, start = 2.dp))
                }
                Spacer(Modifier.height(7.dp))
                PixelButton(
                    "检查更新",
                    fontSize = 15.sp,
                    onClick = {
                        scope.launch {
                            checkStatus = "检查中…"
                            when (val r = AppUpdater.check(currentVersion)) {
                                is AppUpdater.CheckResult.UpToDate -> checkStatus = "已是最新版本 ✓"
                                is AppUpdater.CheckResult.NewRelease -> {
                                    checkStatus = null
                                    offer = r.release
                                }
                                is AppUpdater.CheckResult.Failed -> checkStatus = r.reason
                            }
                        }
                    },
                )
            }
        }
 // 升级弹窗（发现新版 → 下载进度 → 签名校验 → 安装器）
        offer?.let { release ->
            UpdateOfferDialog(
                release = release,
                onDownload = { offer = null; installing = release },
                onDismiss = { _ -> offer = null },
                neverRemindOption = false, // 手动检查路径无启动提醒语义，不渲染勾选行
            )
        }
        installing?.let { release ->
            UpdateInstallFlow(release = release, onDismiss = { installing = null })
        }
    }
}

/** cookie 状态行：平台名 + 「已存 cookie：有 ✓/无」（口径与登录中心一致）。 */
@Composable
private fun CookieStatusRow(name: String, hasCookie: Boolean) {
    val c = PixelTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(name, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            if (hasCookie) "已存 cookie：有 ✓" else "已存 cookie：无",
            color = if (hasCookie) c.success else c.textSecondary,
            fontSize = 11.sp,
        )
    }
}

/** 选项行：互斥像素 chip——选中橙底白字，未选奶白底棕字（复用 PixelChip）。 */
@Composable
private fun OptionRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    label: (String) -> String = { it },
) {
    val c = PixelTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { value ->
            val isSelected = value == selected
            PixelChip(
                label(value),
                bg = if (isSelected) c.primary else c.surface,
                contentColor = if (isSelected) c.onPrimary else c.textPrimary,
                modifier = Modifier.weight(1f),
            ) {
                if (!isSelected) onSelect(value) // 重复点已选项不写盘
            }
        }
    }
}

/** 行尾小注（当前选项的人话解释；灰字 11sp）。 */
@Composable
private fun OptionNote(text: String) {
    val c = PixelTheme.colors
    Text(text, color = c.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp, start = 2.dp))
}
