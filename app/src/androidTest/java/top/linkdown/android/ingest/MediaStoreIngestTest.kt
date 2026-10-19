package top.linkdown.android.ingest

import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreIngestTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun `ingest_two_phase_and_queryable`() {
        val tmp = File(ctx.cacheDir, "ingest-test-${System.currentTimeMillis()}.bin")
        tmp.writeBytes(ByteArray(64 * 1024) { it.toByte() })
        val seen = mutableListOf<Float>()
        val uri = MediaStoreIngest.ingest(ctx, tmp, "哔哩哔哩") { seen.add(it) }
        try {
            ctx.contentResolver.query(uri, null, null, null, null)!!.use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
                assertEquals("Download/链接下载/哔哩哔哩/", c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)))
            }
 // Review Focus 5：进度严格单调递增且终值 1.0
            assertTrue(seen.size >= 2)
            for (i in 1 until seen.size) assertTrue("progress not monotonic: $seen", seen[i] > seen[i - 1])
            assertEquals(1.0f, seen.last(), 0.0001f)
            assertEquals(64 * 1024, (ctx.contentResolver.openInputStream(uri) ?: error("unreadable")).use { it.readBytes() }.size)
        } finally {
            ctx.contentResolver.delete(uri, null, null)
            tmp.delete()
        }
    }
}
