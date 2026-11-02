package top.linkdown.android.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.launch
import top.linkdown.android.ui.PixelButton
import top.linkdown.android.ui.PixelButtonStyle
import top.linkdown.android.ui.PixelCheckRow
import top.linkdown.android.ui.PixelPanel
import top.linkdown.android.ui.PixelProgressBar
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites

/**
 * 升级弹窗两件套（主入口=设置页「检查更新」+ 启动自动检查共用）：
 * - [UpdateOfferDialog]：发现新版 → 更新说明 + 去下载/下次再说；
 * - [UpdateInstallFlow]：下载（进度）→ 签名校验 → 「安装未知应用」授权引导（授权页返回自动续装）→
 * 系统安装器。签名不过=中止并删包（防镜像换包，spec 公开版拍板项）。
 */
@Composable
fun UpdateOfferDialog(
    release: AppUpdater.LatestRelease,
    onDownload: () -> Unit,
    onDismiss: (neverRemind: Boolean) -> Unit,
    neverRemindOption: Boolean = true,
) {
    val c = PixelTheme.colors
 // 「不再提醒」勾选行（产品拍板 v1.0.0.4，交互范式对齐 NovaX 流量弹窗：勾选随主操作生效，
 // 不做独立按钮——误触即不可逆关闭。恢复入口=设置页「启动时自动检查更新」开关，B站/咪咕同款惯例）。
 // neverRemindOption=false（设置页手动检查路径）不渲染勾选行——显示却不生效的控件伤信任
    var neverRemind by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = { onDismiss(neverRemind) }) {
        PixelPanel {
            Text(
                "发现新版本 v${release.versionName}",
                color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
 // notes 来自 GitHub latest.json 自由文本，长度不受控：限高内滚动，防超长把按钮顶出屏幕（评审 P1）
            Text(
                release.notes.ifBlank { "修复与改进" },
                color = c.textSecondary, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (neverRemindOption) {
                Spacer(Modifier.height(9.dp))
                PixelCheckRow("不再提醒", Sprites.NOTE, "", neverRemind) { neverRemind = !neverRemind }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                PixelButton("下次再说", style = PixelButtonStyle.SUCCESS, onClick = { onDismiss(neverRemind) }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PixelButton("下载更新", onClick = onDownload, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun UpdateInstallFlow(
    release: AppUpdater.LatestRelease,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = PixelTheme.colors
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("下载中…") }
    var cancelled by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) } // 完成（已交安装器）/失败 → 只剩关闭
    var canRetryInstall by remember { mutableStateOf(false) }
    val apk = remember { File(context.getExternalFilesDir("apk"), "update.apk") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
 // 真机实测（MIUI）：从授权页返回的瞬间 canInstall() 可能尚未同步成 true——延迟重读几次再判
        scope.launch {
            var granted = false
            for (attempt in 0 until 6) {
                if (AppUpdater.canInstall(context)) { granted = true; break }
                kotlinx.coroutines.delay(400)
            }
            if (granted) {
                if (AppUpdater.install(context, apk)) {
                    statusText = "已交给系统安装器"
                    terminal = true
                } else {
                    statusText = "唤起安装器失败，可点重试安装"
                    canRetryInstall = true
                    terminal = true
                }
            } else {
                statusText = "未授予「安装未知应用」权限，无法安装"
                canRetryInstall = true
                terminal = true
            }
        }
    }

    fun tryInstall() {
        if (AppUpdater.canInstall(context)) {
            if (AppUpdater.install(context, apk)) {
                statusText = "已交给系统安装器"
                terminal = true
            } else {
                statusText = "唤起安装器失败"
                terminal = true
            }
        } else {
            statusText = "去系统设置允许「安装未知应用」…"
            installLauncher.launch(AppUpdater.unknownSourcesSettingsIntent(context))
        }
    }

    LaunchedEffect(release) {
        val result = AppUpdater.downloadApk(apk, { progress = it }, { cancelled }, onSource = { index ->
 // 源级联：当前源失败自动切下一个，进度重计并给出可见提示（零用户选择）
            if (index > 0) {
                progress = 0f
                statusText = "切换下载源（第 ${index + 1} 个）…"
            }
        })
        if (cancelled) {
            apk.delete()
            onDismiss()
            return@LaunchedEffect
        }
        if (result.isSuccess) {
            statusText = "校验签名…"
            if (AppUpdater.isSignatureValid(context, apk)) {
                tryInstall()
            } else {
                apk.delete()
                statusText = "下载包签名与本体不符，已中止安装"
                terminal = true
            }
        } else {
            apk.delete()
            statusText = "下载失败（${result.exceptionOrNull()?.message ?: "网络异常"}）"
            terminal = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        PixelPanel {
            Text(
                "更新到 v${release.versionName}",
                color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            PixelProgressBar(progress)
            Spacer(Modifier.height(8.dp))
            Text(
                "${(progress * 100).toInt()}%  ·  $statusText",
                color = c.textSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (terminal) {
                Row {
                    if (canRetryInstall) {
                        PixelButton("重试安装", onClick = { canRetryInstall = false; tryInstall() }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                    }
                    PixelButton("关 闭", style = PixelButtonStyle.SUCCESS, onClick = onDismiss, modifier = Modifier.weight(1f))
                }
            } else {
                PixelButton("取 消", style = PixelButtonStyle.SUCCESS, onClick = {
                    cancelled = true
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
