package top.linkdown.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内升级（GitHub Releases 直链，spec 公开版）：不走 api.github.com（境内不通+60 次/时限频），
 * 用「固定文件名资产直链」——每个 Release 附 `latest.json`（版本号+更新说明）与 [APK_ASSET]，
 * `releases/latest/download/<文件名>` 恒指最新版。境内经镜像前缀代理（设置可选，域名常换故可配）。
 *
 * 链路：检查（GET latest.json → 语义比版本）→ 下载（OkHttp 分块进度，应用私有目录）→
 * **签名校验**（下载包签名证书与当前应用逐一比对——镜像为第三方，防包被换；系统安装器同签名为
 * 兜底）→ REQUEST_INSTALL_PACKAGES 授权引导 → FileProvider + ACTION_VIEW 唤起系统安装器。
 *
 * 参照 Seal Plus UpdateUtil（同构栈：OkHttp + kotlinx-serialization + FileProvider）。
 */
object AppUpdater {

    const val RELEASE_REPO = "liuyouwu1999/linkdown"
    private const val LATEST_BASE = "https://github.com/$RELEASE_REPO/releases/latest/download"
    const val APK_ASSET = "linkdown-arm64.apk"
    const val LATEST_JSON = "latest.json"

 /** 下载加速镜像预设（""=直连；域名常换，公开后若失效在设置里改自定义前缀——本版只做预设）。 */
    val MIRRORS: List<Pair<String, String>> = listOf(
        "" to "直连",
        "https://ghfast.top" to "ghfast.top",
        "https://gh-proxy.com" to "gh-proxy.com",
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

 // ---- URL 构造（纯函数，JVM 直测） ----

 /** 镜像前缀归一：空=直连；去尾斜杠后补一个 /（ghproxy 系前缀形态 `https://镜像/https://github.com/...`）。 */
    fun mirrorPrefix(mirror: String): String =
        mirror.trim().let { if (it.isEmpty()) "" else it.trimEnd('/') + "/" }

    fun latestJsonUrl(mirror: String): String = "${mirrorPrefix(mirror)}$LATEST_BASE/$LATEST_JSON"

    fun apkUrl(mirror: String): String = "${mirrorPrefix(mirror)}$LATEST_BASE/$APK_ASSET"

 // ---- 版本比较（纯函数，JVM 直测） ----

 /**
 * 语义比较 versionName（X.Y.Z 逐段数值；容忍前缀 v、缺段按 0、非数字段按 0）。
 * 返回负/0/正 = a 小于/等于/大于 b。
 */
    fun compareVersions(a: String, b: String): Int {
        fun segs(v: String): List<Long> =
            v.trim().removePrefix("v").removePrefix("V").split('.').map { it.toLongOrNull() ?: 0L }
        val sa = segs(a); val sb = segs(b)
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val x = sa.getOrElse(i) { 0L }; val y = sb.getOrElse(i) { 0L }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

 // ---- 检查 ----

    data class LatestRelease(val versionName: String, val notes: String)

 /** latest.json 解析（纯函数，JVM 直测）：字段缺失/非 JSON → null。 */
    fun parseLatestJson(text: String): LatestRelease? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        LatestRelease(
            versionName = obj["versionName"]?.jsonPrimitive?.content?.trim() ?: return null,
            notes = obj["notes"]?.jsonPrimitive?.content.orEmpty(),
        )
    }.getOrNull()

    sealed interface CheckResult {
        data object UpToDate : CheckResult
        data class NewRelease(val release: LatestRelease) : CheckResult
        data class Failed(val reason: String) : CheckResult
    }

 /** 检查更新：GET latest.json → 版本比较。任何网络/解析问题归 [CheckResult.Failed]（人话）。 */
    suspend fun check(mirror: String, currentVersionName: String): CheckResult =
        withContext(Dispatchers.IO) {
            val release = runCatching {
                client.newCall(Request.Builder().url(latestJsonUrl(mirror)).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        parseLatestJson(resp.body?.string().orEmpty())
                            ?: throw IllegalStateException("latest.json 格式不对")
                    }
            }.getOrElse { return@withContext CheckResult.Failed("连不上更新源（${it.message ?: "网络异常"}），可在下方换下载加速镜像") }
            when {
                compareVersions(currentVersionName, release.versionName) < 0 ->
                    CheckResult.NewRelease(release)
                else -> CheckResult.UpToDate
            }
        }

 // ---- 下载（应用私有目录，分块+进度+取消） ----

 /** 下载最新 APK 到 [target]（应用私有目录，无需存储权限），onProgress 传 0..1。 */
    suspend fun downloadApk(
        mirror: String,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val call = client.newCall(Request.Builder().url(apkUrl(mirror)).build())
            call.execute().use { resp ->
                check(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body ?: throw IllegalStateException("空响应体")
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 shl 10)
                        var read: Int
                        var done = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            if (isCancelled()) throw InterruptedException("已取消")
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                        }
                        onProgress(1f)
                    }
                }
            }
            target
        }
    }

 // ---- 签名校验（防镜像换包；系统安装器同签名为兜底） ----

    private fun certDigests(signers: Array<out android.content.pm.Signature>): Set<String> =
        signers.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

 /** 下载包签名证书与当前应用逐一比对：一致才允许进安装器。 */
    fun isSignatureValid(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners ?: return false
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.signingInfo?.apkContentsSigners ?: return false
        certDigests(installed) == certDigests(archive)
    }.getOrDefault(false)

 // ---- 安装（REQUEST_INSTALL_PACKAGES 授权引导 + FileProvider 唤起安装器） ----

    const val FILE_AUTHORITY_SUFFIX = ".fileprovider"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

 /** 跳「允许安装未知应用」授权页（Android 8+ 按应用开关；授权后回到本应用再触发安装）。 */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

 /** 唤起系统安装器（调用方先过 [canInstall] 门与 [isSignatureValid] 校验）。 */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + FILE_AUTHORITY_SUFFIX, apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setDataAndType(uri, "application/vnd.android.package-archive"),
        )
        true
    }.getOrDefault(false)
}
