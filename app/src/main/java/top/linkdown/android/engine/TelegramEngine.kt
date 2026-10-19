package top.linkdown.android.engine

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import top.linkdown.android.engine.internal.FfmpegPost
import top.linkdown.android.engine.internal.safeFilename
import top.linkdown.android.pipeline.PlatformDirs

/**
 * Telegram 公开频道/群帖下载引擎（2026-10-24 spike 实测定稿）。
 * 原理：t.me/<chan>/<id> → GET t.me/s/<chan>/<id>（网页预览，无登录/无重反爬），
 * 按 data-post="<chan>/<id>" 锁定目标 widget 块——/s/ 页会带前后帖（?single 不隔离），
 * 不按块裁剪会串台。块内提取：
 * - 视频：<video src="https://cdnN.telesco.pe/file/….mp4?token=…">（预览流 mp4，含音轨）
 * - 图片：background-image url('…telesco.pe/…')（须排除 telegram.org/img/emoji 表情贴纸）
 * 媒体直链为带 token 的公开 CDN（telesco.pe/cdn-telegram.org），裸 GET 即下、无需 Referer；
 * token 有时效（现取现下即无害）。
 * 边界（v1）：预览流画质≠频道原件最高画质；document 类文件预览页不提供；私有/登录内容不支持；
 * 相册每张各具独立链接（t.me/<chan>/<id+1>…），本引擎只下链接指向的那一条。
 * 境内网络需全局代理（与 YouTube 同类；OkHttp 默认 ProxySelector 随系统代理）。
 * cookies 参数按约定忽略（公开内容免登录）。
 */
class TelegramEngine(
    private val previewBaseUrl: String,
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
        val (channel, postId) = parsePost(url) ?: throw EngineException(NO_ID_MESSAGE)
        val html = fetchPreview(channel, postId) ?: throw EngineException(PREVIEW_FAIL_MESSAGE)
        val block = targetBlock(html, channel, postId) ?: throw EngineException(NOT_FOUND_MESSAGE)
        val videos = block.videos
        val photos = block.photos
        if (videos.isEmpty() && photos.isEmpty()) throw EngineException(NO_MEDIA_MESSAGE)
        val hasImages = videos.isEmpty()
        // 标题缺省只给帖子号（displayName 会拼「频道 - 」，回退再带频道名会叠成「chan - chan 123」）
        val title = block.text.takeIf { it.isNotEmpty() }?.take(40) ?: postId
        return ProbeInfo(
            platform = PlatformDirs.TELEGRAM,
            mediaId = "${block.channel}_$postId",
            sourceUrl = url,
            title = title,
            author = block.channel,
            coverUrl = block.poster ?: photos.firstOrNull(),
            durationSec = null,
            isVip = false,
            playUrl = if (hasImages) photos.joinToString(";") else videos.first(),
            hasImages = hasImages,
        )
    }

 /** 链接解析：t.me / telegram.me 的 <chan>/<数字id>（容忍 /s/ 前缀与 ?single 等查询参数）。
 *  非帖子形态（share/joinchat/私有 +hash 邀请链）一律 null → 人话报错。internal 供单测。 */
    internal fun parsePost(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = (uri.host ?: "").lowercase()
        if (host != "t.me" && host != "telegram.me" &&
            !host.endsWith(".t.me") && !host.endsWith(".telegram.me")) return null
        val segs = uri.path?.split("/")?.filter { it.isNotBlank() } ?: return null
 // /s/<chan>/<id> 预览形态剥掉 s 段
        val segs2 = if (segs.firstOrNull() == "s") segs.drop(1) else segs
        if (segs2.size != 2) return null
        val channel = segs2[0]
        val id = segs2[1]
        if (channel.isEmpty() || !channel.matches(CHANNEL_RE) || !id.matches(Regex("\\d+"))) return null
        return channel to id
    }

    internal class PostBlock(
        val channel: String,   // 预览页规范频道名（大小写归一后回填，判重不随 URL 大小写分裂）
        val videos: List<String>,
        val photos: List<String>,
        val poster: String?,
        val text: String,
    )

 /** 定位 data-post="<chan>/<id>" 的 widget 块并提取媒体（内部函数供单测直测纯解析）。
 *  预览页 data-post 用频道注册名的规范大小写，与链接大小写无关 → 小写比对；命中后回取
 *  规范频道名（probe 的 mediaId/author 用它，判重不随 URL 大小写分裂）。媒体按 class 收窄：
 *  video 类标签 / photo_wrap 锚点（相册图）/ video_thumb（视频海报）。 */
    internal fun targetBlock(html: String, channel: String, postId: String): PostBlock? {
        val target = "$channel/$postId".lowercase()
        val blocks = html.split(Regex("(?=<div class=\"tgme_widget_message_wrap)"))
        for (b in blocks) {
            val m = REGEX_DATA_POST.find(b) ?: continue
            if (!m.groupValues[1].equals(target, ignoreCase = true)) continue
            val canonical = m.groupValues[1].substringBefore('/')
            val videos = REGEX_VIDEO_TAG.findAll(b)
                .filter { "tgme_widget_message_video" in it.value }
                .map { tag -> REGEX_VIDEO_SRC.find(tag.value)?.groupValues?.get(1) }
                .filterNotNull()
                .map(::absolutize)
                .toList()
            val photos = REGEX_PHOTO_ANCHOR.findAll(b).flatMap { anchor ->
                (REGEX_BG.findAll(anchor.value).map { it.groupValues[1] }.toList() +
                    REGEX_VIDEO_SRC.findAll(anchor.value).map { it.groupValues[1] }.toList()).asSequence()
            }.toList()
                .map(::absolutize)
                .filterNot { it.contains("telegram.org/img/") } // 剔表情贴纸；媒体 CDN（telesco.pe 等）保留
                .distinct()
            val poster = if (videos.isNotEmpty()) {
                REGEX_THUMB.findAll(b).flatMap { REGEX_BG.findAll(it.value).map { g -> g.groupValues[1] } }
                    .firstOrNull()?.let(::absolutize)
            } else {
                photos.firstOrNull()
            }
            val text = REGEX_TEXT.find(b)?.groupValues?.get(1)
                ?.let { decodeEntities(it.replace(Regex("<[^>]+>"), " ")) }
                ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return PostBlock(canonical, videos, photos, poster, text)
        }
        return null
    }

 /** 协议相对 URL（//cdn…）补 https。 */
    private fun absolutize(u: String): String =
        if (u.startsWith("//")) "https:$u" else u

 /** 实体解码（帖子文本常见实体；不追求全量，覆盖预览页出现的即可）。 */
    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&") // 必须最后替换，防 &amp;lt; 被双解码

    private fun fetchPreview(channel: String, postId: String): String? = runCatching {
        client.newCall(
            Request.Builder().url("$previewBaseUrl/s/$channel/$postId")
                .header("User-Agent", UA).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }.getOrNull()

 // ------------------------------------------------------------------
 // download（视频=直链 mp4+海报伴生；图片帖=逐张 jpg；音频=ffmpeg 抽取；仅封面）
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

 /** 视频流：直链 mp4 → 成品 + 伴生海报。 */
    private fun videoFlow(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit,
                          isCancelled: () -> Boolean): List<Path> {
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_MEDIA_MESSAGE)
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
        val final = taskDir.resolve(safeFilename(displayName(info)) + ".mp4")
        Files.move(raw, final, StandardCopyOption.REPLACE_EXISTING)
        val products = mutableListOf(final)
        info.coverUrl?.takeIf { it.isNotEmpty() }?.let { cu ->
            val cover = taskDir.resolve("cover.jpg")
            if (fetchCover(cu, cover, isCancelled)) {
                val coverFinal = taskDir.resolve(safeFilename("${displayName(info)}-cover") + ".jpg")
                Files.move(cover, coverFinal, StandardCopyOption.REPLACE_EXISTING)
                products.add(coverFinal)
            }
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 图片流：图片帖（含相册单条）逐张 jpg。 */
    private fun imagesFlow(info: ProbeInfo, taskDir: Path,
                           onProgress: (Float, String) -> Unit,
                           isCancelled: () -> Boolean): List<Path> {
        val urls = info.playUrl?.split(";")?.filter { it.isNotBlank() } ?: emptyList()
        if (urls.isEmpty()) throw EngineException(NO_MEDIA_MESSAGE)
        val products = mutableListOf<Path>()
        urls.forEachIndexed { i, u ->
            val f = taskDir.resolve("img-$i")
            streamToFile(u, f, onProgress, isCancelled)
            val suffix = if (urls.size == 1) "" else "-${i + 1}"
            val final = taskDir.resolve(safeFilename("${displayName(info)}$suffix") + ".jpg")
            Files.move(f, final, StandardCopyOption.REPLACE_EXISTING)
            products.add(final)
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 音频流：视频直链落盘 → ffmpeg 抽取/转码 → 标签（图片帖无音轨直接人话报错）。 */
    private suspend fun audioFlow(info: ProbeInfo, taskDir: Path, audioFormat: String,
                                  onProgress: (Float, String) -> Unit,
                                  isCancelled: () -> Boolean): List<Path> {
        if (info.hasImages) throw EngineException(NO_AUDIO_MESSAGE)
        val url = info.playUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_MEDIA_MESSAGE)
        val fmt = if (audioFormat == "mp3" || audioFormat == "m4a") audioFormat else "mp3"
        val raw = taskDir.resolve("raw.mp4")
        streamToFile(url, raw, onProgress, isCancelled)
        val audio = post.extractAudio(raw, taskDir.resolve("out.$fmt"), fmt, isCancelled)
        var cover: Path? = null
        info.coverUrl?.takeIf { it.isNotEmpty() }?.let { cu ->
            val f = taskDir.resolve("cover.jpg")
            if (fetchCover(cu, f, isCancelled)) cover = f
        }
        val tagged = post.tagAudio(audio, cover, info.title, info.author, "", isCancelled)
        val final = taskDir.resolve(safeFilename(displayName(info)) + ".$fmt")
        Files.move(tagged, final, StandardCopyOption.REPLACE_EXISTING)
        val products = mutableListOf(final)
        if (cover != null && Files.exists(cover)) {
            val coverFinal = taskDir.resolve(safeFilename("${displayName(info)}-cover") + ".jpg")
            Files.move(cover, coverFinal, StandardCopyOption.REPLACE_EXISTING)
            products.add(coverFinal)
        }
        onProgress(1.0f, DONE_LABEL)
        return products
    }

 /** 仅封面：视频海报 / 图片帖首图。 */
    private fun coverOnly(info: ProbeInfo, taskDir: Path,
                          onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean): Path {
        val url = info.coverUrl?.takeIf { it.isNotEmpty() } ?: throw EngineException(NO_COVER_MESSAGE)
        val cover = taskDir.resolve("cover.img")
        streamToFile(url, cover, onProgress, isCancelled)
        val final = taskDir.resolve(safeFilename(info.title) + ".jpg")
        Files.move(cover, final, StandardCopyOption.REPLACE_EXISTING)
        return final
    }

 /** 成品命名：频道（作者）- 标题（正文前 40 字/回退频道+帖子号），与微博/小红书同惯例。 */
    private fun displayName(info: ProbeInfo): String =
        (if (info.author.isNotEmpty()) "${info.author} - " else "") + info.title

 /** 下载循环：64KB 分块 + Content-Length 进度 + 逐块查取消（直链裸 GET 即可，无需 Referer）。 */
    private fun streamToFile(url: String, dst: Path,
                             onProgress: (Float, String) -> Unit, isCancelled: () -> Boolean) {
        downloadClient.newCall(Request.Builder().url(url)
            .header("User-Agent", UA)
            .build()).execute().use { resp ->
            val body = resp.body ?: throw EngineException(DOWNLOAD_FAIL_MESSAGE)
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
        const val PROD_PREVIEW_BASE_URL = "https://t.me"

 /** 生产入口：默认预览基址（ffmpeg 路径来自 FfmpegLocator）。 */
        fun production(ffmpegPath: String) = TelegramEngine(PROD_PREVIEW_BASE_URL, ffmpegPath)

        private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val CHUNK_BYTES = 1 shl 16
        private val CHANNEL_RE = Regex("[A-Za-z0-9_]+")
        private val REGEX_DATA_POST = Regex("data-post=\"([^\"]+)\"")
        // 媒体按真实 class 收窄：video 类标签（防链接预览视频误捕）、photo_wrap 锚点（相册图）、
        // video_thumb（视频海报）——详见 spike 与双审记录（2026-10-24）
        private val REGEX_VIDEO_TAG = Regex("<video\\b[^>]*>.*?</video>|<video\\b[^>]*/>", setOf(RegexOption.DOT_MATCHES_ALL))
        private val REGEX_VIDEO_SRC = Regex("src=\"([^\"]+)\"")
        private val REGEX_PHOTO_ANCHOR = Regex("<a\\b[^>]*class=\"[^\"]*tgme_widget_message_photo_wrap[^\"]*\"[^>]*>")
        private val REGEX_THUMB = Regex("<a\\b[^>]*class=\"[^\"]*tgme_widget_message_video_thumb[^\"]*\"[^>]*>")
        private val REGEX_BG = Regex("background-image:\\s*url\\('([^']+)'\\)")
        private val REGEX_TEXT = Regex("<div class=\"tgme_widget_message_text[^\"]*\"[^>]*>(.*?)</div>", setOf(RegexOption.DOT_MATCHES_ALL))

        private const val DONE_LABEL = "完成"
        private const val NO_ID_MESSAGE = "Telegram 链接里找不到帖子 id，请确认是公开频道/群的帖子链接（t.me/频道名/数字）"
        private const val NOT_FOUND_MESSAGE = "预览页里没有这条帖子：可能是私有频道，或帖子已被删除"
        private const val PREVIEW_FAIL_MESSAGE = "Telegram 预览页访问失败，请检查网络（境内需全局代理）后重试"
        private const val NO_MEDIA_MESSAGE = "这条帖子里没有可下载的视频或图片"
        private const val NO_AUDIO_MESSAGE = "图片帖没有音轨可提取"
        private const val NO_COVER_MESSAGE = "这条帖子没有可用的封面图"
        private const val DOWNLOAD_FAIL_MESSAGE = "下载失败（响应为空）"
    }
}
