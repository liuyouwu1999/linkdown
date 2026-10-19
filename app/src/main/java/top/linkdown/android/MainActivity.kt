package top.linkdown.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import top.linkdown.android.battery.BatteryPrompt
import top.linkdown.android.battery.BatteryPromptDialog
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.engine.YtdlpEngine
import top.linkdown.android.eula.EulaPromptDialog
import top.linkdown.android.pipeline.PlatformDirs
import top.linkdown.android.queue.EnqueueResult
import top.linkdown.android.queue.QueueState
import top.linkdown.android.settings.AppSettings
import top.linkdown.android.ui.MainScreen
import top.linkdown.android.ui.MainUiState
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.SettingsScreen
import top.linkdown.android.ui.ShelfItem
import top.linkdown.android.ui.ShelfScreen
import top.linkdown.android.ui.ShelfStore
import top.linkdown.android.update.AppUpdater
import top.linkdown.android.update.UpdateInstallFlow
import top.linkdown.android.update.UpdateOfferDialog

private enum class Screen { HOME, SHELF, SETTINGS }

private const val PREFS_NOTIFICATION = "notif_prefs"
private const val KEY_NOTIF_REQUESTED = "post_notifications_requested"

/** 启动自动检查更新的节流间隔（24h；GitHub API 无需调用——固定名资产直链，仍防频繁请求镜像）。 */
private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/** 已知平台 URL（设计：剪贴板 chip 只对认得的平台亮出） */
/** 已知平台（设计：剪贴板 chip 只对认得的平台亮出）——支持整段分享文案（自动提取链接） */
private fun knownPlatformUrl(text: String): Boolean {
    val hit = extractShareUrls(text.trim()).firstOrNull()
        ?: text.trim().takeIf { it.startsWith("www.") }?.let { "https://$it" }
        ?: return false
    return PlatformDirs.dirFor(hit) != "其他"
}

class MainActivity : ComponentActivity() {

 /** 设计：拒绝=无通知功能照跑+横幅提示一次（进程内防重复）。 */
    private var notificationHintShown = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && !notificationHintShown) {
                notificationHintShown = true
                Toast.makeText(this, "未授予通知权限：下载照常进行，仅通知栏不显示进度", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YtdlpEngine.initOnce(applicationContext)
        requestNotificationPermissionOnce()
        setContent { PixelTheme { Root() } }
    }

 /** POST_NOTIFICATIONS 首次主界面请求（全局约束）：每安装一次，已授予/低版本不请求。 */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return // 33 以下通知权限随安装隐式授予
        val prefs = getSharedPreferences(PREFS_NOTIFICATION, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_REQUESTED, false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean(KEY_NOTIF_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Composable
    private fun Root() {
        val scope = rememberCoroutineScope()
        val ts = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
        val clipboard = LocalClipboardManager.current
 // 架构迁移（设计架构）：状态真源=Application 队列单例，本 Activity 仅观察 queue.state
 //（重建/回收无感——技术债已清）；pipeline 直调入口已收编进 queue.enqueue/cancel/retry
        val queue = (application as LinkdownApplication).queue
        val queueState by queue.state.collectAsStateWithLifecycle()
 // 设置流观察（设置页渲染真源；即点即存经仓库 setter → 流回传）
        val settingsRepo = (application as LinkdownApplication).settingsRepository
        val settings by remember { settingsRepo.settings }
            .collectAsStateWithLifecycle(initialValue = AppSettings())
        var ui by remember { mutableStateOf(MainUiState()) }
        var screen by remember { mutableStateOf(Screen.HOME) }
        var shelf by remember { mutableStateOf<List<ShelfItem>>(emptyList()) }

 // —— 应用内升级（产品拍板：设置页手动 + 启动自动检查，24h 节流；失败静默不打扰）——
        var pendingUpdate by remember { mutableStateOf<AppUpdater.LatestRelease?>(null) }
        var updating by remember { mutableStateOf<AppUpdater.LatestRelease?>(null) }
        var updateMirrorAtCheck by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            runCatching {
                if (!settingsRepo.isAutoCheckUpdate()) return@LaunchedEffect
                val now = System.currentTimeMillis()
                if (now - settingsRepo.lastUpdateCheckAt() < AUTO_CHECK_INTERVAL_MS) return@LaunchedEffect
                settingsRepo.setLastUpdateCheckAt(now)
                val mirror = settingsRepo.updateMirror()
                updateMirrorAtCheck = mirror
                val current = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                (AppUpdater.check(mirror, current) as? AppUpdater.CheckResult.NewRelease)
                    ?.let { pendingUpdate = it.release }
            }
        }
        pendingUpdate?.let { release ->
            UpdateOfferDialog(
                release = release,
                onDownload = { pendingUpdate = null; updating = release },
                onDismiss = { pendingUpdate = null },
            )
        }
        updating?.let { release ->
            UpdateInstallFlow(release = release, mirror = updateMirrorAtCheck, onDismiss = { updating = null })
        }

        fun log(line: String) { ui = ui.copy(logLines = ui.logLines + "[${ts.format(Date())}] $line") }
        fun refreshShelf() {
            scope.launch(Dispatchers.IO) { shelf = ShelfStore.query(applicationContext) }
        }

        BackHandler(enabled = screen == Screen.SHELF) { screen = Screen.HOME }
        BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }

        LifecycleResumeEffect(Unit) {
 // 设计：回前台检测剪贴板为已知平台 URL → 绿 chip（取代早期的直接预填）；
 // fix round 1/2：回前台同时刷新各平台「已存 cookie」状态行
            val text = clipboard.getText()?.text?.trim()
            val hasCookie = mapOf(
                "bilibili" to (CookieStore.existing("bilibili", applicationContext.filesDir) != null),
                "netease" to (CookieStore.existing("netease", applicationContext.filesDir) != null),
            )
            ui = if (!text.isNullOrEmpty() && knownPlatformUrl(text) && ui.url.trim() != text) {
                ui.copy(clipboardUrl = text, hasCookie = hasCookie)
            } else {
                ui.copy(clipboardUrl = null, hasCookie = hasCookie)
            }
            refreshShelf()
            onPauseOrDispose { }
        }

 // 队列终态迁移 → 状态日志行 + 出货区刷新（替代 观察 pipeline.state；首个快照只记账不记日志，
 // 防杀进程恢复时把上一进程的历史终态重播一遍）。批量：子项不出日志行（完成仅触发出货区刷新），
 // 父组按派生态记一行（完成/部分失败/取消）。
        var knownStates by remember { mutableStateOf(emptyMap<String, QueueState>()) }
        LaunchedEffect(queueState) {
            val prev = knownStates
            if (prev.isEmpty()) {
                knownStates = queueState.items.associate { it.id to it.state }
                return@LaunchedEffect
            }
            var logged = false
            var completed = false
            for (item in queueState.items) {
                if (prev[item.id] == item.state) continue
                if (item.parentGroupId != null) {
 // 子项：不进日志（防 N 条刷屏）；完成/已上架仅刷新出货区
                    if (item.state == QueueState.Completed || item.state == QueueState.Exists) completed = true
                    logged = true
                    continue
                }
                when (item.state) {
                    QueueState.Completed -> {
                        if (item.kind == top.linkdown.android.queue.QueueItemKind.GROUP) {
                            log("✔ 完成：${item.title}（${item.totalChildren} 条）已保存到 Download/链接下载/")
                        } else {
                            log("✔ 完成：${item.title} 已保存到 Download/链接下载/${item.probePlatform ?: item.platform}/")
                        }
                        completed = true
                    }
                    QueueState.Failed -> {
                        if (item.kind == top.linkdown.android.queue.QueueItemKind.GROUP) {
                            log("✘ 部分失败：${item.title}（失败 ${item.failedChildren}/${item.totalChildren}，可在卡片重试失败子项）")
                        } else {
                            log("✘ 失败：${item.error ?: "未知错误"}")
                        }
                    }
                    QueueState.Cancelled -> log("⏹ 已取消")
                    else -> {}
                }
                logged = true
            }
            if (logged) {
                knownStates = queueState.items.associate { it.id to it.state }
                if (completed) refreshShelf() // 出货区/徽标计数即时更新
            }
        }

        when (screen) {
            Screen.HOME -> MainScreen(
                state = ui,
                queue = queueState,
                shelf = shelf,
                onUrlChange = { ui = ui.copy(url = it) },
                onModeChange = { ui = ui.copy(modes = it) },
                onSessExpandedChange = { ui = ui.copy(sessExpanded = it) },
                onSessChange = { ui = ui.copy(sessText = it) },
                onOpenLoginHub = {
 // fix round 1/2：登录选择页 → 各平台 WebView 自动抓 Cookie
                    startActivity(Intent(this, LoginHubActivity::class.java))
                },
                onStart = { modes, url, probeTitle ->
 // 架构迁移：单视频下单 → queue.enqueue（去重命中弹「已在队列中」，设计；
 // FGS 由队列内触发）。批量：单视频路径恒带 --no-playlist（含混合型 URL/entries==1 回退）。
 // 状态日志记内容名不记链接：弹窗已 probe 到名字直接用；直连路径等 probe 回填（最多 20s，
 // 拿不到就只记「已下单」——链接不进日志）。
                    scope.launch {
                        when (val r = queue.enqueue(url, modes, ui.sessText)) {
                            is EnqueueResult.Enqueued -> {
                                val id = r.item.id
                                val name = probeTitle ?: withTimeoutOrNull(20_000) {
                                    queue.state
                                        .map { st -> st.items.firstOrNull { it.id == id }?.title }
                                        .filterNotNull()
                                        .first { it != url }
                                }
                                log(if (name.isNullOrBlank() || name == url) "已下单" else "已下单：$name")
                            }
                            EnqueueResult.Duplicate ->
                                Toast.makeText(applicationContext, "已在队列中", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onEnqueueGroup = { modes, url, title, children ->
 // 批量：批量下单 → queue.enqueueGroup（父组+子项整批；FGS 由队列内触发）
                    scope.launch {
                        when (val r = queue.enqueueGroup(title, url, modes, children, ui.sessText)) {
                            is EnqueueResult.Enqueued ->
                                log("已下单：${r.item.title}（${r.item.totalChildren} 条）")
                            EnqueueResult.Duplicate ->
                                Toast.makeText(applicationContext, "已在队列中", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enumerateBatch = { u ->
 // 入口枚举缝（恰一次；抛错=弹窗人话不入队任何条目）
                    (application as LinkdownApplication).pipeline.enumerate(u, ui.sessText)
                },
                onCancel = { runId -> scope.launch { queue.cancel(runId) } },
                onRetry = { runId -> scope.launch { queue.retry(runId) } },
                onDeleteQueueItem = { id -> scope.launch { queue.removeTerminal(id) } },
                onDeleteQueueItems = { ids -> scope.launch { queue.removeTerminals(ids) } },
                onTogglePaused = { scope.launch { queue.setPaused(!queueState.paused) } },
                onImportClipboard = {
                    ui.clipboardUrl?.let { clip ->
                        val url = extractShareUrls(clip).firstOrNull() ?: clip
                        ui = ui.copy(url = url, clipboardUrl = null)
                        log("已从剪贴板带入链接")
                    }
                },
                onOpenShelf = { screen = Screen.SHELF },
                onPlayShelfItem = { item -> ShelfStore.play(applicationContext, item) },
                onOpenSettings = { screen = Screen.SETTINGS },
                modifier = Modifier.fillMaxSize(),
            )
            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                onAudioFormatChange = { v -> scope.launch { settingsRepo.setAudioFormat(v) } },
                onQualityChange = { v -> scope.launch { settingsRepo.setQuality(v) } },
                onMaxConcurrencyChange = { v -> scope.launch { settingsRepo.setMaxConcurrency(v) } },
                onUpdateMirrorChange = { v -> scope.launch { settingsRepo.setUpdateMirror(v) } },
                onAutoCheckUpdateChange = { v -> scope.launch { settingsRepo.setAutoCheckUpdate(v) } },
                onBack = { screen = Screen.HOME },
                modifier = Modifier.fillMaxSize(),
            )
            Screen.SHELF -> {
                // 初值=默认书架（须与 SettingsRepository 默认一致，避免设置加载前闪列表）；加载后=用户上次选择
                var shelfBoardMode by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { shelfBoardMode = settingsRepo.isShelfBoardMode() }
                ShelfScreen(
                items = shelf,
                shelfBoardMode = shelfBoardMode,
                onToggleShelfBoardMode = {
                    shelfBoardMode = !shelfBoardMode
                    scope.launch { settingsRepo.setShelfBoardMode(shelfBoardMode) }
                },
                onPlay = { item -> ShelfStore.play(applicationContext, item) },
                onDelete = { item ->
                    scope.launch(Dispatchers.IO) {
                        val ok = ShelfStore.delete(applicationContext, item)
                        if (ok) shelf = ShelfStore.query(applicationContext)
                        log(if (ok) "已下架：${item.name}" else "下架失败：${item.name}")
                    }
                },
                onBatchDelete = { items ->
                    scope.launch(Dispatchers.IO) {
                        var ok = 0
                        val failedNames = mutableListOf<String>()
                        items.forEach { item ->
                            if (ShelfStore.delete(applicationContext, item)) ok++ else failedNames += item.name
                        }
                        shelf = ShelfStore.query(applicationContext)
                        log(
                            when {
                                failedNames.isEmpty() -> "批量下架完成：${ok} 件"
                                else -> "批量下架：${ok} 件成功，失败：${failedNames.joinToString("、")}"
                            }
                        )
                    }
                },
                onBack = { screen = Screen.HOME },
                modifier = Modifier.fillMaxSize(),
                )
            }
        }

 // 首启用户协议（开源合规）：必须显式选择——同意置旗标永不再弹；不同意=退出应用。点外不关闭。
 // 读取中（null）不弹；协议未决期间电池引导不启动（LaunchedEffect(eulaAccepted) 门）。
        var eulaAccepted by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(Unit) { eulaAccepted = settingsRepo.isEulaAccepted() }
        if (eulaAccepted == false) {
            EulaPromptDialog(
                onAccept = {
                    scope.launch { settingsRepo.setEulaAccepted() }
                    eulaAccepted = true
                },
                onDecline = { finish() },
            )
        }

 // 电池优化引导（设计）：首启（DataStore 旗标未置）且未被排除出电池优化 → 像素对话框
 // → 系统授权页。「去设置」跳转 /「暂 不」/点外关闭均置旗标不再弹；已白名单同样落旗标（不再查）。
 // 旗标经 SettingsRepository 一次性直读（不经设置流，防冷启动默认值快照误判「未引导」）。
 // 协议同意（eulaAccepted==true）后才检查——未同意协议前不引导。
        var showBatteryPrompt by remember { mutableStateOf(false) }
        LaunchedEffect(eulaAccepted) {
            if (eulaAccepted == true && !settingsRepo.isBatteryPromptDone()) {
                if (!BatteryPrompt.isIgnoringOptimizations(this@MainActivity)) showBatteryPrompt = true
                else scope.launch { settingsRepo.setBatteryPromptDone() }
            }
        }
        if (showBatteryPrompt) {
            BatteryPromptDialog(
                onAccept = {
                    showBatteryPrompt = false
                    scope.launch { settingsRepo.setBatteryPromptDone() }
                    startActivity(BatteryPrompt.requestIntent(this@MainActivity))
                },
 // 暂 不：不置旗标——本次关闭，下次启动再问（与「不再提醒」区分）
                onDecline = { showBatteryPrompt = false },
                onNeverAsk = {
                    showBatteryPrompt = false
                    scope.launch { settingsRepo.setBatteryPromptDone() }
                },
            )
        }
    }
}
