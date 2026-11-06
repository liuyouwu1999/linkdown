package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.ProbeInfo
import top.linkdown.android.engine.internal.ChildRef

/**
 * 勾选弹窗批量判型纯函数——枚举结果 → 下一步（单视频回退/子项回退/二段确认）
 * 与混合型 watch&list 逃逸判定（设计）。
 */
class OrderDialogLogicTest {

    private fun info(vararg urls: String, entriesTotal: Int = urls.size) = BatchInfo(
        title = "歌单",
        children = urls.map { ChildRef(it, "t-$it") },
        entriesTotal = entriesTotal,
    )

    @Test fun `判型决策 空子项回退单视频原 URL`() {
        assertEquals(
            BatchNextStep.Single("https://example.com/watch?v=x"),
            nextStepAfterEnumerate("https://example.com/watch?v=x", info()),
        )
    }

    @Test fun `判型决策 entries==1 回退单视频路径（子项 URL）`() {
        assertEquals(
            BatchNextStep.Single("https://example.com/v/only"),
            nextStepAfterEnumerate("https://example.com/playlist/1", info("https://example.com/v/only")),
        )
    }

    @Test fun `判型决策 多子项进二段确认`() {
        val step = nextStepAfterEnumerate("https://example.com/playlist/1", info("u1", "u2", "u3"))
        assertTrue(step is BatchNextStep.Confirm)
        assertEquals(3, (step as BatchNextStep.Confirm).info.children.size)
    }

    @Test fun `混合型 watch list 判定 逃逸仅对不过前置门者`() {
        assertTrue("watch&list 混合型 → 逃逸可见", isMixedListUrl("https://www.youtube.com/watch?v=X&list=Y"))
        assertTrue(isMixedListUrl("https://www.youtube.com/watch?list=Y&v=X"))
        assertFalse("普通单视频无 list 参数", isMixedListUrl("https://www.youtube.com/watch?v=X"))
        assertFalse("纯列表过前置门（走批量，非混合）", isMixedListUrl("https://www.youtube.com/playlist?list=Y"))
        assertFalse("B站纯列表同理", isMixedListUrl("https://space.bilibili.com/123/video"))
        assertFalse("无参数任意 URL", isMixedListUrl("https://example.com/video/1"))
    }

    // ---- 多链接逐条判型（resolveOrderLine）：直连/合集/回退/失败行 ----

    private val spaceUrl = "https://space.bilibili.com/123/video"   // 过批量前置门

    @Test fun `多链接判型 直连单曲不枚举`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine("https://example.com/video/1", { error("直连不应枚举") })
        line as OrderLine.Ready
        assertEquals("https://example.com/video/1", line.enqueueUrl)
        assertEquals("单曲", line.label)
        assertNull(line.group)
    }

    @Test fun `多链接判型 合集整组`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(spaceUrl, { info("u1", "u2", "u3") })
        line as OrderLine.Ready
        assertEquals("歌单 · 3 条", line.label)
        assertEquals(spaceUrl, line.url)
        assertEquals(3, line.group?.children?.size)
    }

    @Test fun `多链接判型 合集单子项回退单曲带预取名`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(spaceUrl, { info("https://example.com/v/only") })
        line as OrderLine.Ready
        assertEquals("https://example.com/v/only", line.enqueueUrl)
        assertEquals("歌单", line.probeTitle)
        assertNull(line.group)
    }

    @Test fun `多链接判型 空子项回退单曲原链接`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(spaceUrl, { info() })
        line as OrderLine.Ready
        assertEquals(spaceUrl, line.enqueueUrl)
        assertEquals("歌单", line.probeTitle)
        assertNull(line.group)
    }

    @Test fun `多链接判型 枚举失败成 Failed 行`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(spaceUrl, { throw top.linkdown.android.engine.EngineException("列表为空或不可访问") })
        assertTrue(line is OrderLine.Failed)
        assertEquals("列表为空或不可访问", (line as OrderLine.Failed).message)
    }

    // ---- 短链分支（expand 注入直测，默认真网络展开不可测）----

    private val b23 = "https://b23.tv/abc123"   // isShortlinkUrl 命中的短链域

    @Test fun `多链接判型 短链展开成功按展开后判型`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(b23, { info("u1", "u2") }, { spaceUrl })
        line as OrderLine.Ready
        assertEquals("展开后的 space URL 过批量门 → 合集整组", "歌单 · 2 条", line.label)
        assertEquals(b23, line.url)
    }

    @Test fun `多链接判型 短链展开网络失败回退原链接`() = kotlinx.coroutines.runBlocking {
        val line = resolveOrderLine(b23, { error("回退后不过批量门不应枚举") },
            { throw java.net.SocketTimeoutException("net") })
        line as OrderLine.Ready
        assertEquals("回退原链接：b23.tv 不过批量门 → 直连单曲", b23, line.enqueueUrl)
        assertNull(line.group)
    }

    @Test fun `多链接判型 短链展开取消穿透不吞`() = kotlinx.coroutines.runBlocking {
        try {
            resolveOrderLine(b23, { error("") },
                { throw kotlinx.coroutines.CancellationException("弹窗关闭") })
            assertTrue("应重抛 CancellationException", false)
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
    }

    @Test fun `多链接合集标签 长标题截断与超限截取人话`() = kotlinx.coroutines.runBlocking {
        val many = List(201) { "u$it" }
        val line = resolveOrderLine(spaceUrl, {
            BatchInfo("一个很长的歌单标题超出一行宽度了啊", many.map { ChildRef(it, it) }, many.size)
        })
        line as OrderLine.Ready
        assertTrue(line.label.contains("截取前"))
        assertTrue("长标题截断显示", line.label.startsWith("一个很长的歌单标题超出一行宽度了啊".take(14)))
        assertTrue(line.label.contains("…"))
    }

    // ------------------------------------------------------------------
    // 开窗预判型（ 有什么下什么 ）：图文能力引擎路由 → 初始阶段 → probe 结果折图片张数
    // ------------------------------------------------------------------

    private fun probeInfo(hasImages: Boolean, playUrl: String?) = ProbeInfo(
        platform = "抖音", mediaId = "m", sourceUrl = "", title = "t", author = "",
        coverUrl = null, durationSec = null, isVip = false, playUrl = playUrl, hasImages = hasImages,
    )

    @Test fun `图文能力路由 抖音短链 iesdouyin xhslink tme 命中`() {
        assertTrue(isImageCapableUrl("https://v.douyin.com/wwKBWr_IG60/"))
        assertTrue(isImageCapableUrl("https://www.iesdouyin.com/share/video/7300000000000000000"))
        assertTrue(isImageCapableUrl("https://xhslink.com/abcDE1"))
        assertTrue(isImageCapableUrl("https://t.me/s/chan/123"))
    }

    @Test fun `非图文能力路由 yt-dlp 汽水特例 乱串 不预判`() {
        assertFalse(isImageCapableUrl("https://www.youtube.com/watch?v=x"))
        assertFalse(isImageCapableUrl("https://music.douyin.com/qishui/song/1")) // 汽水先于 douyin 通配
        assertFalse(isImageCapableUrl("https://qishui.douyin.com/song/1"))
        assertFalse(isImageCapableUrl("https://weibo.com/u/1"))
        assertFalse(isImageCapableUrl("Not a link at all"))
    }

    @Test fun `开窗初始阶段 抖音单链进Sniffing 其余直接通用勾选`() {
        assertEquals("Sniffing", initialOrderStage("https://v.douyin.com/wwKBWr_IG60/")::class.simpleName)
        val generic = initialOrderStage("https://www.youtube.com/watch?v=x")
        assertEquals("Modes", generic::class.simpleName)
        assertTrue("通用三行=imageCount null", generic.toString().contains("null"))
        val multi = initialOrderStage(null) // 多链接流不预判（逐条判型后引擎侧交付已自适应）
        assertEquals("Modes", multi::class.simpleName)
        assertTrue(multi.toString().contains("null"))
    }

    @Test fun `probe结果折图片张数 图文取并链计数 视频异常回null`() {
        assertEquals(3, imageCountOf(probeInfo(true, "http://i/1;http://i/2;http://i/3")))
        assertEquals(2, imageCountOf(probeInfo(true, "http://i/1;;http://i/2"))) // 空段不计
        assertNull("hasImages=false → null（通用三行）", imageCountOf(probeInfo(false, "http://v/1.mp4")))
        assertNull("hasImages 但并链空 → null", imageCountOf(probeInfo(true, "")))
    }
}
