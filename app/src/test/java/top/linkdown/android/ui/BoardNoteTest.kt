package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 软木板便签阅读器纯函数（JVM 直测，Uri 无关字段投影）。 */
class BoardNoteTest {

    @Test fun `同stem封面页优先`() {
        val candidates = listOf("标题.mp4" to ShelfKind.VIDEO, "标题-1.jpg" to ShelfKind.IMAGE, "标题.jpg" to ShelfKind.IMAGE)
        assertEquals("标题.jpg", coverSheetNameFor("标题.mp4", ShelfKind.VIDEO, candidates))
    }

    @Test fun `无同stem回退书内首个图片页`() {
        val candidates = listOf("标题.mp3" to ShelfKind.AUDIO, "标题-1.jpg" to ShelfKind.IMAGE, "标题-2.jpg" to ShelfKind.IMAGE)
        assertEquals("标题-1.jpg", coverSheetNameFor("标题.mp3", ShelfKind.AUDIO, candidates))
    }

    @Test fun `图片页返回自身`() {
        val candidates = listOf("标题-3.MP.jpg" to ShelfKind.IMAGE)
        assertEquals("标题-3.MP.jpg", coverSheetNameFor("标题-3.MP.jpg", ShelfKind.IMAGE, candidates))
    }

    @Test fun `实况合成图与图片页互相不串`() {
        // 图文书全图片：任一页返回自身
        val candidates = listOf("标题-1.MP.jpg" to ShelfKind.IMAGE, "标题-2.MP.jpg" to ShelfKind.IMAGE)
        assertEquals("标题-2.MP.jpg", coverSheetNameFor("标题-2.MP.jpg", ShelfKind.IMAGE, candidates))
    }

    @Test fun `书内无图片页返回null`() {
        val candidates = listOf("标题.mp4" to ShelfKind.VIDEO)
        assertNull(coverSheetNameFor("标题.mp4", ShelfKind.VIDEO, candidates))
    }

    @Test fun `图钉色六色循环`() {
        assertEquals(0, pinColorIndex(0))
        assertEquals(5, pinColorIndex(5))
        assertEquals(0, pinColorIndex(6))
        assertEquals(3, pinColorIndex(9))
    }

    @Test fun `纸底日期为yyyy-MM-dd形状`() {
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(boardDate(1_780_000_000)))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(boardDate(0)))
    }

    @Test fun `纸张微旋转在正负2度内且按纸确定`() {
        for (i in 0..40) {
            val a = paperRotationDegrees(i)
            assertTrue(a in -2f..2f)
            assertEquals(a, paperRotationDegrees(i))
        }
        assertTrue(paperRotationDegrees(3) != paperRotationDegrees(9))
    }
}
