package top.linkdown.android.engine

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.engine.internal.parseEnumerateJson
import top.linkdown.android.pipeline.PlatformDirs
import top.linkdown.android.pipeline.ProgressParser
import top.linkdown.android.settings.SettingsValues

object YtdlpEngine : MediaEngine {

 /** 1080P 限档格式链（ 画质档）：最佳视频（≤1080）+最佳音频 合流，缺一则退单流。 */
    internal const val VIDEO_FORMAT_1080P = "bv*[height<=1080]+ba/b"

    private var appContext: Context? = null

 // B站空 DASH（风控）：重建请求重试，共 4 次、间隔 2s（上位设计文档 §4.2）
    private const val PROBE_ATTEMPTS = 4
    private const val PROBE_RETRY_INTERVAL_MS = 2_000L

 // ------------------------------------------------------------------
 // probe：yt-dlp --dump-single-json（stdout 恒空 → 经日志回调捕获解析）
 // ------------------------------------------------------------------
    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(PROBE_RETRY_INTERVAL_MS)
            try {
                return probeOnce(url, cookies, runId)
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
 // 仅 B站空 DASH 签名走重试；其余异常原样抛出（交 ErrorMapper，勿包泛化文案）
                if (cause.message?.contains("No video formats found") != true) throw cause
            }
        }
        throw EngineException("B站视频解析失败（风控），请在主页「登录账号」里登录 B站后重试")
    }

    private fun probeOnce(url: String, cookies: File?, runId: String): ProbeInfo {
        val lines = mutableListOf<String>()
        val req = YtDlpRequest(url).apply {
            addOption("--dump-single-json") // 单参旗标（fork _FLAG_MAP 第 4 项，未登记会被静默丢弃）
 // cookies 非空 → 同 download 通道注入 cookiefile（B站会员内容 probe 同样需要）
            cookies?.let { addOption("--cookies", it.absolutePath) }
        }
 // fork 2.0.3 破坏性签名：executeDebug 必填 runId 首参（per-run 取消注册表键，与 download 共用）
        val resp = YtDlp.executeDebug(runId, req, { _, msg -> lines.add(msg) }, null).get()
        if (resp.exitCode != 0) error("yt-dlp 退出码 ${resp.exitCode}（详见上方日志）")
 // 日志行拼接 JSON：首个 "{" 起始行 至 "}" 结束行
        val start = lines.indexOfFirst { it.trimStart().startsWith("{") }
        if (start < 0) error("probe 未捕获到 JSON 输出（--dump-single-json）")
        val end = lines.indexOfLast { it.trimEnd().endsWith("}") }.takeIf { it >= start }
            ?: error("probe JSON 不完整（未找到收尾行）")
        val root = Json.parseToJsonElement(lines.subList(start, end + 1).joinToString("\n"))
            as? JsonObject ?: error("probe JSON 结构异常（顶层非对象）")
        val media = (root["entries"] as? JsonArray)?.firstOrNull() as? JsonObject ?: root
        fun str(key: String): String? =
            (media[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        return ProbeInfo(
            platform = PlatformDirs.dirFor(url), // 引擎内部按 URL host 最长后缀推导
            mediaId = str("id") ?: url,
            sourceUrl = url, // probe 原样带回（mediaId 不可逆推 URL）
            title = str("title") ?: "",
            author = str("uploader") ?: str("channel") ?: str("uploader_id") ?: "",
            coverUrl = str("thumbnail"),
            durationSec = (media["duration"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?.content?.toDoubleOrNull(),
            isVip = false,   // yt-dlp 平台无 VIP 标志（label_info 仅汽水）
            playUrl = null,  // 设计：yt-dlp 平台恒 null（不拿直链绕过 DASH 合流）
            hasImages = false,
        )
    }

 // ------------------------------------------------------------------
 // enumerate（ 批量枚举）：--flat-playlist + --dump-single-json，
 // 日志捕获链路与 probeOnce 同款（stdout 恒空 → 经日志回调拼接 JSON）
 // ------------------------------------------------------------------
    override suspend fun enumerate(url: String, cookies: File?, runId: String): BatchInfo {
        val lines = mutableListOf<String>()
        val req = YtDlpRequest(url).apply {
 // 白名单第 5 项（fork 2.0.4）：未登记会被静默丢弃→非 flat 全量抽取（分钟级+单条死链打死整批）
            addOption("--flat-playlist")
            addOption("--dump-single-json") // 单参旗标：触发 fork 的 stdout→日志桥接
            cookies?.let { addOption("--cookies", it.absolutePath) }
        }
 // ⚠️ 取消语义：flat 抽取（列表网络拉取）无 progress 事件 → progress_hooks 取消钩子
 // 不会 fire，requestCancel(runId) 杀不掉进行中的抽取——入口取消=丢弃本 Future 结果
 // 不入队，Future 自然结束（勿在此造死等/强杀）。
        val resp = YtDlp.executeDebug(runId, req, { _, msg -> lines.add(msg) }, null).get()
        if (resp.exitCode != 0) error("yt-dlp 退出码 ${resp.exitCode}（详见上方日志）")
 // 日志行拼接 JSON：首个 "{" 起始行 至 "}" 结束行（同 probeOnce）
        val start = lines.indexOfFirst { it.trimStart().startsWith("{") }
        if (start < 0) error("enumerate 未捕获到 JSON 输出（--dump-single-json）")
        val end = lines.indexOfLast { it.trimEnd().endsWith("}") }.takeIf { it >= start }
            ?: error("enumerate JSON 不完整（未找到收尾行）")
        val root = Json.parseToJsonElement(lines.subList(start, end + 1).joinToString("\n"))
            as? JsonObject ?: error("enumerate JSON 结构异常（顶层非对象）")
        return BatchInfo(
            title = (root["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: url,
            children = parseEnumerateJson(root),
 // entries 原始条数（含被跳过的 null/无 id entry）——200 截断人话「共 N 条」的口径
            entriesTotal = (root["entries"] as? JsonArray)?.size ?: 0,
        )
    }

 // ------------------------------------------------------------------
 // download：现有实现平移；mode→opts 映射抽 [downloadOptsFor]（ 画质档接线，JVM 可测）
 // ------------------------------------------------------------------
    override suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  cancelHandle: CancelHandle, runId: String,
                                  quality: String): List<Path> {
        val outDir = taskDir.toFile()
        val before = outDir.listFiles { f -> f.isFile }?.map { it.name }?.toSet() ?: emptySet()
        val opts = downloadOptsFor(mode, audioFormat, quality)
        val ctx = appContext ?: error("YtdlpEngine 未初始化（initOnce 未调用）")
        run(ctx, info.sourceUrl, cookies, outDir, onLog, onProgress, opts, runId)
 // --cookies 由 cookies 通道；返回 taskDir 内新增产物（排除 .part/.ytdl）
        val produced = outDir.listFiles { f ->
            f.isFile && f.name !in before && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl")
        }.orEmpty()
        if (produced.isEmpty()) error("退出码 0 但无产物生成")
 // 写了平台封面的轮次（VIDEO/AUDIO）：缩略图改名成 <媒体名>-cover.<ext> 伴生
 // （货架封面匹配同源），并入产物列表交管线入库。
 // 先滤掉反爬占位图（B站老视频会发 1×1 黑 PNG，解码边界 <8px 的直接删）。
        if (mode == Mode.VIDEO || mode == Mode.AUDIO) {
            val kept = produced.toMutableList()
            kept.removeAll { f ->
                val ext = f.extension.lowercase()
                if (ext in IMAGE_EXTS && !isRealImageAtLeast8px(f)) {
                    f.delete()
                    true
                } else {
                    false
                }
            }
            return pairThumbnailCompanion(kept.map { it.toPath() })
        }
        return produced.map { it.toPath() }
 // cancelHandle：yt-dlp 取消走 fork per-run 注册表（requestCancel(runId) → DownloadCancelled，
 // 由 run() 翻译为 EngineCancelledException），此参数为接口统一保留；
 // 两新引擎（已接）与后继引擎靠它轮询取消
    }

 /** 每轮独立子目录由调用方传入；失败抛出（原始错误签名随异常文本，交上层匹配）。 */
    private fun run(context: Context, url: String, cookieFile: File?, outDir: File,
                    onLog: (String) -> Unit, onProgress: (Float, String) -> Unit,
                    extraOpts: List<Pair<String, String?>>, runId: String) {
        val req = YtDlpRequest(url).apply {
            addOption("--ffmpeg-location", FfmpegLocator.ffmpegPath(context))
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            cookieFile?.let { addOption("--cookies", it.absolutePath) }
            extraOpts.forEach { (k, v) ->
                if (v == null) addOption(k) else addOption(k, v)   // 旗标走单参（api-notes 实录的形式）
            }
        }
        try {
 // stdout 恒为空——一切输出走日志回调；异常封装在 Future 里，必须 get() 并解包
            val resp = YtDlp.executeDebug(runId, req, { _, msg ->
                onLog(msg)
                ProgressParser.percent(msg)?.let { onProgress(it / 100f, msg) }
            }, null).get()
            if (resp.exitCode != 0) error("yt-dlp 退出码 ${resp.exitCode}（详见上方日志）")
        } catch (e: ExecutionException) {
            throw translateYtdlpCancellation(e.cause ?: e)
        }
    }

    fun requestCancel(runId: String) = YtDlp.requestCancel(runId)

    @Volatile private var inited = false
    fun initOnce(context: Context) {
        appContext = context.applicationContext
        if (inited) return
        synchronized(this) {
            if (inited) return
            YtDlp.init(context.applicationContext)
            YtDlp.setNativeLibDir(context.applicationInfo.nativeLibraryDir)   // 兜底 monkeypatch 的传值口，平时无害
            inited = true
        }
    }
}

/**
 * mode→extraOpts 映射（ 画质档接线；top-level internal：JVM 单测直测）。
 * - VIDEO：max=默认格式链（ 语义不动）；1080P 限档=`-f "bv*[height<=1080]+ba/b"`。
 * - AUDIO：`-x --audio-format <fmt>`（画质档不消费——音频无画质）。
 * - COVER：`--write-thumbnail --skip-download`（画质档/音频格式均不消费）。
 */
/**
 * mode→download extraOpts（top-level internal：JVM 纯函数直测）。
 * 设计：**单视频路径恒带 --no-playlist**——混合型 watch&list URL 只下点名的那一个
 * （fork 白名单已登记该旗标）；子项/普通单页 URL 为 no-op。
 */
/** 缩略图扩展名（与货架伴生匹配 COVER_EXTS 对齐）。 */
internal val IMAGE_EXTS = setOf("webp", "jpg", "jpeg", "png")

/** 反爬占位图甄别：能解码且边界 ≥8×8 才算真图（B站老视频发 1×1 黑 PNG）。 */
internal fun isRealImageAtLeast8px(f: File): Boolean {
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
    return opts.outWidth >= 8 && opts.outHeight >= 8
}

internal fun pairThumbnailCompanion(produced: List<Path>): List<Path> {
    val thumbExts = setOf("webp", "jpg", "jpeg", "png")
    val media = produced.filter { it.toFile().extension.lowercase() !in thumbExts }
        .maxByOrNull { it.toFile().length() } ?: return produced
    val stem = media.toFile().nameWithoutExtension
    return produced.map { p ->
        val ext = p.toFile().extension.lowercase()
        if (p != media && ext in thumbExts) {
            val dst = p.toFile().resolveSibling("$stem-cover.$ext")
            if (p.toFile().renameTo(dst)) dst.toPath() else p
        } else {
            p
        }
    }
}

internal fun downloadOptsFor(mode: Mode, audioFormat: String, quality: String): List<Pair<String, String?>> {
    val noPlaylist = "--no-playlist" to null
    return when (mode) {
        Mode.VIDEO ->
            (if (quality == SettingsValues.QUALITY_1080P) listOf("-f" to YtdlpEngine.VIDEO_FORMAT_1080P) else emptyList()) +
                listOf("--write-thumbnail" to null, noPlaylist)
        Mode.AUDIO -> listOf("-x" to null, "--audio-format" to audioFormat, "--write-thumbnail" to null, noPlaylist)
        Mode.COVER -> listOf("--write-thumbnail" to null, "--skip-download" to null, noPlaylist)
    }
}

/**
 * 取消签名翻译（技术债②清）：fork yt-dlp 取消 = YtDlpException 且 message 含 DownloadCancelled
 * → EngineCancelledException（类型甄别载体，管道 Cancelled 判据）。
 * [linkdown] cancelled marker 走 python.stdout→logcat，不经 LogCallback，翻译层不影响 marker 链路。
 * 非取消异常原样返回（交 ErrorMapper，勿包泛化文案）。top-level internal：JVM 单测直测。
 */
internal fun translateYtdlpCancellation(t: Throwable): Throwable =
    if (t is YtDlpException && t.message?.contains("DownloadCancelled") == true) {
        EngineCancelledException(CANCELLED_MESSAGE)
    } else t

/**
 * 批量枚举结果：二段确认「title · N 条」+ 入队接口的子项 URL 源。
 * children=构造后的子项引用（≤调用方截断上限）；entriesTotal=flat dump entries 原始条数
 * （含被跳过的 null/无 id entry——「共 N 条」截断人话口径）。
 */
data class BatchInfo(
    val title: String,
    val children: List<ChildRef>,
    val entriesTotal: Int,
)
