package top.linkdown.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import top.linkdown.android.cookies.CookieStore
import top.linkdown.android.ui.PixelIcon
import top.linkdown.android.ui.PixelTheme
import top.linkdown.android.ui.Sprites

/**
 * 登录选择页（评审修复 产品拍板）：细棚条 + 招牌「登录账号」+ 平台行卡
 * （色块图标 + 平台名 + 状态「有 ✓/无」+ 右侧「登录 ▸」→ CookieLoginActivity），
 * 状态每次 onResume 刷新（CookieStore.existing）。
 */
class LoginHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PixelTheme { LoginHubScreen(onOpenPlatform = { openPlatform(it) }) } }
    }

    private fun openPlatform(platform: String) {
        startActivity(Intent(this, CookieLoginActivity::class.java).putExtra(CookieLoginActivity.EXTRA_PLATFORM, platform))
    }
}

/** 折叠区/选择页共用的平台清单（顺序即展示顺序） */
internal val LOGIN_PLATFORMS = listOf(CookieLoginActivity.PLATFORM_BILIBILI, CookieLoginActivity.PLATFORM_NETEASE)

internal fun platformDisplayName(platform: String): String = when (platform) {
    CookieLoginActivity.PLATFORM_NETEASE -> "网易云"
    else -> "B站"
}

@Composable
private fun LoginHubScreen(onOpenPlatform: (String) -> Unit) {
    val c = PixelTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var status by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LifecycleResumeEffect(Unit) {
 // 每次回前台刷新（CookieLoginActivity 保存后 finish 回到本页也会触发）
        status = LOGIN_PLATFORMS.associateWith { CookieStore.existing(it, context.filesDir) != null }
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().background(c.background)) {
 // 细棚条（同货架页：10dp 含 3dp 棕底缘）
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .drawBehind {
                    val h = size.height - 3.dp.toPx()
                    val stripe = 18.dp.toPx()
                    var x = 0f; var i = 0
                    while (x < size.width) {
                        drawRect(if (i % 2 == 0) c.primary else c.awningCream, topLeft = Offset(x, 0f), size = Size(stripe, h))
                        x += stripe; i++
                    }
                    drawRect(c.outline, topLeft = Offset(0f, h), size = Size(size.width, 3.dp.toPx()))
                },
        )
 // 招牌「登录账号」
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text("登录账号", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LOGIN_PLATFORMS.forEach { platform ->
                LoginPlatformRow(
                    platform = platform,
                    hasCookie = status[platform] == true,
                    onClick = { onOpenPlatform(platform) },
                )
            }
        }
 // 底部弱字说明
        Text(
            "更多平台的登录支持陆续添加",
            color = c.textSecondary, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LoginPlatformRow(platform: String, hasCookie: Boolean, onClick: () -> Unit) {
    val c = PixelTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val isNetease = platform == CookieLoginActivity.PLATFORM_NETEASE
    val blockColor = if (isNetease) c.success else c.primary
    val blockIcon = if (isNetease) Sprites.NOTE else Sprites.CAMERA
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .border(2.dp, c.outline, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 26.dp)
                .background(blockColor, RoundedCornerShape(4.dp))
                .border(1.dp, c.outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            PixelIcon(blockIcon, c.background, accent = c.surface, iconSize = 16.dp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(platformDisplayName(platform), color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (hasCookie) "已存 cookie：有 ✓" else "已存 cookie：无",
                color = if (hasCookie) c.success else c.textSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        val btnShape = RoundedCornerShape(5.dp)
        Box(
            Modifier
                .background(c.primary, btnShape)
                .border(2.dp, c.outline, btnShape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text("登录 ▸", color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
