package top.linkdown.android.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import top.linkdown.android.engine.internal.FfmpegPost
import top.linkdown.android.engine.internal.qualityRank
import top.linkdown.android.engine.internal.safeFilename
import top.linkdown.android.engine.internal.truthy
import top.linkdown.android.pipeline.PlatformDirs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 汽水引擎（设计；参考实现 linkdown/resolvers/qishui.py 逐字平移）。
 * luna seo_track 取址：video_model（JSON 字符串最多 3 层解包，递归收集含 main/backup 键节点）→
 * url_player_info → audio_info.play_info_list 三级；质量排序照 _quality_rank。
 * 有意偏离（设计上位拍板）：试听曲 probe 直接阻断（参考实现 原语义=下载试听+标题注记）；
 * VIP（label_info 三标志）仅 download 时 onLog 提示不阻断；video 模式按音频交付（modeLabel 归管道）。
 * 构造注入缝：luna 基址分参（单测换 MockWebServer），生产默认值见 [PROD_LUNA_BASE_URL]。
 * cookies 参数按 设计忽略（仅 YtdlpEngine 消费）。
 */
class QishuiEngine(
    private val lunaBaseUrl: String,
    ffmpegPath: String,
) : MediaEngine {

    private val post = FfmpegPost(ffmpegPath)
 // 参考实现 单客户端 timeout=20（probe）/ 60（download）；OkHttp 默认跟随重定向（≙follow_redirects=True）
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

 // ------------------------------------------------------------------
 // probe（参考实现 probe + _extract_track_id + _fetch_play_url + _is_preview + _cover_url）
 // ------------------------------------------------------------------

    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        val page = probeGet(url) // 短链 → track 页（参考实现 str(page.url) + page.text）
        val tid = extractTrackId(page.first) ?: extractTrackId(page.second)
            ?: throw EngineException(NO_TRACK_ID_MESSAGE)
        val data = probeGetJson("$lunaBaseUrl/luna/h5/seo_track?track_id=$tid&device_platform=web")
 // 参考实现 ：实测响应无顶层 status_code（状态在 status_info），仅在显式非 0 时判失败
        val statusCode = data["status_code"] as? JsonPrimitive
        if (statusCode != null && statusCode !is JsonNull && statusCode.content.toDoubleOrNull() != 0.0) {
            throw EngineException("汽水接口返回错误 status_code=${statusCode.content}，接口可能改版")
        }
        val track = (data["seo_track"] as? JsonObject)?.get("track") as? JsonObject
            ?: JsonObject(emptyMap())
        val play = fetchPlayUrl(data, track)
            ?: throw EngineException(NO_PLAY_MESSAGE)
 // 有意偏离（设计上位拍板）：试听判定后直接阻断，不留字段（参考实现 原语义=is_preview 注记）
        if (isPreview(play, track)) {
            throw EngineException(previewMessage(play))
        }
 // 参考实现 label_info 三标志（VIP 仅标记，提示在 download 经 onLog，不阻断不抛）
        val label = track["label_info"] as? JsonObject ?: JsonObject(emptyMap())
        val isVip = truthy(label["only_vip_download"]) || truthy(label["only_vip_playable"]) ||
            (label["quality_map"] as? JsonObject)?.values?.any {
                truthy((it as? JsonObject)?.get("need_vip"))
            } == true
        val artists = (track["artists"] as? JsonArray).orEmpty()
        return ProbeInfo(
            platform = PlatformDirs.QISHUI, // 入库目录名（判重前缀/货架分组同源）
            mediaId = primStr(track["id"]) ?: "",
            sourceUrl = url, // probe 原样带回的原始链接
            title = primStr(track["name"])?.takeIf { it.isNotEmpty() } ?: "未知歌曲", // 参考实现 name or 未知歌曲
 // 参考实现 "、".join(a.get("name", "") for a in artists)：缺名项也占一个空串位
            author = artists.joinToString("、") { a ->
                primStr((a as? JsonObject)?.get("name")) ?: ""
            },
            coverUrl = coverUrl(track["album"] as? JsonObject ?: JsonObject(emptyMap())),
            durationSec = (longOf(track["duration"]) ?: 0L).div(1000.0).takeIf { it != 0.0 }, // 参考实现 ms→s，0 视为缺失
            isVip = isVip,
            playUrl = playUrlOf(play),
            hasImages = false,
        )
    }

 /** 参考实现 _extract_track_id 三正则：URL 参数式 → JSON 字符串式 → track|song 路径式。 */
    private fun extractTrackId(text: String): String? =
        TRACK_ID_PARAM_RE.find(text)?.groupValues?.get(1)
            ?: TRACK_ID_JSON_RE.find(text)?.groupValues?.get(1)
            ?: TRACK_PATH_RE.find(text)?.groupValues?.get(1)

 /** JSON 字符串原语取值（JsonNull/非原语 → null）。 */
    private fun primStr(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content

 /** 数字原语取整（兼容数字与数字字符串；浮点截断，≙Python int()/float() 常见路径）。 */
    private fun longOf(e: JsonElement?): Long? =
        (e as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.let { c ->
            c.toLongOrNull() ?: c.toDoubleOrNull()?.toLong()
        }

 /** 参考实现 probe 全程 _headers()（UA=PC + Referer music.douyin.com）挂在 client 上。 */
    private fun probeGet(url: String): Pair<String, String> =
        probeClient.newCall(request(url)).execute().use { resp ->
            Pair(resp.request.url.toString(), resp.body?.string() ?: "")
        }

    private fun probeGetJson(url: String): JsonObject =
        probeClient.newCall(request(url)).execute().use { resp ->
            Json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
        }

    private fun request(url: String): Request = Request.Builder().url(url)
        .header("User-Agent", UA)
        .header("Referer", REFERER)
        .build()

 /** 参考实现 _fetch_play_url 三级：video_model → url_player_info（不足或试听时）→ audio_info 兜底。 */
    private fun fetchPlayUrl(data: JsonObject, track: JsonObject): JsonObject? {
        val trackPlayer = data["track_player"] as? JsonObject ?: JsonObject(emptyMap())
        val candidates = mutableListOf<JsonObject>()
        collectPlayUrls(trackPlayer["video_model"], candidates)
        var best = pickBest(candidates)
        val infoUrl = primStr(trackPlayer["url_player_info"])
        if ((best == null || isPreview(best, track)) && !infoUrl.isNullOrEmpty()) {
            val root = probeGetJson(infoUrl) // ≙参考实现 client.get(info_url)，同 client 头
            val list = (((root["Result"] as? JsonObject)?.get("Data") as? JsonObject)
                ?.get("PlayInfoList") as? JsonArray).orEmpty()
            best = pickBest(list.filterIsInstance<JsonObject>()) ?: best
        }
        if (best == null) {
            val list = ((track["audio_info"] as? JsonObject)?.get("play_info_list") as? JsonArray).orEmpty()
            best = pickBest(list.filterIsInstance<JsonObject>())
        }
        return best
    }

 /** 参考实现 _json_unpack：video_model 是 JSON 字符串（可能多层嵌套），按 music-lib 同款最多 3 层解包。 */
    private fun jsonUnpack(node: JsonElement): JsonElement {
        var n: JsonElement = node
        repeat(JSON_UNPACK_DEPTH) {
            val p = n as? JsonPrimitive ?: return n // ≙参考实现 not isinstance(str) → break
            if (!p.isString) return n
            n = try {
                Json.parseToJsonElement(p.content)
            } catch (e: SerializationException) {
                return p // ≙参考实现 except ValueError → break
            }
        }
        return n
    }

 /** 参考实现 _collect_play_urls：递归收集「含任一 main/backup 键且值非空」的节点。 */
    private fun collectPlayUrls(node: JsonElement?, out: MutableList<JsonObject>) {
        var n = node
        if (n is JsonPrimitive) n = jsonUnpack(n) // ≙参考实现 isinstance(str) 先解包
        when (n) {
            is JsonObject -> {
                if ((MAIN_KEYS + BACKUP_KEYS).any { truthy(n[it]) }) out += n
                for (v in n.values) collectPlayUrls(v, out)
            }
            is JsonArray -> for (v in n) collectPlayUrls(v, out)
            else -> {}
        }
    }

 /** 参考实现 _play_url：四键名候选按序取首个真值（main 四键先于 backup 四键）。 */
    private fun playUrlOf(play: JsonObject): String? =
        (MAIN_KEYS + BACKUP_KEYS).firstOrNull { truthy(play[it]) }?.let { primStr(play[it]) }

 /** 参考实现 _pick_best：只在有播放地址的节点里取质量分最高（平分取靠前）。 */
    private fun pickBest(infos: List<JsonObject>): JsonObject? =
        infos.filter { playUrlOf(it) != null }.maxByOrNull { qualityRank(it) }

 /** 参考实现 _is_preview：流时长 +5 严格小于完整时长（秒）；节点键 duration 或大写 Duration。 */
    private fun isPreview(play: JsonObject, track: JsonObject): Boolean {
        val full = (longOf(track["duration"]) ?: 0L) / 1000.0
        val dur = playDurationSec(play)
        return dur > 0 && full > 0 && dur + 5 < full
    }

 /** ≙参考实现 float(play.get("duration") or play.get("Duration") or 0)（url_player_info 实测键名 Duration）。 */
    private fun playDurationSec(play: JsonObject): Double =
        listOf(play["duration"], play["Duration"]).firstOrNull { truthy(it) }
            ?.let { primStr(it)?.toDoubleOrNull() } ?: 0.0

 /** 试听人话：N=流时长秒取整（设计文案逐字）。 */
    private fun previewMessage(play: JsonObject): String =
        "汽水只给到试听片段（约 ${playDurationSec(play).roundToInt()} 秒），完整版需要 VIP"

 /** 参考实现 _cover_url：url_cover.urls 是图片基地址列表，base + uri + "~" + template_prefix +
 * "-resize:960:960.png"；urls[0] 缺省基 p3-luna.douyinpic.com/img/。无 uri → null。 */
    private fun coverUrl(album: JsonObject): String? {
        val img = album["url_cover"] as? JsonObject ?: return null
        val uri = primStr(img["uri"])?.takeIf { it.isNotEmpty() } ?: return null
        val urls = img["urls"] as? JsonArray
        val base = if (urls != null && !urls.isEmpty()) primStr(urls.first()) ?: IMG_BASE else IMG_BASE
 // 参考实现 f-string 对缺失 template_prefix 会渲染 "None"，此处按空串处理（fixture 恒有值）
        val tpl = primStr(img["template_prefix"]) ?: ""
        return "$base$uri~${tpl}-resize:960:960.png"
    }

 // ------------------------------------------------------------------
 // download（参考实现 download：流式 raw.m4a → 抽取/转码 → 封面下载 → 标签嵌入；设计矩阵）
 // ------------------------------------------------------------------

    override suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  cancelHandle: CancelHandle, runId: String,
                                  quality: String): List<Path> {
        if (info.isVip) onLog(VIP_LOG_MESSAGE) // VIP 仅提示，不阻断不抛（设计；probe 无 onLog 故在此提示）
        return when (mode) {
            Mode.COVER -> listOf(coverOnly(info, taskDir, onProgress, cancelHandle::isCancelled)) // 设计：仅封面
            Mode.VIDEO, Mode.AUDIO ->
                audioFlow(info, mode == Mode.VIDEO, taskDir, audioFormat, onLog, onProgress,
                    cancelHandle::isCancelled)
        }
    }

 /** 参考实现 download 主体：流式 raw.m4a → 抽取/转码 → 封面下载 → 标签（失败不阻断）→ 产物改名。
 * 产物 = `作者 - 标题.{fmt}` + 伴生 `作者 - 标题-cover.jpg`（按设计）。 */
    private suspend fun audioFlow(info: ProbeInfo, asAudioNotice: Boolean, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  isCancelled: () -> Boolean): List<Path> {
        if (asAudioNotice) onLog(VIDEO_AS_AUDIO_LOG) // 设计：汽水无视频流，按音频交付（modeLabel 归管道）
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_MESSAGE)
        val fmt = if (audioFormat == "mp3" || audioFormat == "m4a") audioFormat else "mp3" // 参考实现非 mp3/m4a 回退 mp3
        val raw = taskDir.resolve("raw.m4a")
        streamToFile(url, raw, onProgress, isCancelled)
        val audio = post.extractAudio(raw, taskDir.resolve("out.$fmt"), fmt, isCancelled)
        var cover: Path? = null
        if (!info.coverUrl.isNullOrEmpty()) {
            val file = taskDir.resolve("cover.jpg")
            if (fetchCover(info.coverUrl!!, file, isCancelled)) cover = file // 非 200/取消 → 参考实现 cover=None
        }
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val tagged = post.tagAudio(audio, cover, info.title, info.author, "", isCancelled)
        val final = taskDir.resolve(safeFilename(base) + ".$fmt") // 后缀不进清洗（防截断吃扩展名）
        Files.move(tagged, final, StandardCopyOption.REPLACE_EXISTING)
        val products = mutableListOf(final)
        if (cover != null && Files.exists(cover)) {
            val coverFinal = taskDir.resolve(safeFilename("$base-cover") + ".jpg")
            Files.move(cover, coverFinal, StandardCopyOption.REPLACE_EXISTING)
            products.add(coverFinal) // Path 实现 Iterable，+= 有歧义，须显式 add
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 设计矩阵：qishui 仅封面 → 仅 cover.jpg（参考实现 无此分支，取 DouyinEngine 同款命名/人话）。 */
    private fun coverOnly(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean): Path {
        val url = info.coverUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_COVER_MESSAGE)
        val cover = taskDir.resolve("cover.jpg")
        streamToFile(url, cover, onProgress, isCancelled) // 200 门 + 取消语义与 DouyinEngine.COVER 一致
        val final = taskDir.resolve(safeFilename(info.title) + ".jpg")
        Files.move(cover, final, StandardCopyOption.REPLACE_EXISTING)
        return final
    }

 /** ≙参考实现 下载循环：raise_for_status；64KB 分块流式 + Content-Length 已知时 MB 进度；逐块查取消。 */
    private fun streamToFile(url: String, dst: Path,
                             onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean) {
        downloadClient.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER) // 参考实现下载请求同样带 Referer music.douyin.com
            .build()).execute().use { resp ->
            val body = resp.body ?: throw EngineException("下载失败（HTTP ${resp.code}）") // ≙raise_for_status
            if (!resp.isSuccessful) throw EngineException("下载失败（HTTP ${resp.code}）")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L // ≙参考实现 Content-Length or 0
            var done = 0L
            body.byteStream().use { input ->
                Files.newOutputStream(dst).use { out ->
                    val buf = ByteArray(CHUNK_BYTES) // 参考实现 aiter_bytes(1 << 16)
                    while (true) {
                        if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE) // ≙CancelledError
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress((done.toDouble() / total).toFloat(),
                                String.format(Locale.US, "%.1f/%.1f MB", done / 1e6, total / 1e6))
                        }
                    }
                }
            }
        }
    }

 /** 参考实现 封面下载：普通 GET，status==200 且未取消才落盘，否则静默视为无封面（不阻断）。 */
    private fun fetchCover(url: String, dst: Path, isCancelled: () -> Boolean): Boolean =
        downloadClient.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .build()).execute().use { resp ->
            val body = resp.body
            if (resp.code == 200 && !isCancelled() && body != null) {
                Files.write(dst, body.bytes())
                true
            } else {
                false
            }
        }

    companion object {
        const val PROD_LUNA_BASE_URL = "https://beta-luna.douyin.com"

 /** 生产入口：默认 luna 基址（ffmpeg 路径来自 FfmpegLocator）。 */
        fun production(ffmpegPath: String) = QishuiEngine(PROD_LUNA_BASE_URL, ffmpegPath)

 /** VIP 提示文案（参考实现 仅 is_vip 标记无固定文案，本条为 Kotlin 侧新增提示语）。 */
        internal const val VIP_LOG_MESSAGE = "该歌曲为 VIP 内容（完整版可能需要会员）"

 // 参考实现 _UA / _headers Referer / _IMG_BASE / _MAIN_KEYS / _BACKUP_KEYS 逐字
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val REFERER = "https://music.douyin.com/"
        private const val IMG_BASE = "https://p3-luna.douyinpic.com/img/"
        private val MAIN_KEYS = listOf("main_play_url", "main_url", "MainPlayUrl", "MainUrl")
        private val BACKUP_KEYS = listOf("backup_play_url", "backup_url", "BackupPlayUrl", "BackupUrl")

 // 参考实现 _extract_track_id 三正则
        private val TRACK_ID_PARAM_RE = Regex("""(?:^|[?&])track_id=(\d{10,})""")
        private val TRACK_ID_JSON_RE = Regex("\"track_id\"\\s*:\\s*\"(\\d{10,})\"")
        private val TRACK_PATH_RE = Regex("""(?:track|song)/(\d{10,})""")
        private const val JSON_UNPACK_DEPTH = 3
        private const val CHUNK_BYTES = 1 shl 16

        private const val NO_TRACK_ID_MESSAGE = "汽水链接里找不到 track_id，请确认是歌曲分享链接"
        private const val NO_PLAY_MESSAGE = "汽水未返回可用的播放地址，歌曲可能仅 VIP 可听"
        private const val NO_COVER_MESSAGE = "汽水未返回可用的封面地址"
 // internal：管道折叠补发同款文案（折叠后引擎收 AUDIO 不再自报，SingleDownloadPipeline 复用）
        internal const val VIDEO_AS_AUDIO_LOG = "汽水无视频流，已按音频交付"
        private const val DONE_LABEL = "完成"
    }
}
