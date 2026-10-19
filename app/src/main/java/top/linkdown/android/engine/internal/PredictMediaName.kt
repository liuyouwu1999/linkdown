package top.linkdown.android.engine.internal

/**
 * yt-dlp 落盘名预测（ 库判重 设计纯函数）：复刻 fork（yt-dlp 2026.08.19，Chaquopy
 * Android 路径）的 `%(title)s` sanitize 短路行为——YoutubeDL.py prepare_outtmpl 实证
 * （`sys.platform != 'win32' && !restrictfilenames && windowsfilenames is False` 分支）：
 *
 * ```
 * def sanitize(key, value):
 * return str(value).replace('/', '⧸').replace('\0', '')
 * ```
 *
 * 即 Android 路径仅两步：`/`→⧸（U+29F8）、删 NUL；`?`/`:` 等其余字符在 Linux 路径原样合法。
 * **有意不使用** 参考管线 postprocess.py/safe_filename 的替换表与 150 截断（那是 restricted
 * 模式行为，本 App 引擎侧 outtmpl=`%(title)s.%(ext)s` 不带 restrictfilenames）。
 * 局限：Windows/restrictfilenames 规则不同（本 App 不用）；原生引擎（抖音/汽水）落盘走
 * safeFilename 清洗，特殊字符/超 150 标题可能漏判（设计残留局限注记）。
 */
object PredictMediaName {

 /** sanitize 短路（fork 实测行为逐字）：`/`→⧸（U+29F8）、删 NUL，其余原样。 */
    fun base(title: String): String =
        title.replace('/', '⧸').replace("\u0000", "")

 /** 预测落盘名（引擎 outtmpl=`%(title)s.%(ext)s`）：sanitize 后标题 + "." + 扩展名。 */
    fun predict(title: String, ext: String): String = "${base(title)}.$ext"
}
