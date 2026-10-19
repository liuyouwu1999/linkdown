package top.linkdown.android.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
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
 * 小红书引擎（解析键照 XHS-Downloader 开源实现逐字对齐，2026-10）。
 * 分享链流程：xhslink.com/<码> 302 到 xiaohongshu.com/explore/<noteId>?xsec_token=<token>
 * （分享链接自带 token，token 与 note_id 必须配对使用）→ 页面 HTML 内嵌
 * `window.__INITIAL_STATE__` JSON（含 undefined/new Map 需清洗）→
 * noteDetailMap[noteId].note →
 * - 视频笔记：video.consumer.originVideoKey 直链（sns-video-bd.xhscdn.com/<key>）优先，
 * 否则 video.media.stream.<清晰度>[] 按 height 取最高（backupUrls[0] 优先于 masterUrl）。
 * - 图文笔记：imageList[].url_default（无水印）或 url，多图按「;」并入 playUrl，下载时拆分。
 * 直链 CDN（xhscdn.com）匿名可下；引擎下载恒带 Referer xiaohongshu.com。
 * xhslink 解析走真实网络（OkHttp 跟随重定向取最终 URL）；页面基址为构造注入缝（单测换 MockWebServer）。
 * cookies 参数按约定忽略。
 */
class XhsEngine(
    private val xhsBaseUrl: String,
    ffmpegPath: String,
) : MediaEngine {

    private val post = FfmpegPost(ffmpegPath)

 /** 进程级访客 Cookie 池：小红书首访下发 a1/webId 等，后续请求必须携带（否则 461/解析失败）。 */
    private val xhsCookies = LinkedHashMap<String, String>()
    private val xhsCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (url.host.endsWith("xiaohongshu.com")) {
                cookies.forEach { xhsCookies[it.name] = it.value }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (url.host.endsWith("xiaohongshu.com")) {
                xhsCookies.entries.map { Cookie.Builder().name(it.key).value(it.value).domain(url.host).build() }
            } else {
                emptyList()
            }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).cookieJar(xhsCookieJar).build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .cookieJar(xhsCookieJar).build()

 // ------------------------------------------------------------------
 // probe
 // ------------------------------------------------------------------

    override suspend fun probe(url: String, cookies: File?, runId: String): ProbeInfo {
        probeMemo[url]?.let { return it }
        return doProbe(url, cookies, runId)
    }

    private suspend fun doProbe(url: String, cookies: File?, runId: String): ProbeInfo {
        val resolved = resolve(url)
        val noteId = noteIdOf(resolved) ?: throw EngineException(NO_ID_MESSAGE)
        val token = tokenOf(resolved)
        val html = fetchPage(noteId, token)
        println("XHS html len=" + html.length + " hasState=" + html.contains("__INITIAL_STATE__") + " head=" + html.take(80))
        val state = extractState(html) ?: throw EngineException(STATE_FAIL_MESSAGE)
        val note = noteOf(state, noteId) ?: throw EngineException(STATE_FAIL_MESSAGE)
        val type = primStr(note["type"]) ?: "normal"
        val user = note["user"] as? JsonObject
        val author = primStr(user?.get("nickname")) ?: ""
        val title = ((primStr(note["title"]))?.takeIf { it.isNotBlank() }
            ?: textPlain(primStr(note["desc"])) ?: "小红书笔记").take(40)
        val cover = firstImageUrl(note)
        return when (type) {
            "video" -> {
                val play = videoUrlOf(note) ?: throw EngineException(NO_PLAY_MESSAGE)
                memoPut(url, ProbeInfo(
                    platform = PlatformDirs.XHS,
                    mediaId = noteId,
                    sourceUrl = url,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    durationSec = null,
                    isVip = false,
                    playUrl = play,
                    hasImages = false,
                ))
            }
            else -> {
                val images = imageUrls(note)
                if (images.isEmpty()) throw EngineException(NO_PLAY_MESSAGE)
                memoPut(url, ProbeInfo(
                    platform = PlatformDirs.XHS,
                    mediaId = noteId,
                    sourceUrl = url,
                    title = title,
                    author = author,
                    coverUrl = cover,
                    durationSec = null,
                    isVip = false,
                    playUrl = images.joinToString(";"),
                    hasImages = true,
                ))
            }
        }
    }

    private fun memoPut(url: String, info: ProbeInfo): ProbeInfo {
        probeMemo[url] = info
        return info
    }

 // ------------------------------------------------------------------
 // 链接/页面解析
 // ------------------------------------------------------------------

 /** xhslink 短链跟随重定向取最终 URL；其余原样。 */
    private fun resolve(url: String): String {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return url
        if (!host.endsWith("xhslink.com")) return url
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
            return resp.request.url.toString()
        }
    }

 /** 页面路径里的 noteId：/explore/<id> 或 /discovery/item/<id>。 */
    private fun noteIdOf(url: String): String? {
        val segs = runCatching { URI(url).path?.split("/")?.filter { it.isNotBlank() } }.getOrNull() ?: return null
        return when {
            segs.firstOrNull() == "explore" -> segs.getOrNull(1)
            segs.firstOrNull() == "discovery" && segs.getOrNull(1) == "item" -> segs.getOrNull(2)
            else -> null
        }?.takeIf { it.matches(ID_RE) }
    }

    private fun tokenOf(url: String): String? =
        runCatching { URI(url).let { u -> u.rawQuery?.let { q -> q.split("&").firstOrNull { it.startsWith("xsec_token=") }?.substringAfter("=") } } }.getOrNull()

    private fun fetchPage(noteId: String, token: String?): String {
        val query = if (token.isNullOrEmpty()) "" else "?xsec_token=$token"
        client.newCall(
            Request.Builder().url("$xhsBaseUrl/explore/$noteId$query")
                .header("User-Agent", UA).build()
        ).execute().use { resp ->
            val dbg = resp.body?.string() ?: ""
            println("DBG fetchPage code=${resp.code} len=${dbg.length} hasState=${dbg.contains("window.__INITIAL_STATE__")} hasDetailMap=${dbg.contains("noteDetailMap")} head=${dbg.take(120)}")
            if (!resp.isSuccessful) throw EngineException(PAGE_FAIL_MESSAGE)
            return dbg
        }
    }

 /** __INITIAL_STATE__ 提取：取含该标记的 script 内文，剥前缀、清洗 undefined/new Map 后 JSON 解析。 */
    private fun extractState(html: String): JsonObject? {
        val marker = "window.__INITIAL_STATE__="
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        val scriptEnd = html.indexOf("</script>", start).takeIf { it >= 0 } ?: html.length
        val raw = html.substring(start + marker.length, scriptEnd)
        val cleaned = raw.replace("new Map([])", "[]")
            .replace(Regex("\\bundefined\\b"), "null")
        return runCatching { Json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
    }

 /**
 * 笔记对象提取，两代结构都认（2026-10 真机/参考实现 实测）：
 * ① 移动端 SSR：noteData.data.noteData（无 noteDetailMap，imageList[].url 预签名直链）；
 * ② 桌面端：note.noteDetailMap[noteId].note（imageList[].urlDefault 无水印）。
 */
    private fun noteOf(state: JsonObject, noteId: String): JsonObject? {
 // ① 移动端 SSR：noteData.data.noteData（imageList[].url 预签名直链）
        val noteDataNs = state["noteData"] as? JsonObject
        val mobileData = noteDataNs?.get("data") as? JsonObject
        (mobileData?.get("noteData") as? JsonObject)?.let { return it }
 // ② 桌面端：note.noteDetailMap[noteId].note（imageList[].urlDefault 无水印）
        val noteNs = state["note"] as? JsonObject
        val map = (noteNs?.get("noteDetailMap") ?: state["noteDetailMap"]) as? JsonObject
            ?: return null
        (map[noteId] as? JsonObject)?.get("note")?.let { return it as? JsonObject }
        val first = (map.values.firstOrNull() as? JsonObject)?.get("note")
        return first as? JsonObject
    }

 // ------------------------------------------------------------------
 // 媒体地址（XHS-Downloader video.py/image.py 键名对齐）
 // ------------------------------------------------------------------

 /** 视频直链：originVideoKey 拼直链优先；否则 stream 各清晰度按 height 取最高（backup 首选）。 */
    private fun videoUrlOf(note: JsonObject): String? {
        val video = note["video"] as? JsonObject ?: return null
        val origin = primStr(((video["consumer"] as? JsonObject) ?: JsonObject(emptyMap()))["originVideoKey"])
            ?.takeIf { it.isNotEmpty() }
        if (origin != null) return "$ORIGIN_BASE/$origin"
        val stream = ((video["media"] as? JsonObject) ?: JsonObject(emptyMap()))["stream"] as? JsonObject
            ?: return null
        val items = mutableListOf<JsonObject>()
        for (v in stream.values) {
            (v as? JsonArray)?.forEach { (it as? JsonObject)?.let { item -> items += item } }
        }
        val best = items.filter { primStr(it["masterUrl"]) != null || backupOf(it) != null }
            .maxByOrNull { primStr(it["height"])?.toIntOrNull() ?: 0 } ?: return null
        return httpsUpgraded(backupOf(best) ?: primStr(best["masterUrl"]))
    }

    private fun backupOf(item: JsonObject): String? =
        (item["backupUrls"] as? JsonArray)
            ?.mapNotNull { httpsUpgraded(primStr(it)) }?.firstOrNull { it.isNotBlank() }

 /** 图文：imageList[].url_default 优先，缺省 url。 */
    private fun imageUrls(note: JsonObject): List<String> {
        val list = note["imageList"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            httpsUpgraded(
                primStr(obj["urlDefault"])?.takeIf { it.isNotEmpty() }
                    ?: primStr(obj["url"])?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun firstImageUrl(note: JsonObject): String? = imageUrls(note).firstOrNull()

 /**
 * 小红书图/视频 CDN（*.xhscdn.com）会在 __INITIAL_STATE__ 里下发 http 明文直链
 * （真机实测 sns-webpic-qc 被明文直链打中 → CLEARTEXT not permitted 下载失败）。
 * Android 9+ 默认禁明文而 参考网页版不拦，故只在此处统一升 https（CDN 本就支持）；
 * 仅限 xhscdn 域——MockWebServer 测试地址是 http://localhost，不能误伤。
 */
    private fun httpsUpgraded(url: String?): String? {
        if (url == null || !url.startsWith("http://")) return url
        val host = url.removePrefix("http://").substringBefore('/')
        return if (host == "xhscdn.com" || host.endsWith(".xhscdn.com"))
            "https://" + url.removePrefix("http://")
        else url
    }

 /** JSON 字符串原语取值（JsonNull/非原语 → null）。 */
    private fun primStr(e: JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content

 /** 正文去标签压空白。 */
    private fun textPlain(html: String?): String? {
        val s = html ?: return null
        return s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
    }

 // ------------------------------------------------------------------
 // download（视频=直链；图文=多图拆分；音频=视频抽取；仅封面=首图）
 // ------------------------------------------------------------------

    override suspend fun download(info: ProbeInfo, mode: Mode, cookies: File?, taskDir: Path,
                                  audioFormat: String, onLog: (String) -> Unit,
                                  onProgress: (Float, String) -> Unit,
                                  cancelHandle: CancelHandle, runId: String,
                                  quality: String): List<Path> {
        return when (mode) {
            Mode.COVER -> listOf(coverOnly(info, taskDir, onProgress, cancelHandle::isCancelled))
            Mode.AUDIO -> audioFlow(info, taskDir, audioFormat, onProgress, cancelHandle::isCancelled)
            Mode.VIDEO ->
                if (info.hasImages) imagesFlow(info, taskDir, onProgress, cancelHandle::isCancelled)
                else videoFlow(info, taskDir, onProgress, cancelHandle::isCancelled)
        }
    }

 /** 视频流：直链 mp4 → 成品 + 伴生封面（首图）。 */
    private fun videoFlow(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit,
                          isCancelled: () -> Boolean): List<Path> {
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_MESSAGE)
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
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

 /** 图文流：多图逐张下载（title-1/2/3.jpg…）。 */
    private fun imagesFlow(info: ProbeInfo, taskDir: Path,
                           onProgress: (Float, String) -> Unit,
                           isCancelled: () -> Boolean): List<Path> {
        val urls = info.playUrl?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        if (urls.isEmpty()) throw EngineException(NO_PLAY_MESSAGE)
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val products = mutableListOf<Path>()
        urls.forEachIndexed { i, u ->
            val f = taskDir.resolve("img-$i")
            streamToFile(u, f, onProgress, isCancelled)
            val final = taskDir.resolve(safeFilename("$base-${i + 1}") + ".jpg")
            Files.move(f, final, StandardCopyOption.REPLACE_EXISTING)
            products.add(final) // Path 实现了 Iterable，+= 有重载歧义，须显式 add
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 音频流：视频直链落盘 → ffmpeg 抽取/转码 → 标签。 */
    private suspend fun audioFlow(info: ProbeInfo, taskDir: Path, audioFormat: String,
                                  onProgress: (Float, String) -> Unit,
                                  isCancelled: () -> Boolean): List<Path> {
        if (info.hasImages) throw EngineException("图文笔记没有音频可提取")
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_PLAY_MESSAGE)
        val fmt = if (audioFormat == "mp3" || audioFormat == "m4a") audioFormat else "mp3"
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
        val audio = post.extractAudio(raw, taskDir.resolve("out.$fmt"), fmt, isCancelled)
        val base = (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title
        val tagged = post.tagAudio(audio, null, info.title, info.author, "", isCancelled)
        val final = taskDir.resolve(safeFilename(base) + ".$fmt")
        Files.move(tagged, final, StandardCopyOption.REPLACE_EXISTING)
        onProgress(1.0f, DONE_LABEL)
        return listOf(final)
    }

 /** 仅封面：首图。 */
    private fun coverOnly(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean): Path {
        val url = info.coverUrl?.takeIf { it.isNotEmpty() }
            ?: info.playUrl?.split(";")?.firstOrNull { it.isNotBlank() }
            ?: throw EngineException(NO_COVER_MESSAGE)
        val cover = taskDir.resolve("cover.jpg")
        streamToFile(url, cover, onProgress, isCancelled)
        val final = taskDir.resolve(safeFilename(info.title) + ".jpg")
        Files.move(cover, final, StandardCopyOption.REPLACE_EXISTING)
        return final
    }

 /** 下载循环：Referer 恒带（xhscdn 直链稳妥起见）+ 64KB 分块 + 进度 + 逐块查取消。 */
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
        const val PROD_XHS_BASE_URL = "https://www.xiaohongshu.com"

 /** 生产入口：默认页面基址。 */
        fun production(ffmpegPath: String) = XhsEngine(PROD_XHS_BASE_URL, ffmpegPath)

 // probe 记忆化（进程级，LRU≤4）：二段式流程里队列任务会对同一 URL 复跑 probe——
 // 小红书对同 token 的秒级重复访问会下发反爬页（首访正常、重访被拒），memo 同时规避限频与重复抓取
        private val probeMemo = object : LinkedHashMap<String, ProbeInfo>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProbeInfo>?): Boolean = size > 4
        }

        internal fun clearProbeMemo() = probeMemo.clear()

        private const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        private const val DOWNLOAD_REFERER = "https://www.xiaohongshu.com/"
        private const val ORIGIN_BASE = "https://sns-video-bd.xhscdn.com"
        private const val CHUNK_BYTES = 1 shl 16
        private val ID_RE = Regex("[0-9A-Za-z]+")

        private const val NO_ID_MESSAGE = "小红书链接里找不到笔记 id，请用 App 内「分享→复制链接」得到的链接"
        private const val NO_PLAY_MESSAGE = "小红书未返回可用的媒体地址，笔记可能已被删除或需要登录"
        private const val NO_COVER_MESSAGE = "小红书未返回可用的封面地址"
        private const val STATE_FAIL_MESSAGE = "小红书页面解析失败（接口可能改版），请稍后重试"
        private const val PAGE_FAIL_MESSAGE = "小红书页面访问失败（可能缺少分享令牌），请用 App 内分享得到的原始链接"
        private const val DONE_LABEL = "完成"
    }
}