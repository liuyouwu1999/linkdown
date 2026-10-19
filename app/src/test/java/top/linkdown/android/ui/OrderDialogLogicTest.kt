package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.BatchInfo
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
}
