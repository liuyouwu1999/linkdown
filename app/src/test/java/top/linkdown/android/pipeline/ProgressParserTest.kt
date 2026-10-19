package top.linkdown.android.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressParserTest {
    @Test fun `extracts percent`() {
        assertEquals(43.2f, ProgressParser.percent("[download]  43.2% of  209.56MiB at 10MiB/s")!!, 0.01f)
        assertEquals(100.0f, ProgressParser.percent("[download] 100% of 1MiB")!!, 0.01f)
    }
    @Test fun `ignores non progress lines`() {
        assertNull(ProgressParser.percent("[BiliBili] Extracting URL: x"))
        assertNull(ProgressParser.percent(""))
    }
}
