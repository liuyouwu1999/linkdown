package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 货架排序核心直测（shelfSortedIndices 只吃名称/类别键，绕开 JVM 测试无法构造 android Uri）：
 * 按时间=原序（MediaStore 日期倒序）、按名称=小写字母序稳定、
 * 按类别=视频→音频→图片→文件分组、组内按名称。
 */
class ShelfSortTest {

 // (下标) 0:肉.mp4视频 1:apple.png图片 2:Banana.mp3音频 3:cherry.pdf文件 4:Apple.mp4视频
    private val names = listOf("肉.mp4", "apple.png", "Banana.mp3", "cherry.pdf", "Apple.mp4")
    private val kinds = listOf(
        ShelfKind.VIDEO, ShelfKind.IMAGE, ShelfKind.AUDIO, ShelfKind.OTHER, ShelfKind.VIDEO,
    )

    @Test
    fun time_keepsStoreOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4), shelfSortedIndices(names, kinds, ShelfSort.TIME))
    }

    @Test
    fun name_sortsCaseInsensitive() {
 // 小写后 apple.mp4("apple.mp4") < apple.png("apple.png")（扩展名 m<p）
        assertEquals(listOf(4, 1, 2, 3, 0), shelfSortedIndices(names, kinds, ShelfSort.NAME))
    }

    @Test
    fun kind_groupsThenName() {
 // 视频:apple(4)<肉(0)；音频:Banana(2)；图片:apple(1)；文件:cherry(3)
        assertEquals(listOf(4, 0, 2, 1, 3), shelfSortedIndices(names, kinds, ShelfSort.KIND))
    }

    @Test
    fun next_cyclesInDeclaredOrder() {
        assertEquals(ShelfSort.NAME, ShelfSort.TIME.next())
        assertEquals(ShelfSort.KIND, ShelfSort.NAME.next())
        assertEquals(ShelfSort.TIME, ShelfSort.KIND.next())
    }

    @Test
    fun empty_staysEmpty() {
        assertEquals(emptyList<Int>(), shelfSortedIndices(emptyList(), emptyList(), ShelfSort.KIND))
    }
}
