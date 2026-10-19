package top.linkdown.android.pipeline

object ProgressParser {
    private val re = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
    fun percent(line: String): Float? = re.find(line)?.groupValues?.get(1)?.toFloatOrNull()
}
