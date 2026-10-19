package top.linkdown.android.pipeline

import android.content.Context
import java.net.URI
import top.linkdown.android.engine.DouyinEngine
import top.linkdown.android.engine.FfmpegLocator
import top.linkdown.android.engine.MediaEngine
import top.linkdown.android.engine.QishuiEngine
import top.linkdown.android.engine.TelegramEngine
import top.linkdown.android.engine.WeiboEngine
import top.linkdown.android.engine.XhsEngine
import top.linkdown.android.engine.YtdlpEngine

/** 路由判定结果（host/path 纯函数产物；实例化薄壳见 [EngineRouter.route]）。 */
enum class EngineKind { YTDLP, DOUYIN, QISHUI, WEIBO, XHS, TELEGRAM }

/**
 * 路由（设计：按原始 URL host 判定，最具体优先——qishui 规则先于 *.douyin.com 通配，
 * 参考实现 router.py「顺序即优先级」同款；path 前缀须显式先判）。
 * 与 参考实现 router 的两处有意偏离（设计拍板）：
 * - music.douyin.com 非 /qishui path → YtdlpEngine（ 现状，特例先于 *.douyin.com 通配）；
 * - www.qishui.com 本批有意降级不接（→ YtdlpEngine，EngineRouterTest 专用断言防将来通配误捕）。
 */
object EngineRouter {

    fun routeFor(url: String): EngineKind {
        val uri = runCatching { URI(url) }.getOrNull() ?: return EngineKind.YTDLP
        val host = uri.host?.lowercase() ?: return EngineKind.YTDLP
        val path = uri.path ?: ""
 // music.douyin.com 特例先行（不得被 *.douyin.com 通配截胡）
        if (host == "music.douyin.com" || host.endsWith(".music.douyin.com")) {
            return if (path.startsWith("/qishui")) EngineKind.QISHUI else EngineKind.YTDLP
        }
        if (host == "qishui.douyin.com" || host.endsWith(".qishui.douyin.com")) return EngineKind.QISHUI
        if (host == "douyin.com" || host.endsWith(".douyin.com")) return EngineKind.DOUYIN
        if (host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")) return EngineKind.DOUYIN
        if (host == "weibo.com" || host.endsWith(".weibo.com") ||
            host == "weibo.cn" || host.endsWith(".weibo.cn")) return EngineKind.WEIBO
        if (host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
            host == "xhslink.com" || host.endsWith(".xhslink.com")) return EngineKind.XHS
        if (host == "t.me" || host == "telegram.me" || host.endsWith(".t.me") ||
            host.endsWith(".telegram.me")) return EngineKind.TELEGRAM
        return EngineKind.YTDLP
    }

 /** 实例化薄壳：原生引擎按次构造（ffmpeg 路径来自 FfmpegLocator）；YtdlpEngine 沿用既有 object。 */
    fun route(kind: EngineKind, context: Context): MediaEngine = when (kind) {
        EngineKind.QISHUI -> QishuiEngine.production(FfmpegLocator.ffmpegPath(context))
        EngineKind.DOUYIN -> DouyinEngine.production(FfmpegLocator.ffmpegPath(context))
        EngineKind.WEIBO -> WeiboEngine.production(FfmpegLocator.ffmpegPath(context))
        EngineKind.XHS -> XhsEngine.production(FfmpegLocator.ffmpegPath(context))
        EngineKind.TELEGRAM -> TelegramEngine.production(FfmpegLocator.ffmpegPath(context))
        EngineKind.YTDLP -> YtdlpEngine
    }
}
