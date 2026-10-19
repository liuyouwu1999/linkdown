package top.linkdown.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test

/** fix round 2：登录选择页行卡断言（像素风棚条/招牌/双平台行/底部说明） */
class LoginHubTest {
    @get:Rule val rule = createAndroidComposeRule<LoginHubActivity>()

    @get:Rule val cleanCookies = object : org.junit.rules.ExternalResource() {
        override fun before() {
 // 状态断言需要确定的起点：清掉其他用例可能残留的 cookie 文件（E2E 不落 cookie，不受影响）
            val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            java.io.File(ctx.filesDir, "cookies/bilibili.txt").delete()
            java.io.File(ctx.filesDir, "cookies/netease.txt").delete()
        }
        override fun after() { }
    }

    @Test fun hub_shows_both_platform_rows_with_login_buttons() {
        rule.onNodeWithText("登录账号").assertExists()
        rule.onNodeWithText("B站").assertExists()
        rule.onNodeWithText("网易云").assertExists()
        rule.onAllNodesWithText("登录 ▸").assertCountEquals(2)
        rule.onNodeWithText("更多平台的登录支持陆续添加").assertExists()
 // 起点无 cookie 文件 → 两行状态均为「无」
        rule.onAllNodesWithText("已存 cookie：无").assertCountEquals(2)
    }
}
