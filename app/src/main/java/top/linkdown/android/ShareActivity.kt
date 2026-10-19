package top.linkdown.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.YtdlpEngine
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.queue.DownloadQueue
import top.linkdown.android.queue.EnqueueResult
import top.linkdown.android.queue.activePositionOf
import top.linkdown.android.ui.ModeOrderDialog
import top.linkdown.android.ui.PixelIcon
import top.linkdown.android.ui.PixelPanel
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites

/** URL 合法字符集（RFC3986 unreserved+保留字+% 转义；正则限定即天然截断 CJK 与空白，设计） */
private val URL_REGEX = Regex("https?://[A-Za-z0-9\\-._~:/?#@!$&'()*+,;=%\\[\\]]+")

/** 尾部 CJK 标点剥离集（设计逐字：。，、！？）】」） */
private const val TRAILING_CJK_PUNCT = "。，、！？）】」"

/**
 * 分享文本 → http(s) 链接列表（设计）：正则限定 URL 合法字符集 + 去尾部 CJK 标点，
 * 按出现序返回全部命中（首个为下单对象；多条由调用方 Toast「已识别第 1 条链接」）。
 * 纯函数，JVM 单测直测（ShareUrlExtractorTest）。
 */
internal fun extractShareUrls(text: String): List<String> =
    URL_REGEX.findAll(text).map { it.value.trimEnd(*TRAILING_CJK_PUNCT.toCharArray()) }.toList()

/**
 * 系统分享卡片（与批量入口同流程）：ACTION_SEND text/plain → 提取首个 http(s) URL →
 * 像素下载卡（复用勾选弹窗 [ModeOrderDialog]：批量门 → 加载态「解析列表…」→ 二段确认「标题 · N 条」；
 * 枚举抛错弹窗人话不入队）→ enqueue/enqueueGroup（FGS 唯一触发点在队列内自动起）→
 * **入队完成才**「已加入队列」并延后 finish；去重命中 Toast「已在队列中」+ finish；
 * 无 URL（含非 http 文本）→ 人话 + finish。sessdata 快照恒 null（弹窗无此栏，执行时回落已存 cookie）。
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统条适配（同 MainActivity）：Android 15+ 强制 edge-to-edge，条区让位交给根布局 safeDrawingPadding
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
 // 分享入口可冷启动（不经主页）：引擎初始化幂等（initOnce），队内真跑依赖 Chaquopy 就绪
        YtdlpEngine.initOnce(applicationContext)
        val urls = extractShareUrls(intent?.getStringExtra(Intent.EXTRA_TEXT) ?: "")
        val url = urls.firstOrNull()
        if (url == null) {
            toast("没认出视频链接：请分享视频页的链接再试")
            finish()
            return
        }
        if (urls.size > 1) toast("已识别第 1 条链接")
        val app = application as LinkdownApplication
        setContent {
            PixelTheme {
                ShareCard(
                    queue = app.queue,
                    enumerate = { u -> app.pipeline.enumerate(u, null) }, // 入口枚举缝（sessdata 恒 null）
                    url = url,
                    onClose = { finish() },
                )
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 分享下单卡：勾选弹窗（含批量二段确认/加载态）→ 入队完成 → 「已加入队列」像素卡停 2 秒 → [onClose] */
@Composable
private fun ShareCard(
    queue: DownloadQueue,
    enumerate: suspend (String) -> BatchInfo,
    url: String,
    onClose: () -> Unit,
) {
    val c = PixelTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
 // 入队完成才置位（单视频=第 N 位；父组=N 条）——finish 随之延后（设计）
    var queuedSummary by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier.background(c.darkBrown).safeDrawingPadding().fillMaxSize().background(c.background),
        contentAlignment = Alignment.Center,
    ) {
        val summary = queuedSummary
        if (summary != null) {
            PixelPanel {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PixelIcon(Sprites.BOX, c.darkBrown, accent = c.gold, iconSize = 20.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(summary, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    if (queuedSummary == null) {
        ModeOrderDialog(
            url = url,
            initial = DownloadModes(false, false, false),
            enumerate = enumerate,
            onConfirmSingle = { modes, u, _ ->
                scope.launch {
                    when (val r = queue.enqueue(u, modes, sessdataSnapshot = null)) {
                        is EnqueueResult.Enqueued -> {
                            queuedSummary = "已加入队列（第 ${queue.state.value.activePositionOf(r.item.id)} 位）"
                            delay(2000) // 设计：卡片停留 2 秒再收
                            onClose()
                        }
                        EnqueueResult.Duplicate -> {
                            Toast.makeText(context, "已在队列中", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    }
                }
            },
            onConfirmBatch = { modes, title, children ->
                scope.launch {
                    when (val r = queue.enqueueGroup(title, url, modes, children, sessdataSnapshot = null)) {
                        is EnqueueResult.Enqueued -> {
 // 父组不出独立排位（设计）：摘要显 N 条
                            queuedSummary = "已加入队列（${r.item.totalChildren} 条）"
                            delay(2000)
                            onClose()
                        }
                        EnqueueResult.Duplicate -> {
                            Toast.makeText(context, "已在队列中", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    }
                }
            },
            onDismiss = onClose,
        )
    }
}
