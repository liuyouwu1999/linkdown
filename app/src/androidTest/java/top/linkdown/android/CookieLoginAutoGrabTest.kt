package top.linkdown.android

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.linkdown.android.cookies.CookieStore

/**
 * fix round 3：登录页自动检测+自动关闭——目标 cookie 出现时自动抓取保存并 finish（回选择页）。
 * CookieManager 是进程级单例，用例间会泄漏：每个用例前后都清理 Cookie 与 cookie 文件。
 */
@RunWith(AndroidJUnit4::class)
class CookieLoginAutoGrabTest {
    private fun neteaseIntent(): Intent =
        Intent(ApplicationProvider.getApplicationContext<Context>(), CookieLoginActivity::class.java)
            .putExtra(CookieLoginActivity.EXTRA_PLATFORM, CookieLoginActivity.PLATFORM_NETEASE)

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun cleanup() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        File(context().filesDir, "cookies/netease.txt").delete()
        File(context().filesDir, "cookies/bilibili.txt").delete()
    }

    @Test fun auto_grab_saves_and_finishes_when_marker_cookie_present() {
        try {
            cleanup()
            ActivityScenario.launch<CookieLoginActivity>(neteaseIntent()).use { scenario ->
 // 模拟「登录已完成」：CookieManager 里出现 MUSIC_U（同进程 CookieManager）
                CookieManager.getInstance().setCookie("https://music.163.com", "MUSIC_U=autotest; Path=/; Domain=.music.163.com")
                CookieManager.getInstance().flush()
                var finishing = false
                scenario.onActivity { act ->
                    act.autoGrabIfCookieReady(loginCfgFor(CookieLoginActivity.PLATFORM_NETEASE))
                    finishing = act.isFinishing
                }
                val saved = CookieStore.existing("netease", context().filesDir)
                assertTrue("检测到 marker 应自动 finish 回选择页", finishing)
                assertNotNull("应自动写入 cookies/netease.txt", saved)
            }
        } finally {
            cleanup()   // 断言失败也要清，不留伪造 cookie 污染真机下载与后续用例
        }
    }

    @Test fun no_marker_keeps_page_open_for_manual_grab() {
        try {
            cleanup()
            ActivityScenario.launch<CookieLoginActivity>(neteaseIntent()).use { scenario ->
                var finishing = true
                scenario.onActivity { act ->
                    act.autoGrabIfCookieReady(loginCfgFor(CookieLoginActivity.PLATFORM_NETEASE))
                    finishing = act.isFinishing
                }
                assertFalse("无 marker 不应自动关闭，保留手动抓取兜底", finishing)
            }
        } finally {
            cleanup()
        }
    }
}
