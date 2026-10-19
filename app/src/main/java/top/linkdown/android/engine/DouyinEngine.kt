package top.linkdown.android.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.linkdown.android.engine.internal.FfmpegPost
import top.linkdown.android.engine.internal.safeFilename
import top.linkdown.android.pipeline.PlatformDirs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 抖音引擎（设计；参考实现 linkdown/resolvers/douyin.py 逐字平移）。
 * iesdouyin share 页 _ROUTER_DATA 自解析；ttwid 每次现取不缓存（参考实现 同语义，无过期管理）。
 * 构造注入缝：两 host 分参（单测换 MockWebServer），生产默认值在 [production]/伴生常量。
 * cookies 参数按 设计忽略（仅 YtdlpEngine 消费）。
 */
class DouyinEngine(
    private val ttwidBaseUrl: String,
    private val shareBaseUrl: String,
    ffmpegPath: String,
) : MediaEngine {

    private val post = FfmpegPost(ffmpegPath)
 // 参考实现 两客户端超时：probe timeout=20、download timeout=60；OkHttp 默认跟随重定向（≙follow_redirects=True）
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

 // ------------------------------------------------------------------
 // probe（参考实现 probe + _aweme_id + _get_ttwid + _share_item）
 // ------------------------------------------------------------------

    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        val finalUrl = probeClient.newCall(
            Request.Builder().url(url).header("User-Agent", UA_PC).build()
        ).execute().use { it.request.url.toString() } // 跟随重定向后的最终 URL（≙参考实现 str(r.url)）
        val vid = AWEME_ID_RE.find(finalUrl)?.groupValues?.get(1)
            ?: MODAL_ID_RE.find(finalUrl)?.groupValues?.get(1)
            ?: throw EngineException("抖音链接里找不到视频 ID")
        val ttwid = getTtwid()
        var item = shareItem(vid, ttwid?.let { "ttwid=$it" })
        if (item == null) item = shareItem(vid, null) // ttwid 失败（或带 cookie 没取到）→ 无 cookie 再试一次
        if (item == null) {
            throw EngineException("抖音分享页结构可能已改版，尝试更新工具")
        }
        val video = item["video"] as? JsonObject ?: JsonObject(emptyMap())
        val playAddrList = (video["play_addr"] as? JsonObject)?.get("url_list") as? JsonArray
 // 图集判定（设计新增人话，参考实现 douyin.py 无此分支）：无 play_addr.url_list 且含 images 键
        if ((playAddrList == null || playAddrList.isEmpty()) && item.containsKey("images")) {
            throw EngineException("抖音图集暂不支持")
        }
 // ≙参考实现 ((video.get("play_addr") or {}).get("url_list") or [""])[0].replace("playwm", "play")
        val playUrl = playAddrList.orEmpty().asSequence()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            .firstOrNull().orEmpty().replace("playwm", "play")
        if (playUrl.isEmpty()) {
            throw EngineException("未取到播放地址，视频可能已删除或仅登录可见")
        }
        return ProbeInfo(
            platform = PlatformDirs.DOUYIN, // 入库目录名（判重前缀/货架分组同源）
            mediaId = vid,
            sourceUrl = url, // probe 原样带回的原始链接
            title = (item["desc"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                ?.takeIf { it.isNotEmpty() } ?: vid, // ≙参考实现 item.get("desc") or vid
            author = (item["author"] as? JsonObject)?.get("nickname")?.let { p ->
                (p as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            } ?: "",
            coverUrl = (video["cover"] as? JsonObject)?.get("url_list")?.let { list ->
                (list as? JsonArray)?.firstOrNull()?.let { p ->
                    (p as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                }
            },
            durationSec = (video["duration"] as? JsonPrimitive)?.takeIf { it !is JsonNull }
                ?.content?.toDoubleOrNull()?.div(1000.0)?.takeIf { it != 0.0 }, // 参考实现 ms→s，0 视为缺失
            isVip = false,
            playUrl = playUrl,
            hasImages = item.containsKey("images"),
        )
    }

 /** 参考实现 _get_ttwid：POST 注册接口，status==200 门 + 响应全量 Set-Cookie 头扫描 ttwid=([^;]+)（设计
 * 有意补强：参考实现 只扫第一个头，Kotlin 逐头扫描——ttwid 在第 2+ 头时同样成功）。失败（含网络错误）null 降级。 */
    private fun getTtwid(): String? = try {
        val req = Request.Builder().url(ttwidBaseUrl)
            .post(TTWID_BODY.toRequestBody(JSON_MEDIA_TYPE))
            .build() // UA 不设——参考实现用 httpx 默认 UA，逐字平移（OkHttp 走默认 UA）
        probeClient.newCall(req).execute().use { resp ->
            if (resp.code != 200) null
            else resp.headers("Set-Cookie").asSequence()
                .mapNotNull { TTWID_COOKIE_RE.find(it)?.groupValues?.get(1) }
                .firstOrNull()
        }
    } catch (e: IOException) {
        null
    }

 /** 参考实现 _share_item：GET share/video/{vid}（UA=移动 + Cookie ttwid）→ _ROUTER_DATA 正则提取 →
 * 遍历 loaderData.values 找含 videoInfoRes 键项（禁取首个——首个可能是 video_layout=null）→ item_list[0]。 */
    private fun shareItem(vid: String, cookie: String?): JsonObject? {
        val req = Request.Builder().url("$shareBaseUrl/share/video/$vid")
            .header("User-Agent", UA_MOBILE)
            .apply { if (cookie != null) header("Cookie", cookie) }
            .build()
        return probeClient.newCall(req).execute().use { resp ->
            parseSharePage(resp.body?.string() ?: return null)
        }
    }

    private fun parseSharePage(html: String): JsonObject? {
        val m = ROUTER_DATA_RE.find(html) ?: return null
        val root = try {
            Json.parseToJsonElement(m.groupValues[1])
        } catch (e: SerializationException) {
            return null // ≙参考实现 except ValueError: return None
        }
        val loaderData = (root as? JsonObject)?.get("loaderData") as? JsonObject ?: return null
 // 遍历找含 videoInfoRes 键的 dict（参考实现 next(...)，不能 next(iter(...values()))）
        val page = loaderData.values.firstOrNull {
            it is JsonObject && it.containsKey("videoInfoRes")
        } as? JsonObject ?: return null
        val items = (page["videoInfoRes"] as? JsonObject)?.get("item_list") as? JsonArray
            ?: return null
        return items.firstOrNull() as? JsonObject // item_list 空 → None → 上层降级再试
    }

 // ------------------------------------------------------------------
 // download（参考实现 download + _finish；设计矩阵：video/audio/cover 三模式）
 // ------------------------------------------------------------------

    override suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  cancelHandle: CancelHandle, runId: String,
                                  quality: String): List<Path> {
        return when (mode) {
            Mode.COVER -> {
                val coverUrl = info.coverUrl?.takeIf { it.isNotEmpty() }
                    ?: throw EngineException(NO_PLAY_URL_MESSAGE)
                val raw = taskDir.resolve("cover.jpg")
                streamToFile(coverUrl, raw, onProgress, cancelHandle::isCancelled)
                onProgress(1.0f, DOWNLOAD_DONE_LABEL)
                listOf(finish(raw, info.title))
            }
            Mode.AUDIO -> {
                val raw = taskDir.resolve("video.mp4")
                streamToFile(requirePlayUrl(info), raw, onProgress, cancelHandle::isCancelled)
                onProgress(1.0f, DOWNLOAD_DONE_LABEL)
 // 参考实现 mode=="audio" → postprocess.extract_audio（out.{audio_format}），失败阻断
                val audio = post.extractAudio(raw, taskDir.resolve("out.$audioFormat"),
                    audioFormat, cancelHandle::isCancelled)
                listOf(finish(audio, info.title))
            }
            Mode.VIDEO -> {
                val raw = taskDir.resolve("video.mp4")
                streamToFile(requirePlayUrl(info), raw, onProgress, cancelHandle::isCancelled)
                onProgress(1.0f, DOWNLOAD_DONE_LABEL)
                listOf(finish(raw, info.title))
            }
        }
    }

    private fun requirePlayUrl(info: ProbeInfo): String =
        info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_URL_MESSAGE)

 /** 参考实现 下载循环：UA=PC + Referer www.douyin.com；raise_for_status；64KB 分块流式 +
 * Content-Length 已知时 MB 进度（f"{done/1e6:.1f}/{total/1e6:.1f} MB"）；逐块查取消。 */
    private fun streamToFile(url: String, dst: Path,
                             onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean) {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA_PC)
            .header("Referer", "https://www.douyin.com/")
            .build()
        downloadClient.newCall(req).execute().use { resp ->
            val body = resp.body ?: throw EngineException("下载失败（HTTP ${resp.code}）") // ≙raise_for_status
            if (!resp.isSuccessful) throw EngineException("下载失败（HTTP ${resp.code}）")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L // ≙参考实现 Content-Length or 0
            var done = 0L
            body.byteStream().use { input ->
                Files.newOutputStream(dst).use { out ->
                    val buf = ByteArray(CHUNK_BYTES) // 参考实现 aiter_bytes(1 << 16)
                    while (true) {
                        if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE) // ≙参考实现 CancelledError
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

 /** 参考实现 _finish：dest 内改名为 safe_filename(title + 后缀)（POSIX rename 覆盖语义 → REPLACE_EXISTING）。 */
    private fun finish(file: Path, title: String): Path {
        val name = file.fileName.toString()
        val dot = name.lastIndexOf('.')
        val suffix = if (dot > 0) name.substring(dot) else "" // ≙参考实现 Path.suffix
        val final = file.resolveSibling(safeFilename(title) + suffix) // 后缀不进清洗（防截断吃扩展名）
        if (file.fileName.toString() != final.fileName.toString()) {
            Files.move(file, final, StandardCopyOption.REPLACE_EXISTING)
        }
        return final
    }

    companion object {
        const val PROD_TTWID_URL = "https://ttwid.bytedance.com/ttwid/union/register/"
        const val PROD_SHARE_BASE_URL = "https://www.iesdouyin.com"

 /** 生产入口：默认双 host（ffmpeg 路径来自 FfmpegLocator）。 */
        fun production(ffmpegPath: String) = DouyinEngine(PROD_TTWID_URL, PROD_SHARE_BASE_URL, ffmpegPath)

 // 参考实现 _UA_PC / _UA_MOBILE / _TTWID_BODY 逐字
        private const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        private const val TTWID_BODY = """{"region":"cn","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
 // 参考实现 _aweme_id 双正则
        private val AWEME_ID_RE = Regex("""/(?:video|note|slides)/(\d{15,})""")
        private val MODAL_ID_RE = Regex("""modal_id=(\d{15,})""")
        private val TTWID_COOKIE_RE = Regex("""ttwid=([^;]+)""")
 // 参考实现 window._ROUTER_DATA 提取（DOTALL 非贪婪必须）
        private val ROUTER_DATA_RE =
            Regex("""window\._ROUTER_DATA\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        private const val CHUNK_BYTES = 1 shl 16
        private const val DOWNLOAD_DONE_LABEL = "下载完成"
        private const val NO_PLAY_URL_MESSAGE = "未取到播放地址，视频可能已删除或仅登录可见"
    }
}
