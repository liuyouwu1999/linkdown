package top.linkdown.android.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.engine.EngineException
import top.linkdown.android.engine.internal.ChildRef
import top.linkdown.android.queue.FakeEngine
import top.linkdown.android.queue.FakeEnv
import java.io.File

/**
 * 入口批量枚举缝（弹窗二段确认的 pipeline.enumerate）——引擎/cookie 缝透传、
 * 异常原样上抛（弹窗人话，不入队任何条目）。
 */
class PipelineEnumerateTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "pe-${System.nanoTime()}").apply { mkdirs() }

    @Test fun `enumerate 经环境引擎与 cookie 缝透传`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val engine = FakeEngine().apply {
                enumerateResult = BatchInfo(
                    title = "我的歌单",
                    children = listOf(ChildRef("https://example.com/v/1", "t1"), ChildRef("https://example.com/v/2", "t2")),
                    entriesTotal = 2,
                )
            }
            env.register("https://example.com/playlist/1", engine)
            val pipeline = SingleDownloadPipeline(env)
            val info = pipeline.enumerate("https://example.com/playlist/1", sessdata = "abc123")
            assertEquals("我的歌单", info.title)
            assertEquals(2, info.children.size)
            assertEquals(listOf("https://example.com/playlist/1"), engine.enumerateCalls)
            assertEquals("sessdata 快照 → cookie 文件透传", listOf("SESSDATA=abc123"), engine.cookiesSeen)
        } finally { root.deleteRecursively() }
    }

    @Test fun `enumerate 无快照 cookie 为 null 引擎异常原样上抛`() = runTest {
        val root = tempRoot()
        try {
            val env = FakeEnv(root)
            val engine = FakeEngine().apply {
                enumerateError = EngineException("需要登录：请在主页「登录账号」里登录后重试")
            }
            env.register("https://example.com/playlist/2", engine)
            val pipeline = SingleDownloadPipeline(env)
            var thrown: Throwable? = null
            try {
 // 快照透传 env.cookieFile（空白折叠在 AndroidPipelineEnv 生产实现内，JVM 缝外）
                pipeline.enumerate("https://example.com/playlist/2", sessdata = null)
            } catch (t: Throwable) {
                thrown = t
            }
            assertTrue("EngineException 人话原样上抛（弹窗人话不入队任何条目）", thrown is EngineException)
            assertEquals("需要登录：请在主页「登录账号」里登录后重试", thrown?.message)
            assertEquals(listOf<String?>(null), engine.cookiesSeen)
        } finally { root.deleteRecursively() }
    }
}
