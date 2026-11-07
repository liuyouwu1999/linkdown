package top.linkdown.android.pipeline

/**
 * 书架台账类型（设计：作品键→文件清单，pipeline 定义避免 pipeline→ui 反向依赖）。
 * FileRef.path=RELATIVE_PATH（含尾斜杠）、name=MediaStore 回查 DISPLAY_NAME。
 */
data class FileRef(val path: String, val name: String)

/** 一次增量合并输入（每模式 ingest 后由 pipeline 构造）。 */
data class LedgerMerge(val workKey: String, val platform: String, val title: String, val files: List<FileRef>)

data class LedgerEntry(
    val workKey: String,
    val platform: String,
    val title: String,
    val files: List<FileRef>,
    val updatedAt: Long,
)
