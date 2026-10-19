package top.linkdown.android.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** AppUpdater 单测：版本语义比较 / 源前缀归一 / 直链构造 / latest.json 解析 / 源级联（检查+下载）。 */
class AppUpdaterTest {

 // ---- compareVersions：X.Y.Z 逐段数值；v 前缀/缺段/非数字段容忍 ----

    @Test fun `版本相等与补丁小版本比较`() {
        assertEquals(0, AppUpdater.compareVersions("1.0.0", "1.0.0"))
        assertEquals(-1, AppUpdater.compareVersions("1.0.0", "1.0.1"))
        assertEquals(1, AppUpdater.compareVersions("1.0.1", "1.0.0"))
        assertEquals(-1, AppUpdater.compareVersions("1.0.0", "1.1.0"))
        assertEquals(-1, AppUpdater.compareVersions("1.9.9", "2.0.0"))
    }

    @Test fun `版本逐段数值比较 十位段不按字典序`() {
        assertEquals(1, AppUpdater.compareVersions("1.10.0", "1.9.9"))
        assertEquals(-1, AppUpdater.compareVersions("1.2.0", "1.10.0"))
    }

    @Test fun `版本容忍v前缀缺段与非数字段`() {
        assertEquals(0, AppUpdater.compareVersions("v1.0.0", "1.0.0"))
        assertEquals(0, AppUpdater.compareVersions("V1.0", "1.0.0")) // 缺段按 0
        assertEquals(1, AppUpdater.compareVersions("1.0.1", "1.0.0-beta")) // 非数字段按 0
        assertEquals(0, AppUpdater.compareVersions("abc", "0"))
    }

 // ---- 源前缀与直链构造 ----

    @Test fun `源前缀归一 空为直连 去尾斜杠补一`() {
        assertEquals("", AppUpdater.sourcePrefix(""))
        assertEquals("", AppUpdater.sourcePrefix("  "))
        assertEquals("https://ghfast.top/", AppUpdater.sourcePrefix("https://ghfast.top"))
        assertEquals("https://ghfast.top/", AppUpdater.sourcePrefix("https://ghfast.top/"))
        assertEquals("http://127.0.0.1:8000/", AppUpdater.sourcePrefix("http://127.0.0.1:8000/"))
    }

    @Test fun `直链构造 直连与镜像两形态`() {
        val p = "https://github.com/${AppUpdater.RELEASE_REPO}/releases/latest/download"
        assertEquals("$p/latest.json", AppUpdater.latestJsonUrl(""))
        assertEquals("$p/${AppUpdater.APK_ASSET}", AppUpdater.apkUrl(""))
        assertEquals("https://ghfast.top/$p/latest.json", AppUpdater.latestJsonUrl("https://ghfast.top"))
        assertEquals("https://ghfast.top/$p/${AppUpdater.APK_ASSET}", AppUpdater.apkUrl("https://ghfast.top/"))
    }

 // ---- latest.json 解析：字段缺失/坏 JSON → null（检查侧归 Failed 人话） ----

    @Test fun `latestJson 正常解析与缺字段容错`() {
        val ok = AppUpdater.parseLatestJson("""{"versionName":"1.0.1","notes":"修复若干"}""")
        assertEquals("1.0.1", ok?.versionName)
        assertEquals("修复若干", ok?.notes)

        val noNotes = AppUpdater.parseLatestJson("""{"versionName":"1.1.0"}""")
        assertEquals("1.1.0", noNotes?.versionName)
        assertEquals("", noNotes?.notes)

        assertNull(AppUpdater.parseLatestJson("""{"notes":"缺版本号"}"""))
        assertNull(AppUpdater.parseLatestJson("not json at all"))
        assertNull(AppUpdater.parseLatestJson(""""裸字符串""""))
    }

 // ---- 源级联：检查/下载按序逐个试，任一成功即止（用户零选择） ----

    private fun latestJson(version: String) =
        """{"versionName":"$version","notes":"n"}"""

    @Test fun `检查级联 首源失败切下一源 成功即止`() = runBlocking {
        val server = MockWebServer(); server.start()
        // 首源是死端口（请求到不了本服务器）；本服务器只服务次源
        server.enqueue(MockResponse().setBody(latestJson("1.0.1")))
        val r = AppUpdater.check(
            "1.0.0", listOf("http://127.0.0.1:1", server.url("/").toString().trimEnd('/')),
        )
        server.shutdown()
        assertTrue("首源失败后由次源给出结果 actual=$r", r is AppUpdater.CheckResult.NewRelease)
        assertEquals("1.0.1", (r as AppUpdater.CheckResult.NewRelease).release.versionName)
    }

    @Test fun `检查级联 首源成功不触后续源`() = runBlocking {
        val s1 = MockWebServer(); s1.start()
        val s2 = MockWebServer(); s2.start()
        s1.enqueue(MockResponse().setBody(latestJson("9.9.9")))
        val r = AppUpdater.check(
            "1.0.0", listOf(s1.url("/").toString().trimEnd('/'), s2.url("/").toString().trimEnd('/')),
        )
        assertEquals("首源成功后不得触后续源", 0, s2.requestCount)
        assertTrue(r is AppUpdater.CheckResult.NewRelease)
        s1.shutdown(); s2.shutdown()
    }

    @Test fun `检查级联 全部失败归Failed`() = runBlocking {
        val r = AppUpdater.check("1.0.0", listOf("http://127.0.0.1:1", "http://127.0.0.1:2"))
        assertTrue(r is AppUpdater.CheckResult.Failed)
    }

    @Test fun `下载级联 首源失败切下一源 成功落盘`() = runBlocking {
        val server = MockWebServer(); server.start()
        // 首源是死端口（请求到不了本服务器）；本服务器只服务次源
        server.enqueue(MockResponse().setBody("apk-bytes"))
        val tmp = File.createTempFile("upd", ".apk")
        val srcs = listOf("http://127.0.0.1:1", server.url("/").toString().trimEnd('/'))
        var switched = -1
        val r = AppUpdater.downloadApk(tmp, {}, { false }, { switched = it }, srcs)
        server.shutdown()
        assertTrue(r.isSuccess)
        assertEquals("切源通知带次源下标", 1, switched)
        assertEquals("apk-bytes", tmp.readText())
        assertTrue(tmp.delete())
    }

    @Test fun `下载取消 旗标生效且级联不得触任何源`() = runBlocking {
        val s1 = MockWebServer(); s1.start()
        val s2 = MockWebServer(); s2.start()
        val tmp = File.createTempFile("upd", ".apk")
        val r = AppUpdater.downloadApk(
            tmp, {}, { true }, {},
            listOf(s1.url("/").toString().trimEnd('/'), s2.url("/").toString().trimEnd('/')),
        )
        assertEquals("取消后不得发起任何请求", 0, s1.requestCount + s2.requestCount)
        assertTrue(r.isFailure)
        s1.shutdown(); s2.shutdown()
        assertTrue(tmp.delete())
    }

    @Test fun `检查入参缺省即默认级联源`() {
        // 防呆：默认源必须是「直连、ghfast.top、gh-proxy.com」的顺序
        assertEquals("", AppUpdater.SOURCES[0])
        assertEquals("https://ghfast.top", AppUpdater.SOURCES[1])
        assertEquals("https://gh-proxy.com", AppUpdater.SOURCES[2])
    }
}
