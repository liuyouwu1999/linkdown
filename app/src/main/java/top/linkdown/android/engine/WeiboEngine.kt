package top.linkdown.android.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import top.linkdown.android.engine.internal.FfmpegPost
import top.linkdown.android.engine.internal.safeFilename
import top.linkdown.android.pipeline.PlatformDirs
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 微博引擎（2026-10 实测定稿；参考实现 curl 全链验证）。
 * 三步匿名访客链（无 JS/无签名）：POST visitor/genvisitor2（表单，回包是
 * `visitor_gray_callback({json})`，含 sub/subp/tid）→ GET visitor?a=incarnate&t=<tid>…
 * （跟重定向，收获 SUB/SUBP 访客 Cookie）→ m.weibo.cn statuses/show JSON。
 * media_info 两代结构都认：stream_url_hd/stream_url（新，mp4 直链，hd 优先）与
 * playback_url（旧，分号分隔多 CDN 取首个）。直链下载：裸 GET 即 200，但 Range 不带
 * Referer 会 403 —— 引擎恒带 Referer weibo.com + UA。
 * bid 提取：m.weibo.cn/status/<bid> 原样；weibo.com/<uid>/<bid> 取尾段；
 * weibo.com/tv/show/<mid> 是十进制 mid，statuses/show 只认 bid —— 内置 base62 转换
 * （mid 从右每 7 位一组转 62 进制拼接，已用真实帖双向验证）。
 * 构造注入缝：visitor/api 基址分参（单测换 MockWebServer），生产默认值见 companion。
 * cookies 参数按约定忽略（匿名访客即可；微博登录可见帖 v1 不支持）。
 */
class WeiboEngine(
    private val visitorBaseUrl: String,
    private val apiBaseUrl: String,
    ffmpegPath: String,
) : MediaEngine {

    private val post = FfmpegPost(ffmpegPath)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

 // ------------------------------------------------------------------
 // probe
 // ------------------------------------------------------------------

    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        val bid = extractBid(url) ?: throw EngineException(NO_BID_MESSAGE)
        val data = showStatus(bid)
        val pageInfo = data["page_info"] as? JsonObject ?: JsonObject(emptyMap())
        val media = pageInfo["media_info"] as? JsonObject ?: JsonObject(emptyMap())
        val playUrl = mediaUrlOf(media) ?: throw EngineException(NO_PLAY_MESSAGE)
        val user = data["user"] as? JsonObject ?: JsonObject(emptyMap())
        val author = primStr(user["screen_name"]) ?: ""
        val rawTitle = primStr(data["status_title"])
            ?: textPlain(primStr(data["text"]))
            ?: ""
        val title = rawTitle.takeIf { it.isNotEmpty() }?.take(40) ?: "微博视频"
        return ProbeInfo(
            platform = PlatformDirs.WEIBO,
            mediaId = bid,
            sourceUrl = url,
            title = title,
            author = author,
            coverUrl = ((pageInfo["page_pic"] as? JsonObject)?.get("url") as? JsonPrimitive)
                ?.takeIf { p -> p !is JsonNull }?.content,
            durationSec = primStr(media["duration"])?.toDoubleOrNull(),
            isVip = false,
            playUrl = playUrl,
            hasImages = false,
        )
    }

 /** bid 提取：m.weibo.cn/status/<bid> 原样；weibo.com/tv/show/<mid> 走 base62 转换；其余取尾段。 */
    internal fun extractBid(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = (uri.host ?: "").lowercase()
        if (!host.endsWith("weibo.com") && !host.endsWith("weibo.cn")) return null
        val segs = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
        val candidate: String? = when {
            segs.firstOrNull()?.equals("status", true) == true -> segs.getOrNull(1)
            segs.firstOrNull() == "tv" && segs.getOrNull(1) == "show" ->
                segs.getOrNull(2)?.substringAfterLast(':')
            segs.firstOrNull() == "u" -> segs.getOrNull(2) // weibo.com/u/<uid>/<bid>
            else -> segs.lastOrNull()
        }
        val raw = candidate?.takeIf { it.matches(BID_RE) } ?: return null
 // m.weibo.cn/status/<x>：数字与短码 API 都认，原样；weibo.com 形态纯数字=mid 需转换
        if (host.endsWith("weibo.cn")) return raw
        return if (raw.all { it.isDigit() }) midToBid(raw) else raw
    }

 /** 微博 mid → bid：十进制从右每 7 位一组转 62 进制拼接（首组自然去前导零）。 */
    internal fun midToBid(mid: String): String {
        val out = StringBuilder()
        var i = mid.length
        while (i > 0) {
            val start = maxOf(0, i - 7)
            var n = mid.substring(start, i).toLong()
            var s = ""
            while (n > 0) {
                s = B62[(n % 62).toInt()] + s
                n /= 62
            }
            out.insert(0, s)
            i = start
        }
        return out.toString()
    }

 // ------------------------------------------------------------------
 // 访客链 + statuses/show
 // ------------------------------------------------------------------

 /** genvisitor2 拿 tid/sub/subp（回包是 JSONP 包裹）。 */
    private fun genVisitor(): JsonObject {
        val form = FormBody.Builder()
            .add("cb", "visitor_gray_callback")
            .add("ver", "20250916")
            .add("request_id", "linkdown")
            .add("tid", "")
            .add("from", "weibo_m weibo_m")
            .add("webdriver", "false")
            .add("rid", "linkdown")
            .add("return_url", "$apiBaseUrl/")
            .build()
        client.newCall(
            Request.Builder().url("$visitorBaseUrl/visitor/genvisitor2")
                .header("User-Agent", UA).post(form).build()
        ).execute().use { resp ->
            val body = resp.body?.string() ?: throw EngineException(VISITOR_FAIL_MESSAGE)
            val m = CALLBACK_RE.find(body) ?: throw EngineException(VISITOR_FAIL_MESSAGE)
            return try {
                Json.parseToJsonElement(m.groupValues[1]).jsonObject
            } catch (e: Exception) {
                throw EngineException(VISITOR_FAIL_MESSAGE)
            }
        }
    }

 /** statuses/show：首试直接用 sub/subp；失败走 incarnate 补发 Cookie 后重试一次。 */
    private fun showStatus(bid: String): JsonObject {
        val gen = genVisitor()
 // genvisitor2 回包结构：{retcode, data:{sub, subp, tid}}
        val genData = gen["data"] as? JsonObject ?: JsonObject(emptyMap())
        var cookie = "SUB=${primStr(genData["sub"])}; SUBP=${primStr(genData["subp"])}"
        var root: JsonObject? = fetchShow(bid, cookie)
        if (root == null || !isOk(root)) {
            cookie = incarnate(primStr(genData["tid"]) ?: "", cookie)
            root = fetchShow(bid, cookie)
        }
        val data = (root?.get("data") as? JsonObject)
        if (root == null || !isOk(root) || data == null) throw EngineException(SHOW_FAIL_MESSAGE)
        return data
    }

 /** JSON 字符串原语取值（JsonNull/非原语 → null）。 */
    private fun primStr(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content

 /** incarnate：跟重定向种访客 Cookie（响应头 Set-Cookie 的 SUB/SUBP/SVB 优先于 genvisitor2 裸值）。 */
    private fun incarnate(tid: String, fallback: String): String {
        val harvested = LinkedHashMap<String, String>()
        if (fallback.isNotBlank()) {
            fallback.split("; ").forEach { pair ->
                val i = pair.indexOf('=')
                if (i > 0) harvested[pair.take(i)] = pair.substring(i + 1)
            }
        }
 // 跳转链上的 Set-Cookie 只能靠 CookieJar 收（OkHttp 自动跟 302，中间响应头拿不到）
        val jarClient = client.newBuilder()
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookies.forEach {
                        if (it.name == "SUB" || it.name == "SUBP" || it.name == "SVB") {
                            harvested[it.name] = it.value
                        }
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
            })
            .build()
        val encoded = java.net.URLEncoder.encode(tid, "UTF-8")
        jarClient.newCall(
            Request.Builder().url(incarnateUrl(visitorBaseUrl, encoded))
                .header("User-Agent", UA).build()
        ).execute().use { /* 仅为走链收 Cookie */ }
        return harvested.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun fetchShow(bid: String, cookie: String): JsonObject? = runCatching {
        client.newCall(
            Request.Builder().url("$apiBaseUrl/statuses/show?id=$bid")
                .header("User-Agent", UA)
                .header("Referer", "$apiBaseUrl/")
                .header("Cookie", cookie)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            Json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
        }
    }.getOrNull()

    private fun isOk(root: JsonObject?): Boolean =
        (root?.get("ok") as? JsonPrimitive)?.let { p -> p !is JsonNull && p.content == "1" } == true

 /** media_info 两代结构：stream_url_hd → stream_url → playback_url 首个非空段。 */
    private fun mediaUrlOf(media: JsonObject): String? {
        val hd = primStr(media["stream_url_hd"])?.takeIf { it.isNotEmpty() }
        val sd = primStr(media["stream_url"])?.takeIf { it.isNotEmpty() }
        val legacy = primStr(media["playback_url"])
            ?.split(";")?.firstOrNull { it.isNotBlank() }
        return hd ?: sd ?: legacy
    }

 /** 正文去标签（text 是 HTML：<a>/br 等），压掉多余空白。 */
    private fun textPlain(html: String?): String? {
        val s = html ?: return null
        return s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
    }

 // ------------------------------------------------------------------
 // download（视频=直链 mp4+封面伴生；音频=ffmpeg 抽取；仅封面=海报图）
 // ------------------------------------------------------------------

    override suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  cancelHandle: CancelHandle, runId: String,
                                  quality: String): List<Path> {
        return when (mode) {
            Mode.COVER -> listOf(coverOnly(info, taskDir, onProgress, cancelHandle::isCancelled))
            Mode.VIDEO -> videoFlow(info, taskDir, onProgress, cancelHandle::isCancelled)
            Mode.AUDIO -> audioFlow(info, taskDir, audioFormat, onProgress, cancelHandle::isCancelled)
        }
    }

 /** 视频流：mp4 直链（hd 缺失回退 sd 已在 probe 选定）→ 成品 mp4 + 伴生 -cover.jpg（货架封面匹配同源）。 */
    private fun videoFlow(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit,
                          isCancelled: () -> Boolean): List<Path> {
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_MESSAGE)
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val final = taskDir.resolve(safeFilename(base) + ".mp4")
        Files.move(raw, final, StandardCopyOption.REPLACE_EXISTING)
        val products = mutableListOf(final)
        info.coverUrl?.takeIf { it.isNotEmpty() }?.let { cu ->
            val cover = taskDir.resolve("cover.jpg")
            if (fetchCover(cu, cover, isCancelled)) {
                val coverFinal = taskDir.resolve(safeFilename("$base-cover") + ".jpg")
                Files.move(cover, coverFinal, StandardCopyOption.REPLACE_EXISTING)
                products.add(coverFinal)
            }
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 音频流：mp4 直链落盘 → ffmpeg 抽取/转码 → 标签 → 成品；伴生封面同视频。 */
    private suspend fun audioFlow(info: ProbeInfo, taskDir: Path, audioFormat: String,
                          onProgress: (Float, String) -> Unit,
                          isCancelled: () -> Boolean): List<Path> {
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_MESSAGE)
        val fmt = if (audioFormat == "mp3" || audioFormat == "m4a") audioFormat else "mp3"
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
        val audio = post.extractAudio(raw, taskDir.resolve("out.$fmt"), fmt, isCancelled)
        var cover: Path? = null
        info.coverUrl?.takeIf { it.isNotEmpty() }?.let { cu ->
            val f = taskDir.resolve("cover.jpg")
            if (fetchCover(cu, f, isCancelled)) cover = f
        }
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val tagged = post.tagAudio(audio, cover, info.title, info.author, "", isCancelled)
        val final = taskDir.resolve(safeFilename(base) + ".$fmt")
        Files.move(tagged, final, StandardCopyOption.REPLACE_EXISTING)
        val products = mutableListOf(final)
        if (cover != null && Files.exists(cover)) {
            val coverFinal = taskDir.resolve(safeFilename("$base-cover") + ".jpg")
            Files.move(cover, coverFinal, StandardCopyOption.REPLACE_EXISTING)
            products.add(coverFinal)
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 仅封面：海报图（page_pic）。 */
    private fun coverOnly(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean): Path {
        val url = info.coverUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_COVER_MESSAGE)
        val cover = taskDir.resolve("cover.jpg")
        streamToFile(url, cover, onProgress, isCancelled)
        val final = taskDir.resolve(safeFilename(info.title) + ".jpg")
        Files.move(cover, final, StandardCopyOption.REPLACE_EXISTING)
        return final
    }

 /** 下载循环：带 Referer（Range 无 Referer 会 403）+ 64KB 分块 + Content-Length 进度 + 逐块查取消。 */
    private fun streamToFile(url: String, dst: Path,
                             onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean) {
        downloadClient.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", DOWNLOAD_REFERER)
            .build()).execute().use { resp ->
            val body = resp.body ?: throw EngineException("下载失败（HTTP ${resp.code}）")
            if (!resp.isSuccessful) throw EngineException("下载失败（HTTP ${resp.code}）")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L
            var done = 0L
            body.byteStream().use { input ->
                Files.newOutputStream(dst).use { out ->
                    val buf = ByteArray(CHUNK_BYTES)
                    while (true) {
                        if (isCancelled()) throw EngineCancelledException(CANCELLED_MESSAGE)
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

 /** 封面下载：200 且未取消才落盘，否则静默视为无封面（不阻断）。 */
    private fun fetchCover(url: String, dst: Path, isCancelled: () -> Boolean): Boolean =
        downloadClient.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
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
        const val PROD_VISITOR_BASE_URL = "https://visitor.passport.weibo.cn"
        const val PROD_API_BASE_URL = "https://m.weibo.cn"

 /** 生产入口：默认访客/API 基址（ffmpeg 路径来自 FfmpegLocator）。 */
        fun production(ffmpegPath: String) = WeiboEngine(PROD_VISITOR_BASE_URL, PROD_API_BASE_URL, ffmpegPath)

        private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        private const val DOWNLOAD_REFERER = "https://weibo.com/"
        private const val CHUNK_BYTES = 1 shl 16
        private val CALLBACK_RE = Regex("""\(\s*(\{.*\})\s*\)""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val BID_RE = Regex("[0-9A-Za-z]+")
        private val B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private const val NO_BID_MESSAGE = "微博链接里找不到帖子 id，请确认是微博视频分享链接"
        private const val NO_PLAY_MESSAGE = "微博未返回可用的视频地址，该帖可能不是视频帖"
        private const val NO_COVER_MESSAGE = "微博未返回可用的封面地址"
        private const val SHOW_FAIL_MESSAGE = "微博接口访问失败（访客身份被拒），请稍后重试"
        private const val VISITOR_FAIL_MESSAGE = "微博访客身份获取失败，请稍后重试"
        private const val DONE_LABEL = "完成"

 /** incarnate 直链（tid 由调用方 urlencode）。internal 供单测断言 URL 形状。 */
        internal fun incarnateUrl(base: String, tidEncoded: String): String =
            "$base/visitor/visitor?a=incarnate&t=$tidEncoded&w=2&c=096&gc=&cb=cross_domain&from=weibo_m%20weibo_m"
    }
}