package top.linkdown.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.ProbeInfo
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.engine.internal.VideoScope
import top.linkdown.android.engine.internal.expandShortlink
import top.linkdown.android.engine.internal.isBatchUrl
import top.linkdown.android.engine.internal.isShortlinkUrl
import top.linkdown.android.engine.internal.isVideoUrl
import top.linkdown.android.engine.internal.ScopeFlowAction
import top.linkdown.android.engine.internal.scopeChildren
import top.linkdown.android.engine.internal.scopeFlowNext
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.EngineKind
import top.linkdown.android.pipeline.EngineRouter
import top.linkdown.android.pipeline.ErrorMapper
import top.linkdown.android.pipeline.PlatformDirs
import top.linkdown.android.queue.DedupCheck
import top.linkdown.android.queue.MAX_GROUP_CHILDREN
import top.linkdown.android.queue.MediaStoreShelfQuery
import kotlinx.coroutines.launch

/**
 * 弹窗枚举后的判型决策纯函数（JVM 直测，设计）：空子项=单视频回退（原 URL——前置门误判/
 * dump 判型 video）；entries==1=子项 URL 单视频回退（回退路径带 --no-playlist）；其余=二段确认。
 */
internal sealed interface BatchNextStep {
    data class Single(val url: String) : BatchNextStep
    data class Confirm(val info: BatchInfo) : BatchNextStep
}

internal fun nextStepAfterEnumerate(url: String, info: BatchInfo): BatchNextStep = when {
    info.children.isEmpty() -> BatchNextStep.Single(url)
    info.children.size == 1 -> BatchNextStep.Single(info.children.first().url)
    else -> BatchNextStep.Confirm(info)
}

/**
 * 混合型 watch&list 判定（设计）：不过批量前置门但带 list= 参数 → 勾选弹窗给
 * 「仅下这一个」逃逸（--no-playlist；单视频路径恒带，逃逸按钮为显式逃生语义）。
 */
internal fun isMixedListUrl(url: String): Boolean =
    !isBatchUrl(url) && Regex("[?&]list=").containsMatchIn(url)

/** 多链接逐条判型后的行（JVM 可测）：group=null 单曲（enqueueUrl 为实际入队链接）；Failed 不入队。 */
internal sealed interface OrderLine {
    val url: String

    data class Ready(
        override val url: String,  // 原链接（合集整组的 source_url）
        val enqueueUrl: String,    // 单曲实际入队链接（直连原链接 / 枚举出的子项回退）
        val label: String,         // 「单曲」或「标题 · N 条」
        val probeTitle: String?,   // 单曲预取名（枚举拿到标题时）
        val group: BatchInfo?,     // 非 null = 合集整组
    ) : OrderLine

    data class Failed(override val url: String, val message: String) : OrderLine
}

/** 多链接整批回调的载荷：单曲与合集组各一摞，循环入队由弹窗外层做（弹窗协程随关闭取消）。 */
internal data class MultiSingleOrder(val url: String, val probeTitle: String?)
internal data class MultiGroupOrder(val url: String, val title: String, val children: List<ChildRef>)

/** 多链接合集行标签：长标题截断 + 条数；超 [MAX_GROUP_CHILDREN] 追加截取人话（与单条确认一致）。 */
internal fun groupLabel(info: BatchInfo): String {
    val title = if (info.title.length > 14) info.title.take(14) + "…" else info.title
    val n = info.children.size
    return if (n > MAX_GROUP_CHILDREN) "$title · $n 条（截取前 $MAX_GROUP_CHILDREN）" else "$title · $n 条"
}

/**
 * 多链接单条判型（弹窗内串行执行）：短链先展开（失败回退原链接，不阻塞）；非批量门 → 直连单曲；
 * 过门 → 枚举 → [nextStepAfterEnumerate]（单曲回退 / 合集整组）；抛错 → 人话 Failed 行，不拖垮整批。
 * [expand] 注入只为 JVM 直测短链分支（默认真网络展开，与单链接流同语义）。
 */
internal suspend fun resolveOrderLine(
    url: String,
    enumerate: suspend (String) -> BatchInfo,
    expand: suspend (String) -> String = ::expandShortlink,
): OrderLine {
    val target = when {
        !isShortlinkUrl(url) -> url
        else -> try {
            expand(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            url
        }
    }
    return try {
        if (!isBatchUrl(target)) return OrderLine.Ready(url, target, "单曲", null, null)
        val info = enumerate(target)
        when (val step = nextStepAfterEnumerate(target, info)) {
            is BatchNextStep.Single -> OrderLine.Ready(url, step.url, "单曲", info.title, null)
            is BatchNextStep.Confirm -> OrderLine.Ready(url, url, groupLabel(info), null, info)
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        OrderLine.Failed(url, if (t is EngineException) (t.message ?: "解析列表失败") else ErrorMapper.humanize(t))
    }
}

/** 弹窗阶段：勾选 → 解析加载态 → 批量二段确认 → 枚举抛错人话（按设计）；多链接另有逐条解析/列表确认。
 * Modes.imageCount 非 null = 开窗预判型判得图文（勾选行随内容自适应）；Sniffing = 预判型进行中。
 * internal：[initialOrderStage] 签名暴露（JVM 直测经 ::class/toString 断言）。 */
internal sealed interface OrderStage {
    data class Modes(val imageCount: Int?) : OrderStage
    data object Sniffing : OrderStage
    data object Parsing : OrderStage
    data class Confirm(val info: BatchInfo) : OrderStage
    data class ScopeChoice(
        val scope: top.linkdown.android.engine.internal.VideoScope,
        val srcUrl: String,              // startFlow 展开后 target（fix 1：b23.tv 裸短链不得回传）
    ) : OrderStage
    data class ScopeList(
        val info: top.linkdown.android.engine.BatchInfo,
        val exists: List<Boolean>,       // 与 info.children 对齐（预查判重）
        val listTitle: String,           // page=视频标题 / season=合集标题
        val which: String,               // "page" | "season"
        val srcUrl: String,              // startFlow 展开后 target（同上；回调 url 形参非空来源）
    ) : OrderStage
    data class MultiParsing(val done: Int, val total: Int) : OrderStage
    data class MultiConfirm(val lines: List<OrderLine>) : OrderStage
    data class Error(val message: String) : OrderStage
}

/**
 * 图文能力引擎（ 有什么下什么 ）：抖音/小红书/Telegram 单链接开窗先 probe 判图文/视频，
 * 勾选项自适应（图文→「图片·N 张+封面」，无音频行）；判型失败回退通用三行（引擎侧守卫兜底）。
 * 微博恒无图文、yt-dlp/汽水判型慢且恒无图文——都不预判（B 站等保持弹窗秒开）。
 * top-level internal：JVM 直测（routeFor 纯函数）。
 */
internal val IMAGE_CAPABLE_KINDS = setOf(EngineKind.DOUYIN, EngineKind.XHS, EngineKind.TELEGRAM)

internal fun isImageCapableUrl(url: String): Boolean = EngineRouter.routeFor(url) in IMAGE_CAPABLE_KINDS

/** 开窗初始阶段：图文能力单链接先进 Sniffing（判型完成落 Modes(imageCount)），其余直接通用勾选。 */
internal fun initialOrderStage(single: String?): OrderStage = when {
    single != null && isImageCapableUrl(single) -> OrderStage.Sniffing
    else -> OrderStage.Modes(null)
}

/** probe 结果 → 图文张数（hasImages 且 playUrl「;」并直链非空）；视频帖/异常 → null=通用三行。 */
internal fun imageCountOf(info: ProbeInfo): Int? =
    info.takeIf { it.hasImages }?.playUrl
        ?.split(";")?.count { it.isNotBlank() }
        ?.takeIf { it > 0 }

/**
 * 勾选弹窗（功能任务 提取为 internal 共享； 批量二段确认）：主页「下 单！」与
 * ShareActivity 分享卡两入口共用。底部弹窗：标题页签「要点什么」+ 三行大触控勾选 +「开 始」（≥1 项）
 * 与「取 消」；批量门（[isBatchUrl]）过门 → 加载态「解析列表…」→ 二段确认「列表标题 · N 条」
 * （超 [MAX_GROUP_CHILDREN] 追加「共 N 条，将截取前 200」人话）；混合型 URL 给「仅下这一个」逃逸；
 * 多链接（[urls].size > 1）→ 逐条判型「解析 i/N …」→ 列表勾选确认（失败行标 ✕ 不可选）→
 * 整批交 [onConfirmMulti]（循环入队在弹窗外层——第一条回调即关弹窗，弹窗协程不能再承载后续入队）。
 * 枚举抛错 → 弹窗人话，不入队任何条目（设计）。
 */
@Composable
internal fun ModeOrderDialog(
    urls: List<String>,
    initial: DownloadModes,
    enumerate: suspend (String) -> BatchInfo,
    probeFor: suspend (String) -> ProbeInfo,
    videoScopeProbe: top.linkdown.android.engine.VideoScopeProbe =
        top.linkdown.android.engine.HttpVideoScopeProbe(LocalContext.current.filesDir),
    onConfirmSingle: (modes: DownloadModes, url: String, probeTitle: String?) -> Unit,
    onConfirmBatch: (modes: DownloadModes, url: String, title: String, children: List<ChildRef>) -> Unit,
    onConfirmMulti: (modes: DownloadModes, singles: List<MultiSingleOrder>, groups: List<MultiGroupOrder>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = PixelTheme.colors
    val context = LocalContext.current // B 站范围流：识别失败 Toast + 预查判重 MediaStore 查询
    val single = urls.singleOrNull()   // 单链接路径维持原行为；多链接走 Multi* 阶段
    // 封面恒随单下载（用户拍板 2026-11-08：弹窗不提供封面勾选项）：sel.cover 恒 true，
    // 阅读器音频/视频纸用书内封面页当缩略图；判重对已下载封面自动跳过
    var sel by remember { mutableStateOf(initial.copy(cover = true)) }
    var stage by remember { mutableStateOf<OrderStage>(initialOrderStage(single)) }
    var multiPicked by remember { mutableStateOf(emptySet<Int>()) }
    val scope = rememberCoroutineScope()

 // 开窗预判型（ 有什么下什么 ）：图文能力引擎单链接先 probe（重定向+ttwid+share 页等数次快 HTTP，
 // 境外不可达域名等满连接超时后回退通用三行），判得图文 →
 // 勾选行自适应为「图片·N 张+封面」并清掉 remembered 音频勾选；视频帖/判型失败 → 通用三行原样
    LaunchedEffect(single) {
        if (single != null && stage is OrderStage.Sniffing) {
            val imageCount = try {
                imageCountOf(probeFor(single))
            } catch (ce: CancellationException) {
                throw ce // 弹窗关闭取消穿透不吞
            } catch (_: Throwable) {
                null // 判型失败：回退通用三行（引擎侧音频守卫兜底）
            }
            if (imageCount != null) sel = sel.copy(audio = false)
            stage = OrderStage.Modes(imageCount)
        }
    }

    fun startFlow() {
        if (single == null) {
 // 防御：空列表不进解析（当前调用点不可达——isValidInput 门控 + ShareActivity 恒单条，
 // 防未来调用点踩雷：空 lines 会让 all{Failed} 恒真 → first() 崩）
            if (urls.isEmpty()) return
 // 多链接：串行逐条判型（并行会同时起 N 个提取器，手机内存/占用不可控），行进度「i/N」反馈
            scope.launch {
                val lines = mutableListOf<OrderLine>()
                urls.forEachIndexed { i, u ->
                    stage = OrderStage.MultiParsing(i, urls.size)
                    lines += resolveOrderLine(u, enumerate)
                }
                if (lines.all { it is OrderLine.Failed }) {
                    stage = OrderStage.Error(lines.filterIsInstance<OrderLine.Failed>().first().message)
                } else {
                    multiPicked = lines.indices.filter { lines[it] is OrderLine.Ready }.toSet()
                    stage = OrderStage.MultiConfirm(lines.toList())
                }
            }
            return
        }
 // 混合型默认与「仅下这一个」逃逸同走单视频路径（不过前置门不枚举；--no-playlist 由单视频路径恒带，
 // 只下点名的那一个——设计「混合型 URL 默认」）
        val url = single
        if (isShortlinkUrl(url)) stage = OrderStage.Parsing // 短链要先过一次网络展开，先给加载态
        scope.launch {
 // 短链展开（web 端由 shortlink.py 承担的同一步；yt-dlp 提取器不认 b23.tv 等短域，
 // 裸短链落 generic 提取器报错）；失败回退原链接，不得阻塞下单；取消必须穿透
 //（runCatching 会吞 CancellationException——弹窗关闭取消后若回退原链接会反向入队必失败的单）
            val target = when {
                !isShortlinkUrl(url) -> url
                else -> try {
                    expandShortlink(url)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    url
                }
            }
 // cover 模式在本弹窗不可达（封面不提供勾选项，用户拍板 2026-11-08；sel 无 cover 位），
 // 故无 mode!=COVER 守卫位——若未来 sel 增加 cover 勾选，此处必须先补排除再 probe
            if (isVideoUrl(target)) {
                stage = OrderStage.Sniffing        // 「识别内容…」加载态（协程随关闭取消）
                val res = try {
                    videoScopeProbe.probe(target)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                when (scopeFlowNext(res)) {        // 纯决策（T7，JVM 已测）
                    ScopeFlowAction.SINGLE_TOAST -> {
                        Toast.makeText(context, "合集/分P识别失败，已按单个视频下单",
                            Toast.LENGTH_SHORT).show()
                        onConfirmSingle(sel, target, null)
                    }
 // gone（SINGLE_PLAIN）不 toast 识别注（spec §7：下载必失败，失败信息经队列
 // Failed 态呈现、用户必见）——与 web「error 不并入注」同一口径
                    ScopeFlowAction.SINGLE_PLAIN -> onConfirmSingle(sel, target, null)
                    ScopeFlowAction.SHOW_CHOICE -> stage = OrderStage.ScopeChoice(res!!.scope!!, target)
                }
                return@launch
            }
            if (!isBatchUrl(target)) {
                onConfirmSingle(sel, target, null)  // 直连路径未枚举，无预取名
                return@launch
            }
            stage = OrderStage.Parsing // 加载态「解析列表…」；关闭弹窗=丢弃结果 Future 自然结束不入队
            val info = try {
                enumerate(target)
            } catch (ce: CancellationException) {
                throw ce // 协程取消穿透不吞
            } catch (t: Throwable) {
                stage = OrderStage.Error(
                    if (t is EngineException) (t.message ?: "解析列表失败") else ErrorMapper.humanize(t),
                )
                return@launch
            }
            when (val step = nextStepAfterEnumerate(target, info)) {
                is BatchNextStep.Single -> onConfirmSingle(sel, step.url, info.title)
                is BatchNextStep.Confirm -> stage = OrderStage.Confirm(step.info)
            }
        }
    }

 // B 站范围流：清单预查（预查键 T7 纯函数已预计算；MediaStore 查询隔离 IO，先给 Parsing 加载态）
    fun openScopeList(sj: VideoScope, srcUrl: String, listTitle: String, which: String) {
        stage = OrderStage.Parsing        // 预查加载态（MediaStore 查询离开主线程）
        scope.launch {
            try {
                val (title, kids) = scopeChildren(sj, which)
                val exists = withContext(Dispatchers.IO) {
                    val dedup = DedupCheck(MediaStoreShelfQuery(context))
                    val modes = buildList {
                        if (sel.video) add(Mode.VIDEO)
                        if (sel.audio) add(Mode.AUDIO)
                    }
 // 平台 token 必须是落库目录名：PlatformDirs.dirFor(url)→「哔哩哔哩」
 // （YtdlpEngine.kt:71 先例；传 "bilibili" 查空集→灰显恒失效，Review Focus 6）
                    val platform = PlatformDirs.dirFor(kids.firstOrNull()?.url ?: "")
                    kids.map { child ->
                        if (modes.isEmpty()) false
                        else modes.any { m -> dedup.exists(platform, child.precheckKey, m) }
                    }
                }
                stage = OrderStage.ScopeList(
                    info = BatchInfo(title = title, children = kids.map { ChildRef(it.url, it.title) },
                        entriesTotal = kids.size),
                    exists = exists, listTitle = title, which = which, srcUrl = srcUrl)
            } catch (ce: CancellationException) {
                throw ce // 弹窗关闭取消穿透不吞（照同文件 enumerate 路径先例）
            } catch (t: Throwable) {
                // MediaStore 查询抛异常（权限运行中被撤等）→ Error 态，防卡 Parsing/崩溃
                stage = OrderStage.Error("预查失败：${ErrorMapper.humanize(t)}")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .drawBehind {
                        drawRoundRect(c.panelShadow, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(10.dp.toPx()))
                    }
                    .background(c.surface, shape)
                    .border(3.dp, c.outline, shape)
                    .padding(12.dp),
            ) {
                Column {
 // 标题页签：深棕底奶油字 + 靶心像素图标
                    val tabShape = RoundedCornerShape(6.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.background(c.darkBrown, tabShape).border(2.dp, c.outline, tabShape)
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    ) {
                        PixelIcon(Sprites.TARGET, c.onDarkBrown, accent = c.gold, iconSize = 12.dp)
                        Spacer(Modifier.width(5.dp))
                        val tabTitle = when (val s = stage) {
                            is OrderStage.Confirm -> "${s.info.title} · ${s.info.children.size} 条"
                            is OrderStage.MultiParsing -> "要点什么 · ${s.total} 个链接"
                            is OrderStage.MultiConfirm ->
                                "已解析 ${s.lines.count { it is OrderLine.Ready }}/${s.lines.size} 条"
                            else -> "要点什么"
                        }
                        Text(tabTitle, color = c.onDarkBrown, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(11.dp))
                    when (val s = stage) {
                        is OrderStage.Modes -> {
                            if (s.imageCount != null) {
 // 图文自适应（ 有什么下什么 ）：没有视频/音频流——「视频」勾选位即图片交付（管道映射 Mode.VIDEO→多图）
                                PixelCheckRow("图片 · ${s.imageCount} 张", Sprites.IMAGE, "jpg", sel.video) {
                                    sel = sel.copy(video = !sel.video)
                                }
                            } else {
                                PixelCheckRow("视频 mp4", Sprites.CAMERA, "1080P", sel.video) { sel = sel.copy(video = !sel.video) }
                                Spacer(Modifier.height(7.dp))
                                PixelCheckRow("仅音频", Sprites.NOTE, "mp3", sel.audio) { sel = sel.copy(audio = !sel.audio) }
                            }
                            if (s.imageCount == null && single != null && isMixedListUrl(single)) {
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    "链接带着列表 → 默认只下这一个；想收整个列表请粘贴列表页链接",
                                    color = c.textSecondary, fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 封面不再提供勾选项（用户拍板 2026-11-08）：恒随单下载，开始钮只看视频/音频
                                val anySelected = sel.video || sel.audio
                                PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.weight(1f))
                                if (s.imageCount == null && single != null && isMixedListUrl(single)) {
                                    PixelButton(
                                        "仅下这一个", enabled = anySelected, icon = Sprites.CHECK,
                                        onClick = { onConfirmSingle(sel, single, null) }, modifier = Modifier.weight(1f),
                                    )
                                }
                                PixelButton(
                                    "开 始", enabled = anySelected,
                                    onClick = { startFlow() }, modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        OrderStage.Sniffing -> {
                            Text("识别内容…", color = c.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(9.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(13.dp))
 // 关闭=取消预判型（协程随组合销毁自然结束，未入队任何条目——与 Parsing 同语义）
                            PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                        }
                        OrderStage.Parsing -> {
                            Text("解析列表…", color = c.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(9.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(13.dp))
 // 关闭=取消枚举（丢弃 Future 自然结束，不入队任何条目）
                            PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                        }
                        is OrderStage.Confirm -> {
                            if (s.info.children.size > MAX_GROUP_CHILDREN) {
                                Text(
                                    "共 ${s.info.entriesTotal} 条，将截取前 $MAX_GROUP_CHILDREN",
                                    color = c.error, fontSize = 12.sp,
                                )
                                Spacer(Modifier.height(7.dp))
                            }
                            Text("将按上面勾选逐条下载", color = c.textSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.weight(1f))
                                PixelButton(
                                    "开 始",
                                    onClick = {
                                        val src = single   // 二段确认只在单链接流可达
                                        if (src != null) onConfirmBatch(sel, src, s.info.title, s.info.children)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        is OrderStage.ScopeChoice -> {
                            val sj = s.scope
                            Text("这条链接包含", color = c.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(9.dp))
                            if (sj.pages.size > 1) PixelButton(
                                "全部分P · ${sj.pages.size} 个",
                                icon = Sprites.CHECK,
                                onClick = { openScopeList(sj, s.srcUrl, sj.title, "page") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (sj.episodes.isNotEmpty()) PixelButton(
                                "整个合集「${sj.seasonTitle ?: ""}」· ${sj.episodes.size} 集" +
                                    if (sj.truncated) "（不完整 ${sj.episodes.size}/${sj.epCount}）" else "",
                                icon = Sprites.CHECK,
                                onClick = {
                                    openScopeList(sj, s.srcUrl, sj.seasonTitle ?: sj.title, "season")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(7.dp))
                            if (sj.truncated) Text(
                                "⚠ 合集不完整（仅获得 ${sj.episodes.size}/${sj.epCount} 集），可照常下载可见部分",
                                color = c.error, fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(7.dp))
 // srcUrl=startFlow 展开后 target（非空 String，短链已展开——b23.tv 裸链 yt-dlp 不认必失败，
 // spec §4「现行为」口径；不用 single 原链接，也无须可空性判断；fix 1）
                            PixelButton("只下当前视频（点名的那一P）",
                                style = PixelButtonStyle.NEUTRAL,
                                onClick = { onConfirmSingle(sel, s.srcUrl, null) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(7.dp))
                            PixelButton("取 消", style = PixelButtonStyle.NEUTRAL,
                                onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                        }
                        is OrderStage.ScopeList -> {
                            if (s.info.children.size > MAX_GROUP_CHILDREN) Text(
                                "共 ${s.info.children.size} 条，将截取前 $MAX_GROUP_CHILDREN",
                                color = c.error, fontSize = 12.sp)
                            var picked by remember(s.info) {
                                mutableStateOf(s.info.children.indices
                                    .filter { !s.exists[it] }.toSet())
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                                itemsIndexed(s.info.children) { idx, child ->
                                    PixelCheckRow(child.title, Sprites.NOTE, "mp4", idx in picked) {
                                        picked = if (idx in picked) picked - idx else picked + idx
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Text("已选 ${picked.size}/${s.info.children.size}",
                                color = c.textSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("取 消", style = PixelButtonStyle.NEUTRAL,
                                    onClick = onDismiss, modifier = Modifier.weight(1f))
                                PixelButton("开 始 (${picked.size})", enabled = picked.isNotEmpty(),
                                    icon = Sprites.CHECK,
                                    onClick = {
 // srcUrl=startFlow 展开后 target（父 URL 非空、短链已展开，fix 1；无须 single 可空性判断）
                                        onConfirmBatch(sel, s.srcUrl, s.listTitle,
                                            s.info.children.filterIndexed { i, _ -> i in picked })
                                    },
                                    modifier = Modifier.weight(1f))
                            }
                        }
                        is OrderStage.MultiParsing -> {
                            Text("解析链接 ${s.done + 1}/${s.total} …", color = c.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(9.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(13.dp))
 // 关闭=取消解析（弹窗协程随组合销毁，未入队任何条目——与单条 Parsing 同语义）
                            PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                        }
                        is OrderStage.MultiConfirm -> {
                            Text("将按勾选逐条下单；合集整组入队", color = c.textSecondary, fontSize = 11.sp)
                            Spacer(Modifier.height(7.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier
                                    .heightIn(max = 340.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                s.lines.forEachIndexed { i, line ->
                                    MultiLineRow(
                                        index = i,
                                        line = line,
                                        checked = i in multiPicked,
                                        onToggle = {
                                            multiPicked = if (i in multiPicked) multiPicked - i else multiPicked + i
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.weight(1f))
                                PixelButton("返 回", style = PixelButtonStyle.NEUTRAL, onClick = { stage = OrderStage.Modes(null) }, modifier = Modifier.weight(1f))
                                PixelButton(
                                    "开 始", enabled = multiPicked.isNotEmpty(),
                                    onClick = {
                                        val picked = s.lines.filterIndexed { idx, _ -> idx in multiPicked }
                                            .filterIsInstance<OrderLine.Ready>()
                                        val singles = picked.filter { it.group == null }
                                            .map { MultiSingleOrder(it.enqueueUrl, it.probeTitle) }
                                        val groups = picked.mapNotNull { l ->
                                            l.group?.let { MultiGroupOrder(l.url, it.title, it.children) }
                                        }
                                        onConfirmMulti(sel, singles, groups)
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        is OrderStage.Error -> {
                            Text(s.message, color = c.error, fontSize = 12.sp)
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("取 消", style = PixelButtonStyle.NEUTRAL, onClick = onDismiss, modifier = Modifier.weight(1f))
                                PixelButton("返 回", style = PixelButtonStyle.NEUTRAL, onClick = { stage = OrderStage.Modes(null) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 多链接确认列表行：像素勾选框语言与 [PixelCheckRow] 一致（紧凑单行版）；失败行 ✕ 不可选。 */
@Composable
private fun MultiLineRow(
    index: Int,
    line: OrderLine,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(6.dp)
    if (line is OrderLine.Ready) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(if (checked) c.selectedBg else Color.White, shape)
                .border(2.dp, c.outline, shape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { onToggle() }
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            val boxShape = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .size(16.dp)
                    .background(if (checked) c.primary else Color.White, boxShape)
                    .border(2.dp, c.outline, boxShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) PixelIcon(Sprites.CHECK, Color.White, iconSize = 10.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = c.textSecondary, fontSize = 11.sp,
            )
            Spacer(Modifier.width(7.dp))
            if (line.group == null) {
                Text(
                    line.enqueueUrl,
                    color = c.textPrimary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    line.label,
                    color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val failed = line as OrderLine.Failed
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, shape)
                .border(2.dp, c.outline, shape)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Text("✕", color = c.error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    failed.url,
                    color = c.textSecondary, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    failed.message,
                    color = c.error, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
