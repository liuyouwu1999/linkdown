package top.linkdown.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** AppUpdater 纯函数单测：版本语义比较 / 镜像前缀归一 / 直链构造 / latest.json 解析。 */
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

 // ---- 镜像前缀与直链构造 ----

    @Test fun `镜像前缀归一 空为直连 去尾斜杠补一`() {
        assertEquals("", AppUpdater.mirrorPrefix(""))
        assertEquals("", AppUpdater.mirrorPrefix("  "))
        assertEquals("https://ghfast.top/", AppUpdater.mirrorPrefix("https://ghfast.top"))
        assertEquals("https://ghfast.top/", AppUpdater.mirrorPrefix("https://ghfast.top/"))
        assertEquals("http://127.0.0.1:8000/", AppUpdater.mirrorPrefix("http://127.0.0.1:8000/"))
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
}
