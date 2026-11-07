package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** ShelfEntry 派生纯函数（Uri 无关投影；Bound 派生字段经 pages 键验证）。 */
class ShelfEntryTest {

    @Test fun `pagesSummary 实况按文件名识别且计入N图`() {
        val names = listOf("猫片-1.MP.jpg", "猫片-2.MP.jpg")
        val kinds = names.map { ShelfStore.kindOf(it) }
        assertEquals("2 页 · 含实况 · 2 图", pagesSummary(names, kinds))
    }

    @Test fun `pagesSummary 视频+音频+封面混合`() {
        val names = listOf("a.mp4", "a.mp3", "a-cover.jpg")
        val kinds = names.map { ShelfStore.kindOf(it) }
        assertEquals("3 页 · 含视频 · 含音频 · 1 图", pagesSummary(names, kinds))
    }

    @Test fun `pagesSummary 纯视频无标签`() {
        assertEquals("1 页", pagesSummary(listOf("a.mp4"), listOf(ShelfKind.VIDEO)))
    }

    @Test fun `pagesSummary 实况与视频并存 标签齐全`() {
        val names = listOf("a.mp4", "a-1.MP.jpg")
        assertEquals("2 页 · 含实况 · 含视频 · 1 图", pagesSummary(names, names.map { ShelfStore.kindOf(it) }))
    }
}
