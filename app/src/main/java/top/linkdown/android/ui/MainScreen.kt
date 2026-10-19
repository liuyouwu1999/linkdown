package top.linkdown.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import top.linkdown.android.extractShareUrls
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.PlatformDirs
import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueItemKind
import top.linkdown.android.queue.QueueState
import top.linkdown.android.queue.QueueUiState
import top.linkdown.android.queue.activePositionOf
import top.linkdown.android.queue.groupDoneChildren
import top.linkdown.android.queue.groupProgressFraction

data class MainUiState(
    val url: String = "",
 // 模式不再常驻主界面（产品拍板）：默认全不选，每次「下 单！」弹窗现勾
    val modes: DownloadModes = DownloadModes(video = false, audio = false, cover = false),
    val sessExpanded: Boolean = false,
    val sessText: String = "",
    val hasCookie: Map<String, Boolean> = emptyMap(), // 各平台已存 cookie（回前台刷新，fix round 2）
    val logLines: List<String> = emptyList(),
    val clipboardUrl: String? = null, // 回前台检测到已知平台 URL → 面板内绿 chip（设计）
)

/**
 * 输入框 → 下单 URL：支持整段分享文案（标题/话题/推广语混链接——用户从抖音等 App
 * 「复制链接」得到的粘贴内容就是这种形态），自动提取首个 http(s) 链接；
 * 纯 www 前缀与裸 URL 维持原行为；提取不到返回 null。
 */
internal fun extractOrderUrl(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    extractShareUrls(t).firstOrNull()?.let { return it }
    if (t.startsWith("www.")) return "https://$t"
    return if (t.startsWith("http://") || t.startsWith("https://")) t else null
}

/** 主按钮可用性：输入能提取出链接即可下单（模式约束移到弹窗「开 始」≥1 项） */
fun isValidInput(state: MainUiState): Boolean = extractOrderUrl(state.url) != null

/** 队列区展示的五态（设计+ Exists「已在货架上」卡）；Completed/Cancelled 终态不入队列区
 * （出货去货架，见历史/通知）。带 parentGroupId 子项一律不出独立卡（经父组卡呈现）。 */
private val queueVisibleStates =
    setOf(QueueState.Queued, QueueState.Running, QueueState.WaitingNetwork, QueueState.Failed, QueueState.Exists)

@Composable
fun MainScreen(
    state: MainUiState,
    queue: QueueUiState,
    shelf: List<ShelfItem>,
    onUrlChange: (String) -> Unit,
    onModeChange: (DownloadModes) -> Unit,
    onSessExpandedChange: (Boolean) -> Unit,
    onSessChange: (String) -> Unit,
    onSaveSess: () -> Unit,
    onOpenLoginHub: () -> Unit,
    onStart: (modes: DownloadModes, url: String, probeTitle: String?) -> Unit,
    onEnqueueGroup: (modes: DownloadModes, url: String, title: String, children: List<ChildRef>) -> Unit,
    enumerateBatch: suspend (String) -> BatchInfo,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDeleteQueueItem: (String) -> Unit,
    onDeleteQueueItems: (List<String>) -> Unit,
    onTogglePaused: () -> Unit,
    onImportClipboard: () -> Unit,
    onOpenShelf: () -> Unit,
    onPlayShelfItem: (ShelfItem) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PixelTheme.colors
    var showOrderDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<QueueItem?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.logLines.size) {
        if (state.logLines.isNotEmpty()) listState.animateScrollToItem(state.logLines.lastIndex)
    }
    Column(modifier = modifier.fillMaxSize().background(c.background)) {
        ShopHeader(shelfCount = shelf.size, onShelfClick = onOpenShelf)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
 // ── 面板一：交给我想要的链接（设计）──
            PixelPanel {
                PixelPanelLabel(Sprites.BOX, "交给我想要的链接：")
                PixelTextField(state.url, onUrlChange, "粘贴视频链接…", testTag = "urlField")
                state.clipboardUrl?.let {
                    Spacer(Modifier.height(8.dp))
                    PixelChip(
                        "剪贴板有一条 · 点此带入",
                        bg = c.success,
                        contentColor = c.onDarkBrown,
                        icon = Sprites.CLIPBOARD,
                        onClick = onImportClipboard,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
 // ── 主按钮：下单（队列化后常驻——取消/重试移入各队列卡，设计）──
            PixelButton("下 单！", enabled = isValidInput(state), onClick = { showOrderDialog = true })
 // 子项不出独立卡（设计）：经父组卡呈现
            val queueCards = queue.items.filter { it.parentGroupId == null && it.state in queueVisibleStates }
            if (queueCards.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                QueueArea(queue, queueCards, onCancel, onRetry, onDelete = { pendingDelete = it },
                    onDeleteMany = onDeleteQueueItems, onTogglePaused, onOpenShelf)
            }
            Spacer(Modifier.height(13.dp))
 // ── 登录账号折叠（ 语义保留 + 入口回归；评审修复 挪到下单之下、出货之上）──
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        onSessExpandedChange(!state.sessExpanded)
                    }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.sessExpanded) "▾ 登录账号（可选）" else "▸ 登录账号（可选）",
                    color = c.textSecondary, fontSize = 13.sp,
                )
            }
            if (state.sessExpanded) {
                PixelTextField(state.sessText, onSessChange, "粘贴 SESSDATA（B站，留空自动用已存 cookie）", singleLine = true)
                Text("B站支持粘贴 SESSDATA；网易云请点下方按钮登录", color = c.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp))
                Spacer(Modifier.height(8.dp))
 // 粘贴→保存：显式确认+成功/失败反馈（成功清空输入并点亮「有✓」；对齐 web 端 cookie 保存按钮）
                PixelButton("保 存", fontSize = 16.sp, onClick = onSaveSess)
                Spacer(Modifier.height(8.dp))
 // 单入口进登录选择页（评审修复 产品拍板）
                PixelButton(
                    "去 登 录",
                    style = PixelButtonStyle.SUCCESS,
                    icon = Sprites.KEY,
                    fontSize = 16.sp,
                    onClick = onOpenLoginHub,
                )
                val mark = { v: Boolean? -> if (v == true) "有✓" else "无" }
                Text(
                    "B站 ${mark(state.hasCookie["bilibili"])} · 网易云 ${mark(state.hasCookie["netease"])}",
                    color = c.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(13.dp))
 // ── 设置入口（音频格式/画质档/并发数三行设置，独立页）──
            PixelButton("设 置", fontSize = 16.sp, icon = Sprites.SETTINGS, onClick = onOpenSettings)
            Spacer(Modifier.height(13.dp))
 // ── 面板二：今日出货（设计：内嵌深棕区 + 区头「全部 >」进货架页）──
            PixelPanel {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onOpenShelf() },
                ) {
                    PixelIcon(Sprites.BOX, c.darkBrown, accent = c.gold, iconSize = 14.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("今日出货 ${shelf.size} 件", color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("全部 >", color = c.darkBrown, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                ShipmentArea(shelf.take(3), onPlayShelfItem)
            }
            if (state.logLines.isNotEmpty()) {
                Spacer(Modifier.height(11.dp))
                LogArea(state.logLines, listState)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
 // ── 弹窗选模式（产品拍板的核心交互，设计）：点「下 单！」→ 底部弹窗勾选 ──
 // 批量：批量门过门 → 弹窗内加载态「解析列表…」→ 二段确认「列表标题 · N 条」（超 200 截断人话）
    if (showOrderDialog) {
        val startUrl = extractOrderUrl(state.url)
            ?: state.url.trim().let { if (it.startsWith("www.")) "https://$it" else it }
        ModeOrderDialog(
            url = startUrl,
            initial = state.modes,
            enumerate = enumerateBatch,
            onConfirmSingle = { modes, u, probeTitle ->
                showOrderDialog = false
                onModeChange(modes)
                onStart(modes, u, probeTitle)
            },
            onConfirmBatch = { modes, title, children ->
                showOrderDialog = false
                onModeChange(modes)
                onEnqueueGroup(modes, startUrl, title, children)
            },
            onDismiss = { showOrderDialog = false },
        )
    }
 // ── 终态记录删除确认（产品拍板：删除要有确认界面）：只清台账，货架文件不动。
 // 标题=入队占位链接（probe 失败没拿到真名）时用平台名人话，别把整条链接糊进弹窗 ──
    pendingDelete?.let { item ->
        val name = item.title
            .takeUnless { it.isBlank() || it == item.url }
            ?.let { "「${it.take(24)}」" }
            ?: "这条「${PlatformDirs.dirFor(item.url)}」"
        val scopeText = if (item.kind == QueueItemKind.GROUP) "的列表记录与全部子项记录" else "的记录"
        PixelConfirmDialog(
            title = "从后厨清掉这条记录？",
            message = "$name${scopeText}将从后厨移除（货架上的文件不受影响）。",
            confirmText = "删 除",
            onConfirm = { onDeleteQueueItem(item.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 队列卡列表（设计+ 父组卡）：头部暂停/继续 + 分态卡——Running（进度+取消，Cancelling 显
 * 「取消中…」禁用）、Queued（排位+取消）、WaitingNetwork（「等待网络」+取消）、Failed（错误+「重 试」）、
 * Exists（「已在货架上」+「去 货 架」直达，）；Failed/Exists 卡另有「删 除」=像素确认框后终态台账
 * 清账（queue.removeTerminal，只删记录不动货架产物）；**长按终态卡进多选**（批量清账，批删走
 * queue.removeTerminals）；GROUP 父项走父组卡（标题·N 条+聚合进度+折叠前 3 条+
 * 其余 N 条+级联取消+重试失败子项），子项不出独立卡。复用 A 配色像素组件，不推翻已验收店面。
 */
@Composable
private fun QueueArea(
    queue: QueueUiState,
    cards: List<QueueItem>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (QueueItem) -> Unit,
    onDeleteMany: (List<String>) -> Unit,
    onTogglePaused: () -> Unit,
    onOpenShelf: () -> Unit,
) {
    val activeCount = queue.items.count {
        it.parentGroupId == null &&
            (it.state == QueueState.Queued || it.state == QueueState.Running || it.state == QueueState.WaitingNetwork)
    }
 // —— 批量清账（长按终态卡进多选）：只有 Failed/Exists 单卡与父组卡可选，子项不出卡不可选 ——
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    val selectableIds = remember(cards) {
        cards.filter { it.state == QueueState.Failed || it.state == QueueState.Exists }.map { it.id }.toSet()
    }
    LaunchedEffect(selectableIds) {
        if (selecting) selected = selected intersect selectableIds // 选中项被删/离场即修剪
    }
    PixelPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelPanelLabel(Sprites.BOX, if (activeCount > 0) "后厨 · ${activeCount} 单" else "后厨")
            Spacer(Modifier.weight(1f))
            PixelButton(
                if (queue.paused) "继 续" else "暂 停",
                fontSize = 14.sp,
                onClick = onTogglePaused,
                modifier = Modifier.width(120.dp),
            )
        }
        if (selecting) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size} 条", color = PixelTheme.colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                PixelButton("全 选", enabled = selectableIds.isNotEmpty(), fontSize = 12.sp,
                    modifier = Modifier.width(64.dp), onClick = { selected = selectableIds })
                Spacer(Modifier.width(7.dp))
                PixelButton("删 除", enabled = selected.isNotEmpty(), style = PixelButtonStyle.ERROR,
                    fontSize = 12.sp, modifier = Modifier.width(64.dp), onClick = { confirmBatch = true })
                Spacer(Modifier.width(7.dp))
                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, fontSize = 12.sp,
                    modifier = Modifier.width(64.dp), onClick = { selecting = false; selected = emptySet() })
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            cards.forEach { item ->
                val isSelectable = item.state == QueueState.Failed || item.state == QueueState.Exists
                if (item.kind == QueueItemKind.GROUP) {
                    GroupCard(
                        item, queue.items.filter { it.parentGroupId == item.id },
                        onCancel, onRetry,
                        onDelete = onDelete,
                        selecting = selecting,
                        selected = item.id in selected,
                        onLongPress = if (isSelectable) ({
                            if (!selecting) selecting = true
                            selected = selected + item.id
                        }) else null,
                        onToggleSelect = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                    )
                } else {
                    QueueCard(
                        item, queue.activePositionOf(item.id),
                        onCancel, onRetry,
                        onDelete = onDelete,
                        onOpenShelf = onOpenShelf,
                        selecting = selecting,
                        selected = item.id in selected,
                        onLongPress = if (isSelectable) ({
                            if (!selecting) selecting = true
                            selected = selected + item.id
                        }) else null,
                        onToggleSelect = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                    )
                }
            }
        }
    }
    if (confirmBatch) {
        PixelConfirmDialog(
            title = "删除所选 ${selected.size} 条记录？",
            message = "所选终态记录将从后厨移除（货架上的文件不受影响）。",
            confirmText = "删 除",
            onConfirm = {
                onDeleteMany(selected.toList())
                selecting = false
                selected = emptySet()
                confirmBatch = false
            },
            onDismiss = { confirmBatch = false },
        )
    }
}

/** 子项态行文案（父组卡折叠列表用）。 */
private fun childStateLabel(item: QueueItem): String = when (item.state) {
    QueueState.Running -> if (item.cancelling) "取消中" else "下载中 ${(item.progressFraction * 100).toInt()}%"
    QueueState.Queued -> "排队中"
    QueueState.WaitingNetwork -> "等待网络"
    QueueState.Completed -> "完成"
    QueueState.Failed -> "失败"
    QueueState.Cancelled -> "已取消"
    QueueState.Exists -> "已在货架"
}

/** 父组卡折叠子项列表：前 3 条（标题+态）+「其余 N 条」（设计）。 */
@Composable
private fun ChildrenPeek(children: List<QueueItem>, totalChildren: Int) {
    val c = PixelTheme.colors
    Column {
        children.take(3).forEach { child ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(child.title, color = c.textPrimary, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(5.dp))
                Text(childStateLabel(child), color = c.textSecondary, fontSize = 10.sp)
            }
        }
        if (totalChildren > 3) {
            Text("其余 ${totalChildren - 3} 条", color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

/** 父组卡（ 设计）：标题·N 条+聚合进度（Exists 视同完成）+折叠前 3 条+级联取消/
 * 重试仅失败子项（派生 Failed=Partial f/m）。子项不出独立卡。多选态：点卡切换选中、内钮禁用。 */
@Composable
private fun GroupCard(
    group: QueueItem,
    children: List<QueueItem>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (QueueItem) -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onLongPress: (() -> Unit)?,
    onToggleSelect: () -> Unit,
) {
    val c = PixelTheme.colors
    val cardShape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, cardShape)
            .border(if (selected) 3.dp else 2.dp, if (selected) c.primary else c.outline, cardShape)
            .pointerInput(group.id, selecting, onLongPress) {
                detectTapGestures(
                    onTap = { if (selecting) onToggleSelect() },
                    onLongPress = { if (!selecting) onLongPress?.invoke() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${group.title} · ${group.totalChildren} 条", color = c.textPrimary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(5.dp))
            Text(group.platform, color = c.error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        when (group.state) {
            QueueState.Running -> {
                Text(
                    if (group.cancelling) "取消中…" else "下载中 已完成 ${group.groupDoneChildren()}/${group.totalChildren}",
                    color = c.textSecondary, fontSize = 11.sp,
                )
                Spacer(Modifier.height(5.dp))
                PixelProgressBar(group.groupProgressFraction())
                Spacer(Modifier.height(7.dp))
                ChildrenPeek(children, group.totalChildren)
                Spacer(Modifier.height(7.dp))
                PixelButton(
                    if (group.cancelling) "取消中…" else "取 消",
                    enabled = !group.cancelling,
                    style = PixelButtonStyle.SUCCESS,
                    fontSize = 14.sp,
                    onClick = { onCancel(group.id) }, // 级联：未完成子项全取消（含 Running）
                )
            }
            QueueState.Failed -> { // Partial(f/m)
                Text("部分失败 ${group.failedChildren}/${group.totalChildren}", color = c.error, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                PixelProgressBar(group.groupProgressFraction())
                Spacer(Modifier.height(7.dp))
                ChildrenPeek(children, group.totalChildren)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PixelButton("重试失败子项", fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = { onRetry(group.id) })
                    PixelButton("删 除", style = PixelButtonStyle.ERROR, fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = { onDelete(group) }) // 级联清台账（不动货架）
                }
            }
            QueueState.Exists -> {
                Text("已在货架上", color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                PixelButton("删 除", style = PixelButtonStyle.ERROR, fontSize = 14.sp, enabled = !selecting,
                    onClick = { onDelete(group) })
            }
            else -> {}
        }
    }
}

/** 单张队列卡：奶白小卡 = 标题 · 平台红字 + 分态行（排位/进度/等待/错误）+ 动作按钮（设计）。
 * 多选态：点卡切换选中（橙粗边框高亮）、内钮禁用、长按进多选由父层给出。 */
@Composable
private fun QueueCard(
    item: QueueItem,
    position: Int,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (QueueItem) -> Unit,
    onOpenShelf: () -> Unit,
    selecting: Boolean,
    selected: Boolean,
    onLongPress: (() -> Unit)?,
    onToggleSelect: () -> Unit,
) {
    val c = PixelTheme.colors
    val cardShape = RoundedCornerShape(5.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, cardShape)
            .border(if (selected) 3.dp else 2.dp, if (selected) c.primary else c.outline, cardShape)
            .pointerInput(item.id, selecting, onLongPress) {
                detectTapGestures(
                    onTap = { if (selecting) onToggleSelect() },
                    onLongPress = { if (!selecting) onLongPress?.invoke() },
                )
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, color = c.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(5.dp))
            Text(item.platform, color = c.error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(5.dp))
        when (item.state) {
            QueueState.Running -> {
                Text("${item.modeLabel}下载中 ${(item.progressFraction * 100).toInt()}%",
                    color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                PixelProgressBar(item.progressFraction)
                Spacer(Modifier.height(7.dp))
 // Cancelling=Running UI 子态：按钮显「取消中…」并禁用（防连点）
                PixelButton(
                    if (item.cancelling) "取消中…" else "取 消",
                    enabled = !item.cancelling,
                    style = PixelButtonStyle.SUCCESS,
                    fontSize = 14.sp,
                    onClick = { onCancel(item.id) },
                )
            }
            QueueState.Queued -> {
                Text("排队中 第 $position 位", color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, fontSize = 14.sp, onClick = { onCancel(item.id) })
            }
            QueueState.WaitingNetwork -> {
                Text("等待网络", color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, fontSize = 14.sp, onClick = { onCancel(item.id) })
            }
            QueueState.Failed -> {
                Text(item.error ?: "下载失败", color = c.error, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PixelButton("重 试", fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = { onRetry(item.id) })
                    PixelButton("删 除", style = PixelButtonStyle.ERROR, fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = { onDelete(item) })
                }
            }
            QueueState.Exists -> { // 库判重命中——已在货架不重下，「去 货 架」直达（设计）
                Text("已在货架上", color = c.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PixelButton("去 货 架", fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = onOpenShelf)
                    PixelButton("删 除", style = PixelButtonStyle.ERROR, fontSize = 14.sp, enabled = !selecting,
                        modifier = Modifier.weight(1f), onClick = { onDelete(item) })
                }
            }
            else -> {}
        }
    }
}

/** 深棕 #4A3B2A 圆角区，内嵌奶白小卡：▶ 标题 · 大小 — 平台名红字（设计）；空态「今日还没开张」 */
@Composable
private fun ShipmentArea(recent: List<ShelfItem>, onPlay: (ShelfItem) -> Unit) {
    val c = PixelTheme.colors
    val regionShape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.outline, regionShape)
            .border(2.dp, c.outline, regionShape)
            .padding(6.dp),
    ) {
        if (recent.isEmpty()) {
            Text(
                "今日还没开张",
                color = c.onDarkBrown.copy(alpha = 0.55f), fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                recent.forEach { item ->
                    val cardShape = RoundedCornerShape(5.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.surface, cardShape)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onPlay(item) }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    ) {
                        PixelIcon(Sprites.PLAY, c.primary, iconSize = 9.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(item.name, color = c.textPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(5.dp))
                        Text("· ${formatSize(item.sizeBytes)}", color = c.textSecondary, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(item.platform, color = c.error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 引擎原样日志区（设计-5 语义保留）：深棕内嵌 + 奶白行 */
@Composable
private fun LogArea(lines: List<String>, listState: androidx.compose.foundation.lazy.LazyListState) {
    val c = PixelTheme.colors
    Column {
        Text("状态日志", color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(128.dp)
                .background(c.outline, RoundedCornerShape(8.dp))
                .padding(6.dp),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp)) {
                items(lines) { line ->
                    val color = when {
                        line.contains("✔") -> c.success
                        line.contains("✘") -> c.error
                        line.contains("⏹") || line.contains("已请求取消") -> c.gold
                        else -> c.onDarkBrown
                    }
                    PixelLogLine(line, color, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
    }
}
