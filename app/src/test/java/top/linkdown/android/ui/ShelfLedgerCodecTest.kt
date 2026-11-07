package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge

/** 台账编解码/合并纯函数（JVM 直测；Uri 无关）。 */
class ShelfLedgerCodecTest {

    private val e1 = LedgerEntry("抖音:111", "抖音", "猫片", listOf(FileRef("Download/链接下载/抖音/", "猫片-1.jpg")), 100L)

    @Test fun `encode decode 往返保真`() {
        val restored = ShelfLedgerCodec.decode(ShelfLedgerCodec.encode(listOf(e1)))
        assertEquals(listOf(e1), restored)
    }

    @Test fun `decode 损坏或空输入回空表`() {
        assertTrue(ShelfLedgerCodec.decode(null).isEmpty())
        assertTrue(ShelfLedgerCodec.decode("not json{").isEmpty())
        assertTrue(ShelfLedgerCodec.decode("[]").isEmpty())
    }

    @Test fun `mergeEntries 新键追加`() {
        val input = LedgerMerge("抖音:222", "抖音", "狗片", listOf(FileRef("Download/链接下载/抖音/", "狗片.mp4")))
        val out = ShelfLedgerCodec.mergeEntries(listOf(e1), input, now = 200L)
        assertEquals(2, out.size)
        assertEquals("狗片", out[1].title)
        assertEquals(200L, out[1].updatedAt)
    }

    @Test fun `mergeEntries 既有键去重追加并覆盖标题`() {
        val input = LedgerMerge("抖音:111", "抖音", "猫片", listOf(
            FileRef("Download/链接下载/抖音/", "猫片-1.jpg"),   // 已有 → 不重复
            FileRef("Download/链接下载/抖音/", "猫片.mp4"),     // 新 → 追加
        ))
        val out = ShelfLedgerCodec.mergeEntries(listOf(e1), input, now = 300L)
        assertEquals(1, out.size)
        assertEquals(listOf("猫片-1.jpg", "猫片.mp4"), out[0].files.map { it.name })
        assertEquals(300L, out[0].updatedAt)
    }

    @Test fun `mergeEntries 文件名自然序 数值感知`() {
        val input = LedgerMerge("抖音:1", "抖音", "t", listOf(
            FileRef("p/", "标题-10.jpg"), FileRef("p/", "标题-2.jpg")))
        val out = ShelfLedgerCodec.mergeEntries(emptyList(), input, now = 0L)
        assertEquals(listOf("标题-2.jpg", "标题-10.jpg"), out[0].files.map { it.name })
    }

    @Test fun `compareNatural 数字段按数值比较`() {
        assertTrue(compareNatural("a-2.jpg", "a-10.jpg") < 0)
        assertTrue(compareNatural("a-002.jpg", "a-2.jpg") == 0)
        assertTrue(compareNatural("b.jpg", "a.jpg") > 0)
    }
}
