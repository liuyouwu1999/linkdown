package top.linkdown.android.engine.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/** 短链白名单（参考实现 shortlink.py EXPAND_HOSTS 逐字对齐）。yt-dlp 提取器不认这些短域，
 *  裸短链会落 generic 提取器报错——与参考实现 web 端一致，在调引擎前先展开。 */
internal val SHORTLINK_HOSTS = setOf("v.douyin.com", "b23.tv", "qishui.douyin.com", "163cn.tv")

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/** ≙参考实现 shortlink.py expand 的 host 判定：URI host 小写 ∈ 白名单（畸形 URL 一律 false）。 */
internal fun isShortlinkUrl(url: String): Boolean = runCatching {
    URI(url).host?.lowercase() in SHORTLINK_HOSTS
}.getOrDefault(false)

private val BANGUMI_ID = Regex("^(ep|ss)\\d+$")
private val VIDEO_ID = Regex("^(av\\d+|BV[0-9A-Za-z]+)$")

/**
 * b23.tv 纯 id 形态（ep/ss 数字、av 数字、BV 码）离线直拼 canonical，不出网：
 * 短链域可能被 DNS 污染（真机实测解析到无关境外 IP），网络展开不可依赖；非 id 形态
 * （随机短码）返回 null 仍走网络展开。host 严格相等，伪装域不命中。
 */
internal fun canonicalBilibiliOffline(url: String): String? {
    val u = runCatching { URI(url) }.getOrNull() ?: return null
    if (u.host?.lowercase() != "b23.tv") return null
    val seg = u.path?.trim('/').orEmpty()
    val query = u.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
    return when {
        seg.matches(BANGUMI_ID) -> "https://www.bilibili.com/bangumi/play/$seg$query"
        seg.matches(VIDEO_ID) -> "https://www.bilibili.com/video/$seg$query"
        else -> null
    }
}

/** ≙参考实现 _UA。 */
private const val EXPAND_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

/** 默认展开客户端：关闭自动跟随（跟跳由本函数手动控制）；连接/读 15s（≙参考实现 timeout=15），
 *  callTimeout 20s 封顶单次展开总时长（防慢滴流服务器拖住 Parsing 态）。 */
private val expandClient: OkHttpClient by lazy {
    OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
}

/**
 * 短链展开（参考实现 shortlink.py expand 平移）：GET + 手动跟重定向；301/302/303/307/308 才跟，
 * 相对 Location 按当前 URL 拼接；Location 缺失或非 3xx 即停；最多 maxHops 跳（超出停在最新
 * Location，≙参考实现 for 循环自然结束）；Location 无法拼接时停在当前链接（比参考实现的
 * join 抛错更收敛）。网络异常向外抛——调用方 runCatching 回退原链接，不得阻塞下单。
 * 注意：自带的 client 必须 followRedirects(false)（默认客户端已关）。
 */
internal suspend fun expandShortlink(
    url: String,
    client: OkHttpClient = expandClient,
    maxHops: Int = 5,
): String {
    canonicalBilibiliOffline(url)?.let { return it }
    return withContext(Dispatchers.IO) {
    var current = url
    repeat(maxHops) {
        client.newCall(
            Request.Builder().url(current).header("User-Agent", EXPAND_UA).build()
        ).execute().use { r ->
            val loc = if (r.code in REDIRECT_CODES) r.header("Location") else null
            if (loc.isNullOrEmpty()) return@withContext current
            current = current.toHttpUrl().resolve(loc)?.toString() ?: return@withContext current
        }
    }
    current
    }
}
