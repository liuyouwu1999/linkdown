package top.linkdown.android.engine.internal

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 批量子项引用（枚举产物：二段确认「标题 · N 条」+ 入队接口的子项 URL 源）。 */
data class ChildRef(val url: String, val title: String)

/** 参考实现 batch_ytdlp.py L7 `_SPACE_EXTRA`：B站 space 路径第 2 段白名单。 */
private val SPACE_EXTRA = setOf("video", "uploads", "lists", "index", "audio", "bangumi")

/**
 * 批量前置门（参考实现 batch_ytdlp.py is_batch_url L11-25 逐字平移；platform 形参改由 URL host
 * 推导≙上位路由）。仅纯列表 URL 过门——防 generic 抽取器把普通网页链接列表误判为批量
 * （设计）；混合型 watch&list 不过门（单视频路径+「仅下这一个」逃逸）。
 */
internal fun isBatchUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: "/"
 // ≙parse_qs(p.query)（默认丢弃空值与无 = 键）后 `"key" in q`
    fun qHas(key: String): Boolean = uri.rawQuery?.split('&')?.any { seg ->
        val i = seg.indexOf('=')
        i > 0 && seg.substring(0, i) == key && seg.substring(i + 1).isNotEmpty()
    } == true
    return when (platformOf(host)) {
        "youtube" -> path == "/playlist" && qHas("list")
        "netease" -> (path == "/playlist" || path == "/album") && qHas("id")
        "bilibili" -> when {
            host.endsWith("space.bilibili.com") -> {
                val seg = path.split('/').filter { it.isNotEmpty() }
 // 参考实现 ：首段必须纯数字 uid；len==1 或第 2 段 ∈ _SPACE_EXTRA
                if (seg.isEmpty() || !seg[0].all { it.isDigit() }) false
                else seg.size == 1 || seg[1] in SPACE_EXTRA
            }
            else -> path.startsWith("/ep") || path.startsWith("/ss") || path.startsWith("/bangumi/play/")
        }
        else -> false
    }
}

/**
 * entry→URL（参考实现 item_url L28-37 逐字平移）：raw_url 以 http 开头原样直用；否则 id 基构造
 * 三分支——YouTube `watch?v=` / 网易云 `song?id=` / B站 数字→bangumi ep 否则 video。
 */
internal fun itemUrl(platform: String, mediaId: String, rawUrl: String): String {
    if (rawUrl.startsWith("http")) return rawUrl
    return when (platform) {
        "youtube" -> "https://www.youtube.com/watch?v=$mediaId"
        "netease" -> "https://music.163.com/song?id=$mediaId"
        else -> if (mediaId.isNotEmpty() && mediaId.all { it.isDigit() }) {   // ≙Python str.isdigit（""→False→video 分支）
            "https://www.bilibili.com/bangumi/play/ep$mediaId"
        } else {
            "https://www.bilibili.com/video/$mediaId"
        }
    }
}

/**
 * 枚举 dump 解析纯函数（参考实现 enumerate_batch L72-88 entries 循环平移）：
 * 判型 `_type=="playlist"` 才解析——单视频 dump（_type=video/缺省）返回空列表（弹窗判非批量
 * 走单视频路径）；entry 参考实现 `if not e`（null/非对象）与无 id 者跳过；title 缺失/空回退 mid；
 * 相对 url 按 root.webpage_url 推导平台后 id 基构造（[itemUrl]，弃 webpage_url 字符串拼接）。
 */
internal fun parseEnumerateJson(root: JsonObject): List<ChildRef> {
    if (str(root["_type"]) != "playlist") return emptyList()
    val entries = root["entries"] as? JsonArray ?: return emptyList()
    val platform = platformOf(uriHost(str(root["webpage_url"]) ?: ""))
    return entries.mapNotNull { e ->
        val obj = e as? JsonObject ?: return@mapNotNull null   // 参考实现 `if not e: continue`
        val mid = str(obj["id"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ChildRef(
            url = itemUrl(platform, mid, str(obj["url"]) ?: ""),
            title = str(obj["title"])?.takeIf { it.isNotEmpty() } ?: mid,  // 参考实现 `or mid`
        )
    }
}

/** ≙参考实现 `e.get(key)` 后 str()/真值链：缺失或 null 视为不存在（JsonNull 不当值用）。 */
private fun str(e: JsonElement?): String? =
    (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** ≙urlparse(url).hostname：host 小写或 ""（非法 URL 一律 ""，调用方回退 else 分支）。 */
private fun uriHost(url: String): String =
    runCatching { URI(url).host?.lowercase() }.getOrNull() ?: ""

/** platform 语义 token（参考实现 is_batch_url/item_url 的 platform 形参值域）：host→youtube/netease/bilibili。 */
private fun platformOf(host: String): String = when {
    host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "youtube"
    host == "music.163.com" || host.endsWith(".music.163.com") -> "netease"
    host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" -> "bilibili"
    else -> ""
}
