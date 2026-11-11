package top.linkdown.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** fix round 2：登录选择页行卡断言（像素风棚条/顶栏/三平台行/底部说明；Task4 补返回钮热区断言） */
class LoginHubTest {
    @get:Rule val rule = createAndroidComposeRule<LoginHubActivity>()

    @get:Rule val cleanCookies = object : org.junit.rules.ExternalResource() {
        override fun before() {
 // 状态断言需要确定的起点：清掉其他用例可能残留的 cookie 文件（E2E 不落 cookie，不受影响）
            val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            java.io.File(ctx.filesDir, "cookies/bilibili.txt").delete()
            java.io.File(ctx.filesDir, "cookies/netease.txt").delete()
            java.io.File(ctx.filesDir, "cookies/douyin.txt").delete()
        }
        override fun after() { }
    }

    @Test fun hub_shows_both_platform_rows_with_login_buttons() {
        rule.onNodeWithText("登录账号").assertExists()
        rule.onNodeWithText("B站").assertExists()
        rule.onNodeWithText("网易云").assertExists()
        rule.onNodeWithText("抖音").assertExists()
        rule.onAllNodesWithText("登录 ▸").assertCountEquals(3)
        rule.onNodeWithText("更多平台的登录支持陆续添加").assertExists()
 // 起点无 cookie 文件 → 三行状态均为「无」
        rule.onAllNodesWithText("已存 cookie：无").assertCountEquals(3)
    }

    @Test fun back_button_touch_target_at_least_48dp() {
 // spec §3：应用内返回热区 ≥48×48dp（链首 pixelMinTouchTarget 后槽位即达标）。
 // SemanticsNode.size 上报的是链内自然内容边界（扩后槽位在 semantics 之外，实测仅 31dp），
 // 槽位真值取 SemanticsNode.layoutInfo（所属 LayoutNode 外层实测尺寸=扩后槽位，内容居中）。
 // 注意：Compose 命中测试对小于 48dp 的可点击件有自动触控扩展（实点验证不具区分力），故以槽位实测为准。
 // （Compose UI 1.10 起 fetchSemanticsNode 为成员函数，勿再 import androidx.compose.ui.test.fetchSemanticsNode）
        val node = rule.onNodeWithText("← 返回").fetchSemanticsNode()
        val density = rule.activity.resources.displayMetrics.density
        rule.runOnIdle {
            assertTrue("返回钮槽位宽应 ≥48dp，实测 ${node.layoutInfo.width / density}dp", node.layoutInfo.width / density >= 48f)
            assertTrue("返回钮槽位高应 ≥48dp，实测 ${node.layoutInfo.height / density}dp", node.layoutInfo.height / density >= 48f)
        }
    }
}
