package top.linkdown.android.engine.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScopeTest {

    private val fixture = """{"code":0,"data":{"bvid":"BV1rYYr6qEkx","title":"合集样本",
        "videos":30,"pages":[{"page":1,"part":"A","duration":100,"cid":1},
        {"page":2,"part":"","duration":200,"cid":2}],
        "owner":{"mid":1,"name":"UP"},
        "ugc_season":{"id":9,"title":"一歌手一合集","ep_count":3,"is_pay_season":false,"sign_state":0,
        "sections":[{"episodes":[
            {"bvid":"BVep1","title":"ep短标题","pages":[{"page":1},{"page":2}],
             "arc":{"title":"集标题A","duration":300,"pic":"https://i/1.jpg","author":"歌手A"}},
            {"bvid":"BVep2","title":"ep短标题2","pages":[{"page":1}],
             "arc":{"title":"集标题B","duration":400,"pic":"https://i/2.jpg","author":"歌手B"}}]}]}}}""".replace("\n", "")

    @Test fun `isVideoUrl 双域与形状`() {
        assertTrue(isVideoUrl("https://www.bilibili.com/video/BV1rYYr6qEkx/"))
        assertTrue(isVideoUrl("https://www.bilibili.com/video/BV1rYYr6qEkx?p=3"))
        assertTrue(isVideoUrl("https://m.bilibili.com/video/BV1rYYr6qEkx"))
        assertTrue(isVideoUrl("https://www.bilibili.com/video/av170001"))
        assertFalse(isVideoUrl("https://space.bilibili.com/123/video"))
        assertFalse(isVideoUrl("https://www.bilibili.com/bangumi/play/ep1"))
    }

    @Test fun `解析 分P与合集子项与截断`() {
        val r = parseVideoScope(fixture)
        val sc = r.scope!!
        assertNull(r.reason)
        assertEquals("BV1rYYr6qEkx", sc.bvid); assertEquals("合集样本", sc.title)
        assertEquals("一歌手一合集", sc.seasonTitle); assertEquals(3, sc.epCount)
        assertTrue(sc.truncated)                       // 2 < 3
        assertTrue(sc.hit())
        assertEquals("BV1rYYr6qEkx_p1", sc.pages[0].mediaId)
        assertEquals("https://www.bilibili.com/video/BV1rYYr6qEkx?p=1", sc.pages[0].url)
        assertEquals("P1 A", sc.pages[0].title); assertEquals(100L, sc.pages[0].durationSec)
        assertEquals("P2", sc.pages[1].title)          // part 空 → 仅 P{n}
        assertEquals("BVep1", sc.episodes[0].mediaId)
        assertEquals("https://www.bilibili.com/video/BVep1", sc.episodes[0].url)
        assertEquals("集标题A (多P，仅下 P1)", sc.episodes[0].title)   // ep.pages=2 → 后缀
        assertEquals("集标题B", sc.episodes[1].title)
    }

    @Test fun `解析 非0code reason 映射`() {
        assertEquals(ProbeReason.GONE, parseVideoScope("""{"code":-404,"data":null}""").reason)
        assertEquals(ProbeReason.DEGRADED, parseVideoScope("""{"code":-412,"data":null}""").reason)
        assertEquals(ProbeReason.DEGRADED, parseVideoScope("<html>").reason)
        assertTrue(parseVideoScope(fixture).scope != null)
    }

    @Test fun `未命中 单P无合集`() {
        val single = """{"code":0,"data":{"bvid":"BV1x","title":"单P",
            "pages":[{"page":1,"part":"a","duration":5,"cid":1}]}}""".replace("\n", "")
        val sc = parseVideoScope(single).scope!!
        assertFalse(sc.hit())
    }

    @Test fun `scopeChildren 清单标题与子项含预查键`() {
        val sc = parseVideoScope(fixture).scope!!
        val (pageTitle, pageKids) = scopeChildren(sc, "page")
        assertEquals("合集样本", pageTitle); assertEquals(2, pageKids.size)
        assertEquals("合集样本 p01 A", pageKids[0].precheckKey)   // 复合标题键
        val (seasonTitle, seasonKids) = scopeChildren(sc, "season")
        assertEquals("一歌手一合集", seasonTitle); assertEquals(2, seasonKids.size)
        assertEquals("https://www.bilibili.com/video/BVep1", seasonKids[0].url)
        assertEquals("集标题A", seasonKids[0].precheckKey)        // 裸集标题键（去后缀，ep1 本身 2 页）
        assertEquals("集标题A (多P，仅下 P1)", seasonKids[0].title) // 展示 title 仍带后缀
    }

    @Test fun `scopeFlowNext 降级决策纯函数`() {
        assertEquals(ScopeFlowAction.SINGLE_PLAIN, scopeFlowNext(null))
        assertEquals(ScopeFlowAction.SINGLE_TOAST,
            scopeFlowNext(VideoScopeProbeResult(null, ProbeReason.DEGRADED)))
        assertEquals(ScopeFlowAction.SINGLE_PLAIN,
            scopeFlowNext(VideoScopeProbeResult(null, ProbeReason.GONE)))
        val hit = parseVideoScope(fixture).scope!!
        assertEquals(ScopeFlowAction.SHOW_CHOICE, scopeFlowNext(VideoScopeProbeResult(hit, null)))
        val miss = parseVideoScope("""{"code":0,"data":{"bvid":"BV1x","title":"单P",
            "pages":[{"page":1,"part":"a","duration":5,"cid":1}]}}""".replace("\n", "")).scope!!
        assertEquals(ScopeFlowAction.SINGLE_PLAIN, scopeFlowNext(VideoScopeProbeResult(miss, null)))
    }

    @Test fun `复合标题预查键 三坑钉死`() {
        // p{:02d} 两位；part 空 → 尾随空格逐字保留（PredictMediaName.base 不 strip）
        assertEquals("合集样本 p03 ", compositePrecheckKey("合集样本", 3, ""))
        assertEquals("合集样本 p03 无人之岛", compositePrecheckKey("合集样本", 3, "无人之岛"))
        // P≥100 三位自然增长
        assertEquals("T p100 x", compositePrecheckKey("T", 100, "x"))
        // sanitize：'/'→⧸
        assertEquals("A⧸B p01 ", compositePrecheckKey("A/B", 1, ""))
    }

    @Test fun `季范围预查键 裸集标题不带后缀`() {
        assertEquals("集标题A", seasonPrecheckKey("集标题A"))
        assertEquals("集标题A", seasonPrecheckKey("集标题A (多P，仅下 P1)".removeSuffix(" (多P，仅下 P1)")))
    }

    @Test fun `季范围多P集 预查命中即灰显（复合落盘名以裸集标题为前缀）`() {
        val stored = "集标题A p01 第一话.mp4"     // 引擎复合落盘名（episode 本身多P）
        assertTrue(stored.removeSuffix(".mp4").startsWith(seasonPrecheckKey("集标题A")))
    }
}
