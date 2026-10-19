package top.linkdown.android.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.linkdown.android.ui.PixelButton
import top.linkdown.android.ui.PixelButtonStyle
import top.linkdown.android.ui.PixelPanel
import top.linkdown.android.ui.PixelTheme

/**
 * 电池优化引导（按设计）：判定（PowerManager.isIgnoringBatteryOptimizations）+ 跳转 intent
 * （ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package data，manifest 已声明同名权限）+ 像素对话框。
 * 弹与不弹的旗标侧（DataStore 旗标）在 SettingsRepository/MainActivity。三出口语义：
 * 「去设置」授权后不再问；「暂 不」本次关闭、下次启动再问（**不置旗标**）；「不再提醒」永久关闭。
 */
object BatteryPrompt {

 /** 硬件侧条件：系统已把本应用排除出电池优化 → 无需引导。 */
    fun isIgnoringOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

 /** 跳转系统「忽略电池优化」授权弹窗（需 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限+package data）。 */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
}

/** 像素对话框（PixelPanel 版式）：主按钮「去设置」/ 次「暂 不」+ 全宽弱化「不再提醒」。 */
@Composable
fun BatteryPromptDialog(onAccept: () -> Unit, onDecline: () -> Unit, onNeverAsk: () -> Unit) {
    val c = PixelTheme.colors
    Dialog(onDismissRequest = onDecline) {
        PixelPanel {
            Text("关掉电池优化？", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "不关的话，锁屏后下载可能被系统暂停，队列里的单子会干等。建议允许「链接小卖部」后台跑完下载。",
                color = c.textSecondary, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("暂 不", style = PixelButtonStyle.SUCCESS, onClick = onDecline, modifier = Modifier.weight(1f))
                PixelButton("去设置", onClick = onAccept, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            PixelButton("不再提醒", style = PixelButtonStyle.SUCCESS, onClick = onNeverAsk, modifier = Modifier.fillMaxWidth())
        }
    }
}
