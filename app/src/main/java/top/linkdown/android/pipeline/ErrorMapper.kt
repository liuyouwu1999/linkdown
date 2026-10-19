package top.linkdown.android.pipeline

object ErrorMapper {
    fun humanize(t: Throwable, platform: String? = null): String {
        val msg = t.message ?: ""
        return when {
            msg.contains("DownloadCancelled") -> "已取消"
 // 登录墙（设计）：会员专享/age-gate/频道会员等内容需登录才能下载——签名大小写不敏感
 // （yt-dlp 实际输出首字母大写「Login required」）；XX=平台名透传（DownloadQueue 传
 // PlatformDirs.dirFor(url) 的路由平台名），未传/空白兜底「B站」（登录中心首推平台）
            listOf("login required", "confirm your age", "members on this channel")
                .any { msg.lowercase().contains(it) } ->
                "需要登录：请在主页「登录账号」里登录 ${platform?.takeIf { it.isNotBlank() } ?: "B站"}"
            msg.contains("Unable to download JSON metadata") ||
                msg.contains("No address associated with hostname") ||
                msg.contains("timed out") -> "网络不给力：请检查网络后重试"
 // 起抖音/汽水已进原生引擎，此分支兜底所有 yt-dlp 不支持的链接（设计只改文案不加签名）；
 // No video formats found 保留——B站重试穷尽后「引导配置 SESSDATA」人话的载体
            msg.contains("Unsupported URL") || msg.contains("No video formats found") ->
                "暂不支持这个链接（支持平台见说明）"
            msg.contains("does not look like a Netscape format cookies file") ->
                "内部错误 C1（cookie 文件损坏，删 App 数据可解）"
            else -> {
                val first = msg.lineSequence().firstOrNull { it.isNotBlank() }?.take(120)
                "下载失败：${first ?: "未知错误"}"
            }
        }
    }
}
