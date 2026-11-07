package top.linkdown.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.pipeline.FileRef
import top.linkdown.android.pipeline.LedgerEntry
import top.linkdown.android.pipeline.LedgerMerge
import java.io.File

/** 台账 DataStore 壳（JVM 真 DataStore + 临时文件；每用例独立目录防同文件双实例）。 */
class ShelfLedgerTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "ledger-${System.nanoTime()}").apply { mkdirs() }

    private class Box : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var ledger: ShelfLedger
        lateinit var dir: File
        override fun close() { scope.cancel(); dir.deleteRecursively() }
    }

    private fun newLedger(): Box = Box().apply {
        dir = tempRoot()
        ledger = ShelfLedger.create(File(dir, "shelf_ledger.preferences_pb"), scope)
    }

    private val m1 = LedgerMerge("抖音:111", "抖音", "猫片", listOf(FileRef("Download/链接下载/抖音/", "猫片-1.jpg")))

    @Test fun `merge 后 load 往返`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            assertEquals(listOf(LedgerEntry(m1.workKey, m1.platform, m1.title, m1.files, b.ledger.load()[0].updatedAt)), b.ledger.load())
        }
        Unit
    }

    @Test fun `merge 幂等去重 同文件不重复`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.merge(m1)
            assertEquals(1, b.ledger.load().size)
            assertEquals(1, b.ledger.load()[0].files.size)
        }
        Unit
    }

    @Test fun `removeKeys 删指定键`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.merge(LedgerMerge("抖音:222", "抖音", "狗片", listOf(FileRef("p/", "g.mp4"))))
            b.ledger.removeKeys(setOf("抖音:111"))
            assertEquals(listOf("抖音:222"), b.ledger.load().map { it.workKey })
        }
        Unit
    }

    @Test fun `removeKeys 空集不写 快速返回`() = runBlocking {
        newLedger().use { b ->
            b.ledger.merge(m1)
            b.ledger.removeKeys(emptySet())
            assertEquals(1, b.ledger.load().size)
        }
        Unit
    }

    @Test fun `损坏文件 回空表不崩`() {
        // 先写坏文件再建唯一实例：DataStore 对同文件第二活跃实例会抛 IllegalStateException
        val b = Box()
        try {
            b.dir = tempRoot()
            val f = File(b.dir, "shelf_ledger.preferences_pb")
            f.writeBytes(byteArrayOf(1, 2, 3, 9, 9))
            b.ledger = ShelfLedger.create(f, b.scope)
            runBlocking { assertTrue(b.ledger.load().isEmpty()) }
        } finally { b.close() }
    }
}
