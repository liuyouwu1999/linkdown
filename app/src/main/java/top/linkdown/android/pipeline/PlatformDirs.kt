package top.linkdown.android.pipeline

import java.net.URI

object PlatformDirs {
 // 入库目录名常量：原生引擎 probe 填 ProbeInfo.platform 用（=判重前缀/货架分组同源）；
 // 原生引擎平台身份由路由固定（EngineRouter），不走 dirFor（测试 mock host 会落「其他」）
    const val QISHUI = "汽水"
    const val DOUYIN = "抖音"
    const val WEIBO = "微博"
    const val XHS = "小红书"

    private val byHost = linkedMapOf(
        "qishui.douyin.com" to QISHUI,
        "bilibili.com" to "哔哩哔哩", "b23.tv" to "哔哩哔哩",
        "music.163.com" to "网易云",
        "youtube.com" to "YouTube", "youtu.be" to "YouTube",
        "x.com" to "X", "twitter.com" to "X",
        "instagram.com" to "Instagram",
        "tiktok.com" to "TikTok",
        "soundcloud.com" to "SoundCloud",
        "weibo.com" to WEIBO, "weibo.cn" to WEIBO, "m.weibo.cn" to WEIBO,
        "xiaohongshu.com" to XHS, "xhslink.com" to XHS,
        "douyin.com" to DOUYIN,
        "iesdouyin.com" to DOUYIN, // 补录：分享页中转域，同归抖音目录
    )

    fun dirFor(url: String): String {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return "其他"
        val hit = byHost.entries.filter { host == it.key || host.endsWith("." + it.key) }
            .maxByOrNull { it.key.length } ?: return "其他"
        return hit.value
    }
}
