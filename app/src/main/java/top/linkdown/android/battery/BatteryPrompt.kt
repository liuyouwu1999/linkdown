package top.linkdown.android.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.linkdown.android.ui.PixelButton
import top.linkdown.android.ui.PixelButtonStyle
import top.linkdown.android.ui.PixelCheckRow
import top.linkdown.android.ui.PixelPanel
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites

/**
 * 电池优化引导（按设计）：判定（PowerManager.isIgnoringBatteryOptimizations）+ 跳转 intent
 * （ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package data，manifest 已声明同名权限）+ 像素对话框。
 * 弹与不弹的旗标侧（DataStore 旗标）在 SettingsRepository/MainActivity。出口语义：
 * 「去设置」授权后不再问；勾「不再提醒」+「暂 不」/点外=永久关闭；不勾「暂 不」=本次关闭、
 * 下次启动再问（**不置旗标**）。
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

/** 像素对话框（PixelPanel 版式）：主按钮「去设置」/ 次「暂 不」，勾选行「不再提醒」随「暂 不」生效
 *（勾选交互与更新弹窗统一，同 NovaX 流量弹窗范式：勾选随主操作生效，不做误触即不可逆的独立按钮）。 */
@Composable
fun BatteryPromptDialog(onAccept: () -> Unit, onDecline: (neverAsk: Boolean) -> Unit) {
    val c = PixelTheme.colors
    var neverAsk by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDecline(neverAsk) }) {
        PixelPanel {
            Text("关掉电池优化？", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "不关的话，锁屏后下载可能被系统暂停，队列里的单子会干等。建议允许「链接小卖部」后台跑完下载。",
                color = c.textSecondary, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(9.dp))
            PixelCheckRow("不再提醒", Sprites.NOTE, "", neverAsk) { neverAsk = !neverAsk }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("暂 不", style = PixelButtonStyle.SUCCESS, onClick = { onDecline(neverAsk) }, modifier = Modifier.weight(1f))
                PixelButton("去设置", onClick = onAccept, modifier = Modifier.weight(1f))
            }
        }
    }
}
