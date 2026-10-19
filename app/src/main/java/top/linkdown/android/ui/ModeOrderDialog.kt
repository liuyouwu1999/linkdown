package top.linkdown.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.engine.internal.expandShortlink
import top.linkdown.android.engine.internal.isBatchUrl
import top.linkdown.android.engine.internal.isShortlinkUrl
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.pipeline.ErrorMapper
import top.linkdown.android.queue.MAX_GROUP_CHILDREN
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

/** 弹窗阶段：勾选 → 解析加载态 → 批量二段确认 → 枚举抛错人话（按设计）。 */
private sealed interface OrderStage {
    data object Modes : OrderStage
    data object Parsing : OrderStage
    data class Confirm(val info: BatchInfo) : OrderStage
    data class Error(val message: String) : OrderStage
}

/**
 * 勾选弹窗（功能任务 提取为 internal 共享； 批量二段确认）：主页「下 单！」与
 * ShareActivity 分享卡两入口共用。底部弹窗：标题页签「要点什么」+ 三行大触控勾选 +「开 始」（≥1 项）
 * 与「取 消」；批量门（[isBatchUrl]）过门 → 加载态「解析列表…」→ 二段确认「列表标题 · N 条」
 * （超 [MAX_GROUP_CHILDREN] 追加「共 N 条，将截取前 200」人话）；混合型 URL 给「仅下这一个」逃逸。
 * 枚举抛错 → 弹窗人话，不入队任何条目（设计）。
 */
@Composable
internal fun ModeOrderDialog(
    url: String,
    initial: DownloadModes,
    enumerate: suspend (String) -> BatchInfo,
    onConfirmSingle: (modes: DownloadModes, url: String, probeTitle: String?) -> Unit,
    onConfirmBatch: (modes: DownloadModes, title: String, children: List<ChildRef>) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = PixelTheme.colors
    var sel by remember { mutableStateOf(initial) }
    var stage by remember { mutableStateOf<OrderStage>(OrderStage.Modes) }
    val scope = rememberCoroutineScope()

    fun startFlow() {
 // 混合型默认与「仅下这一个」逃逸同走单视频路径（不过前置门不枚举；--no-playlist 由单视频路径恒带，
 // 只下点名的那一个——设计「混合型 URL 默认」）
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
                            else -> "要点什么"
                        }
                        Text(tabTitle, color = c.onDarkBrown, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(11.dp))
                    when (val s = stage) {
                        OrderStage.Modes -> {
                            PixelCheckRow("视频 mp4", Sprites.CAMERA, "1080P", sel.video) { sel = sel.copy(video = !sel.video) }
                            Spacer(Modifier.height(7.dp))
                            PixelCheckRow("仅音频", Sprites.NOTE, "mp3", sel.audio) { sel = sel.copy(audio = !sel.audio) }
                            Spacer(Modifier.height(7.dp))
                            PixelCheckRow("封面", Sprites.IMAGE, "jpg", sel.cover) { sel = sel.copy(cover = !sel.cover) }
                            if (isMixedListUrl(url)) {
                                Spacer(Modifier.height(7.dp))
                                Text(
                                    "链接带着列表 → 默认只下这一个；想收整个列表请粘贴列表页链接",
                                    color = c.textSecondary, fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val anySelected = sel.video || sel.audio || sel.cover
                                if (isMixedListUrl(url)) {
                                    PixelButton(
                                        "仅下这一个", enabled = anySelected, icon = Sprites.CHECK,
                                        onClick = { onConfirmSingle(sel, url, null) }, modifier = Modifier.weight(1f),
                                    )
                                }
                                PixelButton(
                                    "开 始", enabled = anySelected,
                                    onClick = { startFlow() }, modifier = Modifier.weight(1f),
                                )
                                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.weight(1f))
                            }
                        }
                        OrderStage.Parsing -> {
                            Text("解析列表…", color = c.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.height(9.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(13.dp))
 // 关闭=取消枚举（丢弃 Future 自然结束，不入队任何条目）
                            PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
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
                                PixelButton(
                                    "开 始",
                                    onClick = { onConfirmBatch(sel, s.info.title, s.info.children) },
                                    modifier = Modifier.weight(1f),
                                )
                                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.weight(1f))
                            }
                        }
                        is OrderStage.Error -> {
                            Text(s.message, color = c.error, fontSize = 12.sp)
                            Spacer(Modifier.height(13.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PixelButton("返 回", onClick = { stage = OrderStage.Modes }, modifier = Modifier.weight(1f))
                                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
