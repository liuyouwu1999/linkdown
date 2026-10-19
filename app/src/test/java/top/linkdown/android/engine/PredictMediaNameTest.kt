package top.linkdown.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import top.linkdown.android.engine.internal.PredictMediaName

/**
 * predictMediaName 归一化纯函数直测（参考实现 复现用例逐字）。
 * 参考实现 =fork 实测 yt-dlp 2026.08.19 Android 路径 `%(title)s` sanitize 短路行为
 * （YoutubeDL.py L1399-1402：`str(value).replace('/', '⧸').replace('\0', '')`——
 * 非 win32 且非 restrictfilenames 且 windowsfilenames=False 分支），**不使用**
 * 参考管线 postprocess.py/safe_filename 的替换表与 150 截断（restricted 模式，本 App 不用）。
 */
class PredictMediaNameTest {

 /** 参考实现 复现用例（设计期望值修正）：仅 `/`→⧸（U+29F8），`?`/`:` 原样保留合法。 */
    @Test fun `参考实现 复现 斜杠转U29F8 问号冒号原样保留`() {
        assertEquals(
            "What is this⧸that? Vol.2: The Sequel.mp4",
            PredictMediaName.predict("What is this/that? Vol.2: The Sequel", "mp4"),
        )
        assertEquals(
            "预测基名（无扩展名）=sanitize 后标题本体（前缀比对用）",
            "What is this⧸that? Vol.2: The Sequel",
            PredictMediaName.base("What is this/that? Vol.2: The Sequel"),
        )
    }

 /** NUL 字符移除（sanitize 第二段 replace('\0','')；先换斜杠后删 NUL 结果同序无关）。 */
    @Test fun `NUL 字符移除 其余字符不动`() {
        assertEquals("a⧸bc.mp4", PredictMediaName.predict("a/b\u0000c", "mp4"))
    }

 /** Android（Linux）路径仅 `/` 与 NUL 非法——其余特殊字符原样保留（与 safe_filename 替换表区分）。 */
    @Test fun `特殊字符原样保留 不走替换表`() {
        assertEquals(
            "AC⧸DC? Live* at 100% <HD>: Remix|\"2026\".mp4",
            PredictMediaName.predict("AC/DC? Live* at 100% <HD>: Remix|\"2026\"", "mp4"),
        )
    }

 /** 超 150 字符标题不截断（**有意与 参考实现 safeFilename take(150) 区分**——fork 实测不截断）。 */
    @Test fun `超150标题不截断`() {
        val long = "标题" + "很长的标题".repeat(30) + "/续篇" // 2+150+3=155 字符含斜杠
        assertEquals(155, long.length)
        assertEquals("预测名 sanitize 后拼扩展名", "${long.replace('/', '⧸')}.mp4", PredictMediaName.predict(long, "mp4"))
        assertEquals("超长基名前缀比对同不截断", long.replace('/', '⧸'), PredictMediaName.base(long))
    }

 /** predict = base + "." + ext（base/predict 单源）。 */
    @Test fun `predict 等于 base 拼扩展名`() {
        val t = "测试 / 标题"
        assertEquals("${PredictMediaName.base(t)}.mkv", PredictMediaName.predict(t, "mkv"))
    }
}
