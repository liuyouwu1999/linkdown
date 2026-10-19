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
 * `releases/latest/download/<文件名>` 恒指最新版。境内按 直连→镜像 顺序自动级联（不设用户选项）。
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

 /** 更新源级联（顺序=优先级）：直连 → ghfast.top → gh-proxy.com。检查与下载都按序逐个试，
 *  任一成功即止，全挂才报失败——用户零选择（此前让用户挑镜像+失败只有一行小字反馈，体验差）。 */
    val SOURCES: List<String> = listOf("", "https://ghfast.top", "https://gh-proxy.com")

 // 检查用小超时（latest.json 只有几百字节；三源全黑洞最坏 ~45s，有界）；
 // 下载绝无 callTimeout——callTimeout 覆盖整个响应体时长，66MB 在境内慢网必然被掐断（评审 P0）
    private val checkClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

 // ---- URL 构造（纯函数，JVM 直测） ----

 /** 源前缀归一：空=直连；去尾斜杠后补一个 /（ghproxy 系前缀形态 `https://镜像/https://github.com/...`）。 */
    fun sourcePrefix(source: String): String =
        source.trim().let { if (it.isEmpty()) "" else it.trimEnd('/') + "/" }

    fun latestJsonUrl(source: String): String = "${sourcePrefix(source)}$LATEST_BASE/$LATEST_JSON"

    fun apkUrl(source: String): String = "${sourcePrefix(source)}$LATEST_BASE/$APK_ASSET"

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

 /** 检查更新：按 [sources] 顺序逐源 GET latest.json → 版本比较；任一源成功即止，
 *  全部失败才归 [CheckResult.Failed]（人话）。取消穿透不打断级联语义外的协程语义。 */
    suspend fun check(
        currentVersionName: String,
        sources: List<String> = SOURCES,
    ): CheckResult = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (source in sources) {
            val release = runCatching {
                checkClient.newCall(Request.Builder().url(latestJsonUrl(source)).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        parseLatestJson(resp.body?.string().orEmpty())
                            ?: throw IllegalStateException("latest.json 格式不对")
                    }
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e // 取消穿透不吞
                lastError = e; continue
            }
            return@withContext when {
                compareVersions(currentVersionName, release.versionName) < 0 ->
                    CheckResult.NewRelease(release)
                else -> CheckResult.UpToDate
            }
        }
        CheckResult.Failed("连接更新源失败，请检查网络后重试（${lastError?.message ?: "网络异常"}）")
    }

 // ---- 下载（应用私有目录，分块+进度+取消） ----

 /** 下载最新 APK 到 [target]（应用私有目录，无需存储权限）：按 [sources] 顺序逐源尝试，
 *  onProgress 传 0..1（换源后从 0 重计），onSource 带当前源下标（从 0 起，UI 可提示切换）。 */
    suspend fun downloadApk(
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        onSource: (Int) -> Unit = {},
        sources: List<String> = SOURCES,
    ): Result<File> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for ((index, source) in sources.withIndex()) {
            if (isCancelled()) return@withContext Result.failure(InterruptedException("已取消"))
            onSource(index)
            val done = runCatching {
                downloadClient.newCall(Request.Builder().url(apkUrl(source)).build())
                    .execute().use { resp ->
                        check(resp.isSuccessful) { "HTTP ${resp.code}" }
                        val body = resp.body ?: throw IllegalStateException("空响应体")
                        val total = body.contentLength()
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 shl 10)
                                var read: Int
                                var written = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    if (isCancelled()) throw InterruptedException("已取消")
                                    out.write(buf, 0, read)
                                    written += read
                                    if (total > 0) onProgress((written.toDouble() / total).toFloat().coerceIn(0f, 1f))
                                }
                                onProgress(1f)
                            }
                        }
                    }
                true
            }.getOrElse { e ->
                // 协程取消穿透；用户取消旗标（InterruptedException）=终止级联返回失败
                //（调用方据 cancelled 关弹窗，重抛会把取消漏成协程未捕获异常）
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is InterruptedException) return@withContext Result.failure(e)
                lastError = e; false
            }
            if (done) return@withContext Result.success(target)
        }
        Result.failure(IllegalStateException("所有下载源都连接失败，请检查网络后重试", lastError))
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
