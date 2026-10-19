package top.linkdown.android.eula

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
 * 首启用户协议与免责声明（开源合规）：完全免费声明 + 免责四要素（DownKyi/Seal 同类项目惯例，
 * DownKyi 因平台律师函关停的先例——协议与免责前置是最小防护）。文案常量 [TERMS] JVM 单测直断言；
 * 同意旗标（DataStore eula_accepted）在 SettingsRepository，接线在 MainActivity——**先于**电池引导，
 * 且必须显式选择：点外不关闭（onDismissRequest 空）、不同意=退出应用。
 */
object EulaPrompt {

 /** 协议条款（逐条渲染；JVM 测试断言关键要素在此集合内）。 */
    val TERMS = listOf(
        "1. 本软件完全免费，仅供个人学习、技术研究与交流使用；禁止任何形式的商业用途，禁止倒卖或付费分发。",
        "2. 下载任何内容前，请确保你拥有合法的访问与使用权利，并遵守内容来源平台的服务条款；因下载、传播内容产生的一切责任由使用者自行承担，下载内容的版权归原作者所有。",
        "3. 本软件与任何第三方平台均无关联；平台接口变更导致功能失效的，开发者不承诺维护。",
        "4. 本软件按「现状」提供，不作任何明示或默示的担保；对因使用本软件产生的直接或间接损失，开发者不承担责任。",
        "5. 本软件仅将你粘贴的链接提交到对应平台获取内容，不收集、不上传其他信息。",
    )
}

/** 首启协议对话框（像素风；必须显式选择——同意并继续 / 不同意退出，点外无操作）。 */
@Composable
fun EulaPromptDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = PixelTheme.colors
    Dialog(onDismissRequest = { /* 必须显式选择，点外不关闭 */ }) {
        PixelPanel {
            Text("用户协议与免责声明", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EulaPrompt.TERMS.forEach {
                    Text(it, color = c.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton("不同意", style = PixelButtonStyle.SUCCESS, onClick = onDecline, modifier = Modifier.weight(1f))
                PixelButton("同意", onClick = onAccept, modifier = Modifier.weight(1f))
            }
        }
    }
}
