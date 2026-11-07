package top.linkdown.android.cookies

import java.io.File
import top.linkdown.android.pipeline.PlatformDirs

object CookieStore {
    fun bilibili(sessdata: String?, filesDir: File): File? {
        if (sessdata.isNullOrBlank()) return existing("bilibili", filesDir)
        return write("bilibili", NetscapeCookieFile.fromPairs("SESSDATA=$sessdata", ".bilibili.com"), filesDir)
    }

 /** 网易云 cookie 探测：只认登录中心 WebView 写入的 netease.txt（无手输 SESSDATA 通道）。 */
    fun netease(filesDir: File): File? = existing("netease", filesDir)

 /** 抖音 cookie 探测：只认登录中心 WebView 写入的 douyin.txt（扫码/验证码登录， 实况图通道）。 */
    fun douyin(filesDir: File): File? = existing("douyin", filesDir)

 /**
 * 路由平台→cookie 文件选择（ 设计「平台化 cookie 文件」，路由平台名=PlatformDirs.dirFor）：
 * 哔哩哔哩→bilibili.txt（sessdata 快照兜底+既有探测， 现状）；网易云→netease.txt（仅既有探测，
 * sessdata 快照为 B站 手输通道不适用）；抖音→douyin.txt（ 实况签名接口的登录态来源）；
 * 其余平台→null（原生引擎不消费 cookie，yt-dlp 平台暂无自动登录——设置页「暂不支持自动登录」同口径）。
 */
    fun forUrl(url: String, sessdata: String?, filesDir: File): File? = when (PlatformDirs.dirFor(url)) {
        "哔哩哔哩" -> bilibili(sessdata, filesDir)
        "网易云" -> netease(filesDir)
        "抖音" -> douyin(filesDir)
        else -> null
    }

    fun existing(platform: String, filesDir: File): File? =
        File(filesDir, "cookies/$platform.txt").takeIf { it.exists() }
    fun write(platform: String, entries: List<NetscapeCookieFile.Entry>, filesDir: File): File =
        File(filesDir, "cookies/$platform.txt").also { NetscapeCookieFile.write(entries, it) }

    /** 登录中心「清除」：删平台 cookie 文件（引擎侧登出）。返回是否确有文件被删。 */
    fun delete(platform: String, filesDir: File): Boolean =
        File(filesDir, "cookies/$platform.txt").let { it.exists() && it.delete() }
}
