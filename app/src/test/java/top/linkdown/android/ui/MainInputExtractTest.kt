package top.linkdown.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框支持整段分享文案：extractOrderUrl 提取 / isValidInput 判定（用户真实粘贴形态）。 */
class MainInputExtractTest {

    private val douyinShare = "《电气工程师去老家出差时》 #电气专业就业 #钓鱼竿 #户外钓鱼日常 " +
        "#电气专业 #电气工程专业 https://v.douyin.com/EIm16yopJc4/ 复制此链接，打开【抖音】，直接观看视频！"

    @Test fun `整段分享文案提取出链接`() {
        assertEquals("https://v.douyin.com/EIm16yopJc4/", extractOrderUrl(douyinShare))
    }

    @Test fun `isValidInput 接受整段分享文案 拒绝纯文本`() {
        assertTrue(isValidInput(MainUiState(url = douyinShare)))
        assertFalse(isValidInput(MainUiState(url = "没有链接的纯文本内容")))
        assertFalse(isValidInput(MainUiState(url = "")))
    }

    @Test fun `裸URL与www前缀维持原行为`() {
        assertEquals("https://x.com/a?b=1", extractOrderUrl("https://x.com/a?b=1"))
        assertEquals("https://www.x.com/a", extractOrderUrl("www.x.com/a"))
    }

    @Test fun `无链接返回null`() {
        assertNull(extractOrderUrl("随便一段文字"))
        assertNull(extractOrderUrl(""))
    }
}
