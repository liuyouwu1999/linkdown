package top.linkdown.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import top.linkdown.android.engine.DouyinEngine
import top.linkdown.android.engine.Mode
import top.linkdown.android.engine.QishuiEngine
import top.linkdown.android.engine.YtdlpEngine

/**
 * 功能任务 路由/折叠单测（按设计）。
 * 路由：按原始 URL host 判定、最具体优先——music.douyin.com 非 /qishui 特例先行 → Ytdlp，
 * qishui.douyin.com 先于 *.douyin.com 通配；www.qishui.com 本批有意降级 → Ytdlp（专用断言防通配误捕）。
 * 折叠：qishui 凡含视频（或音频）的组合折叠为一次音频下载（伴生封面由引擎自带，不重复）；
 * 仅封面 → 仅封面；douyin/Ytdlp 任意直通不折叠。
 */
class EngineRouterTest {

 // ---- 路由表（设计逐条 + 特例/降级专用断言） ----

    @Test fun `douyin hosts route to douyin engine`() {
        assertEquals(EngineKind.DOUYIN, EngineRouter.routeFor("https://www.douyin.com/video/7300000000000000000"))
        assertEquals(EngineKind.DOUYIN, EngineRouter.routeFor("https://v.douyin.com/iABC12/"))
        assertEquals(EngineKind.DOUYIN, EngineRouter.routeFor("https://www.iesdouyin.com/share/video/7300000000000000000"))
        assertEquals(EngineKind.DOUYIN, EngineRouter.routeFor("https://iesdouyin.com/share/video/7300000000000000000"))
    }

    @Test fun `qishui short and music qishui path route to qishui engine`() {
        assertEquals(EngineKind.QISHUI, EngineRouter.routeFor("https://qishui.douyin.com/s/iXXjp58L/"))
        assertEquals(EngineKind.QISHUI, EngineRouter.routeFor("https://music.douyin.com/qishui/share/track?track_id=7159634662997968910"))
    }

    @Test fun `music douyin non qishui path is ytdlp special case before wildcard`() {
        assertEquals(EngineKind.YTDLP, EngineRouter.routeFor("https://music.douyin.com/song?x=1"))
    }

    @Test fun `www qishui com intentionally downgraded to ytdlp`() {
 // 参考实现 router 支持该 host， 设计有意降级不接；此断言防将来 *.douyin.com 之外通配误捕
        assertEquals(EngineKind.YTDLP, EngineRouter.routeFor("https://www.qishui.com/s/abc"))
    }

    @Test fun `unrelated and garbage urls fall back to ytdlp`() {
        assertEquals(EngineKind.YTDLP, EngineRouter.routeFor("https://example.com/video/1"))
        assertEquals(EngineKind.YTDLP, EngineRouter.routeFor("https://www.bilibili.com/video/BV1xx411c7XW"))
        assertEquals(EngineKind.YTDLP, EngineRouter.routeFor("不是链接"))
    }

 // ---- foldedModes 折叠加表（设计矩阵） ----

    private val qishui = QishuiEngine("http://localhost:1", "ffmpeg")
    private val douyin = DouyinEngine("http://localhost:1", "http://localhost:1", "ffmpeg")

    @Test fun `qishui video plus audio folds to single audio`() {
        assertEquals(listOf(Mode.AUDIO), foldedModes(DownloadModes(video = true, audio = true, cover = false), qishui))
    }

    @Test fun `qishui video only folds to audio`() {
        assertEquals(listOf(Mode.AUDIO), foldedModes(DownloadModes(video = true, audio = false, cover = false), qishui))
    }

    @Test fun `qishui cover only stays cover`() {
        assertEquals(listOf(Mode.COVER), foldedModes(DownloadModes(video = false, audio = false, cover = true), qishui))
    }

    @Test fun `qishui video plus cover runs audio with companion cover like video audio`() {
 // 设计未定义此格 → 按「音频+伴生封面」同 [VIDEO,AUDIO] 语义执行（本断言即拍板记录）
        assertEquals(listOf(Mode.AUDIO), foldedModes(DownloadModes(video = true, audio = false, cover = true), qishui))
    }

    @Test fun `qishui audio plus cover merges into one audio per spec section 6 last row`() {
        assertEquals(listOf(Mode.AUDIO), foldedModes(DownloadModes(video = false, audio = true, cover = true), qishui))
    }

    @Test fun `qishui audio only stays audio`() {
        assertEquals(listOf(Mode.AUDIO), foldedModes(DownloadModes(video = false, audio = true, cover = false), qishui))
    }

    @Test fun `douyin and ytdlp never fold`() {
        assertEquals(
            listOf(Mode.VIDEO, Mode.AUDIO, Mode.COVER),
            foldedModes(DownloadModes(video = true, audio = true, cover = true), douyin))
        assertEquals(
            listOf(Mode.VIDEO, Mode.AUDIO),
            foldedModes(DownloadModes(video = true, audio = true, cover = false), YtdlpEngine))
    }
}
