package top.linkdown.android.engine.internal

/**
 * 实况图（动态照片）目标格式 —— 逐字平移参考实现 NEORUAA/XHS_Downloader_Android
 * DownloadOptions.kt LivePhotoFormat（去掉 @Serializable：本项目无设置持久化，仅 AUTO 设备自适应）。
 * 协议来源与真机验收边界见参考仓 docs/LIVE_PHOTO_COMPATIBILITY.md。
 */
enum class LivePhotoFormat {
    AUTO, STANDARD, XIAOMI, OPLUS, SAMSUNG, VIVO, VIVO_LEGACY, HUAWEI;

    fun resolve(manufacturer: String, brand: String): LivePhotoFormat {
        if (this != AUTO) return this
        val names = setOf(manufacturer.lowercase(java.util.Locale.ROOT), brand.lowercase(java.util.Locale.ROOT))
        return when {
            names.any { it in setOf("oppo", "oneplus", "realme", "oplus") } -> OPLUS
            "samsung" in names -> SAMSUNG
            names.any { it in setOf("vivo", "iqoo") } -> VIVO
            names.any { it in setOf("huawei", "honor") } -> HUAWEI
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> XIAOMI
            else -> STANDARD
        }
    }
}
