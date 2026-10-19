package top.linkdown.android.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.linkdown.android.engine.Mode

/**
 * DedupCheck 前缀比对直测（MediaStore 查询缝注入，JVM 假查询）。
 * 判据（设计）：扩展名匹配（VIDEO=mp4|mkv|webm；AUDIO=mp3|m4a；COVER=jpg|webp）+
 * 前缀方向=「预测基名（无扩展名）是 DISPLAY_NAME 去扩展名后的前缀」——顺带命中 MediaStore
 * 冲突自动改名 `xxx (1).mp4`。
 */
class DedupCheckTest {

    private fun shelf(vararg names: String): DedupCheck = DedupCheck { names.toList() }

    private val blueprintTitle = "What is this/that? Vol.2: The Sequel"

 /** 参考实现 复现用例：预测名（sanitize 后）落架 → VIDEO 命中；扩展名集合不同不命中。 */
    @Test fun `参考实现 复现 预测名落架 VIDEO 命中 AUDIO COVER 不命中`() {
        val d = shelf("What is this⧸that? Vol.2: The Sequel.mp4")
        assertTrue(d.exists("哔哩哔哩", blueprintTitle, Mode.VIDEO))
        assertFalse("AUDIO 扩展名集合（mp3|m4a）不含 mp4", d.exists("哔哩哔哩", blueprintTitle, Mode.AUDIO))
        assertFalse("COVER 扩展名集合（jpg|webp）不含 mp4", d.exists("哔哩哔哩", blueprintTitle, Mode.COVER))
    }

 /** Telegram/微博/小红书：预测基名=「作者 - 标题」（引擎落盘带作者前缀，缺分支判重恒 miss）。 */
    @Test fun `telegram微博小红书 作者前缀基名命中`() {
        val d = shelf("durov - Spike hello 1920 test.mp4", "durov - Spike hello 1920 test (1).mp4")
        assertTrue(d.exists("Telegram", "Spike hello 1920 test", Mode.VIDEO, "durov"))
        assertFalse("作者不匹配不算重复", d.exists("Telegram", "Spike hello 1920 test", Mode.VIDEO, "other"))
    }

 /** MediaStore 冲突自动改名 `xxx (1).mp4`：去扩展名后前缀仍命中（前缀方向的动机）。 */
    @Test fun `MediaStore 冲突自动改名 带尾括号 仍前缀命中`() {
        val d = shelf("What is this⧸that? Vol.2: The Sequel (1).mp4")
        assertTrue(d.exists("哔哩哔哩", blueprintTitle, Mode.VIDEO))
    }

 /** 扩展名集合逐项：VIDEO=mp4|mkv|webm；AUDIO=mp3|m4a；COVER=jpg|webp（集合外不命中）。 */
    @Test fun `扩展名集合 VIDEO AUDIO COVER 各自命中 集合外不命中`() {
        fun hit(name: String, mode: Mode): Boolean = shelf(name).exists("汽水", "测试标题", mode)
 // VIDEO
        assertTrue(hit("测试标题.mp4", Mode.VIDEO))
        assertTrue(hit("测试标题.mkv", Mode.VIDEO))
        assertTrue(hit("测试标题.webm", Mode.VIDEO))
        assertTrue("扩展名大小写不敏感（DISPLAY_NAME 可能 .MP4）", hit("测试标题.MP4", Mode.VIDEO))
        assertFalse("mov 不在 VIDEO 集合", hit("测试标题.mov", Mode.VIDEO))
 // AUDIO
        assertTrue(hit("测试标题.mp3", Mode.AUDIO))
        assertTrue(hit("测试标题.m4a", Mode.AUDIO))
        assertFalse("opus 不在 AUDIO 集合（漏判写入局限）", hit("测试标题.opus", Mode.AUDIO))
 // COVER
        assertTrue(hit("测试标题.jpg", Mode.COVER))
        assertTrue(hit("测试标题.webp", Mode.COVER))
        assertFalse("png 不在 COVER 集合", hit("测试标题.png", Mode.COVER))
    }

 /** 前缀方向：预测基名必须是 DISPLAY_NAME 去扩展名后的**起点**前缀——中置/后置不算。 */
    @Test fun `前缀必须从首字符起 中置不算`() {
        val d = shelf("前缀 What is this⧸that? Vol.2: The Sequel.mp4")
        assertFalse("基名不在起点 → 不命中", d.exists("哔哩哔哩", blueprintTitle, Mode.VIDEO))
        assertFalse("完全不同标题 → 不命中", shelf("别的视频.mp4").exists("哔哩哔哩", blueprintTitle, Mode.VIDEO))
        assertFalse(
            "基名比货架名长（货架是前缀）→ 反向不命中",
            shelf("Vol.2.mp4").exists("哔哩哔哩", blueprintTitle, Mode.VIDEO),
        )
    }

 /** 前缀语义另一面：货架基名更长（预测基名是其前缀）→ 命中（同 titled 前缀族，局限注记）。 */
    @Test fun `货架基名以预测基名为起点更长也命中`() {
        val d = shelf("What is this⧸that? Vol.2: The Sequel Part 2.mp4")
        assertTrue(d.exists("哔哩哔哩", blueprintTitle, Mode.VIDEO))
    }

 /** platform 原样透传查询缝（生产=RELATIVE_PATH 前缀 `Download/链接下载/<platform>/`）。 */
    @Test fun `platform 原样透传查询缝`() {
        var seen: String? = null
        val d = DedupCheck { p -> seen = p; listOf("t.mp4") }
        d.exists("汽水", "t", Mode.VIDEO)
        assertEquals("汽水", seen)
    }

 /** 空货架/无扩展名条目不崩不命中。 */
    @Test fun `空货架与无扩展名条目不命中`() {
        assertFalse(DedupCheck { emptyList() }.exists("汽水", "t", Mode.VIDEO))
        assertFalse(shelf("无扩展名").exists("汽水", "无扩展名", Mode.VIDEO))
    }

 /** 超 150 字符标题（不截断两侧）前缀命中——与 safeFilename take(150) 行为区分。
 * 货架名用 sanitize 后形态（真实落盘 DISPLAY_NAME 不可能含 `/`）。 */
    @Test fun `超150标题不截断 前缀命中`() {
        val long = "标题" + "很长的标题".repeat(30) + "/续篇"
        val d = shelf(long.replace('/', '⧸') + ".mp4")
        assertTrue(d.exists("哔哩哔哩", long, Mode.VIDEO))
    }

 /** 真机实证 miss 修复：抖音产物名=safeFilename 归一（删 `|` 等）——判重基名须同源归一才命中。 */
    @Test fun `抖音平台 safeFilename 归一 特殊字符标题命中`() {
 // 产物名（safeFilename 删 `|` 后双空格）；预测基名若走 yt-dlp sanitize 保留 `|` 则恒 miss
        val title = "在孩子们的笑声背后，是老巫妪的低语 | 《巫师3》剧情史诗"
        val product = "在孩子们的笑声背后，是老巫妪的低语  《巫师3》剧情史诗.mp4"
        assertTrue(shelf(product).exists("抖音", title, Mode.VIDEO))
        assertFalse("yt-dlp 平台仍走 sanitize 归一（`|` 保留）→ 不命中该产物名",
            shelf(product).exists("哔哩哔哩", title, Mode.VIDEO))
    }

 /** 真机实证 miss 修复：汽水产物名带 `作者 - ` 前缀——判重须拼 author 才命中。 */
    @Test fun `汽水平台 拼作者前缀命中 不拼则不命中`() {
        assertTrue(shelf("等天黑 - 枫.m4a").exists("汽水", "枫", Mode.AUDIO, author = "等天黑"))
        assertFalse("author 缺省（旧签名兼容）→ 无前缀 → 不命中",
            shelf("等天黑 - 枫.m4a").exists("汽水", "枫", Mode.AUDIO))
    }
}
