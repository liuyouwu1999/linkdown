package top.linkdown.android.eula

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启协议弹窗文案断言（开源合规最小集）：用户要求「完全免费 + 免责」必须在协议里；
 * 关键要素=免费/禁止商用倒卖/仅供学习/责任自负/无担保/与平台无关联（参考 DownKyi/Seal 惯例）。
 */
class EulaPromptTest {

    private val text get() = EulaPrompt.TERMS.joinToString("\n")

    @Test fun `协议含完全免费声明`() {
        assertTrue(text.contains("完全免费"))
    }

    @Test fun `协议含禁止商用与倒卖`() {
        assertTrue(text.contains("商业"))
        assertTrue(text.contains("倒卖"))
    }

    @Test fun `协议含仅供学习与责任自负`() {
        assertTrue(text.contains("学习"))
        assertTrue(text.contains("自行承担"))
    }

    @Test fun `协议含无担保免责`() {
        assertTrue(text.contains("担保"))
        assertTrue(text.contains("不承担责任") || text.contains("免责"))
    }

    @Test fun `协议含与平台无关联声明`() {
        assertTrue(text.contains("平台"))
    }
}
