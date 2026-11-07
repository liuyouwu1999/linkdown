package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.ui.ShelfStore.ShelfKey

/** 合书对账纯函数（Uri 无关投影，JVM 直测）。 */
class GroupShelfTest {

    private fun key(id: String, name: String, relPath: String = "Download/链接下载/抖音/", date: Long = 10L) =
        ShelfKey(id = id, relPath = relPath, name = name,
            kind = ShelfStore.kindOf(name), dateAdded = date)

    private val ledger = listOf(
        LedgerEntry("抖音:111", "抖音", "猫片", listOf(
            FileRef("Download/链接下载/抖音/", "猫片-1.jpg"),
            FileRef("Download/链接下载/抖音/", "猫片-2.jpg"),
        ), 0L),
    )

    @Test fun `成组 页序等于台账序`() {
        val keys = listOf(key("u2", "猫片-2.jpg", date = 9), key("u1", "猫片-1.jpg", date = 11))
        val r = ShelfStore.groupShelf(keys, ledger)
        val bound = r.groups.filterIsInstance<ShelfStore.BoundGroup>().single()
        assertEquals(listOf("u1", "u2"), bound.keys.map { it.id }) // 台账序，非输入序
        assertEquals("猫片", bound.title)
        assertEquals("抖音:111", bound.workKey)
        assertTrue(r.staleKeys.isEmpty())
    }

    @Test fun `剩1页 条目保留 文件按Single且不重复渲染`() {
        val keys = listOf(key("u1", "猫片-1.jpg"))
        val r = ShelfStore.groupShelf(keys, ledger)
        assertEquals(1, r.groups.size)
        assertTrue(r.groups[0] is ShelfStore.SingleGroup)
        assertTrue(r.staleKeys.isEmpty()) // 条目保留，不清理
    }

    @Test fun `全删 stale 收录整组键`() {
        val r = ShelfStore.groupShelf(emptyList(), ledger)
        assertTrue(r.groups.isEmpty())
        assertEquals(setOf("抖音:111"), r.staleKeys)
    }

    @Test fun `未认领文件成Single 旧文件行为不变`() {
        val keys = listOf(key("u9", "旧图.jpg"), key("u1", "猫片-1.jpg", date = 11), key("u2", "猫片-2.jpg"))
        val r = ShelfStore.groupShelf(keys, ledger)
        val bound = r.groups.filterIsInstance<ShelfStore.BoundGroup>().single()
        assertEquals(listOf("u1", "u2"), bound.keys.map { it.id })
        assertEquals("u9", (r.groups.filterIsInstance<ShelfStore.SingleGroup>().single()).key.id)
    }

    @Test fun `同title不同workKey不误并 匹配键含relPath`() {
        val keys = listOf(
            key("a1", "猫片-1.jpg", relPath = "Download/链接下载/抖音/"),
            key("a2", "猫片-1.jpg", relPath = "Download/链接下载/小红书/"),
            key("a3", "猫片-2.jpg", relPath = "Download/链接下载/抖音/"),
        )
        val r = ShelfStore.groupShelf(keys, ledger) // 台账指向抖音目录：a1+a3 成组
        assertEquals(1, r.groups.filterIsInstance<ShelfStore.BoundGroup>().size)
        assertEquals(1, r.groups.filterIsInstance<ShelfStore.SingleGroup>().size) // 小红书同名文件不认领
    }

    @Test fun `输出序 dateAdded DESC 同值输入序稳定`() {
        val keys = listOf(
            key("old", "旧.jpg", date = 5), key("n1", "猫片-1.jpg", date = 11), key("n2", "猫片-2.jpg", date = 9),
        )
        val r = ShelfStore.groupShelf(keys, ledger)
        assertEquals(listOf("抖音:111", "old"), r.groups.map { it.id }) // Bound(max=11) 在前
    }
}
