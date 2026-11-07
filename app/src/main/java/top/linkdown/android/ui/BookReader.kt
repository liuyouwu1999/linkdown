package top.linkdown.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 作品阅读器（设计 §8）：全屏覆盖层，横向翻页。
 * 图片页（含 .MP.jpg 实况合成图，按静图显示）降采样解码；视频/音频/其他页「▶ 播放」占位，
 * 点击复用 ShelfStore.play Resolver 路径，阅读器保持打开。
 */
@Composable
fun BookReader(entry: ShelfEntry, onDismiss: () -> Unit, onPlayPage: (ShelfItem) -> Unit) {
    val pagerState = rememberPagerState { entry.pages.size }
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xEE120A04))
            // 系统条让位（Task 9 E2E 缺陷修复）：覆盖层是根级兄弟，不走 screenModifier 的
            // safeDrawingPadding——顶栏「关 闭」/底栏页码原样画进状态栏/手势条窗口，
            // 触摸被系统状态栏窗口整体拦截（模拟器上实测不可点）。背景保持全屏晕染。
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：标题 + 关闭
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF4A2E18))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    entry.title,
                    color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "关 闭",
                    color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable(MutableInteractionSource(), indication = null) { onDismiss() }
                        .padding(6.dp),
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIdx ->
                val page = entry.pages[pageIdx]
                when (page.kind) {
                    ShelfKind.IMAGE -> ReaderImagePage(page)
                    else -> ReaderPlayPage(page, onPlayPage)
                }
            }
            // 底部页码
            Text(
                "第 ${pagerState.currentPage + 1}/${entry.pages.size} 页",
                color = androidx.compose.ui.graphics.Color(0xFFFFF6E3),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color(0xFF2A1A0E))
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReaderImagePage(page: ShelfItem) {
    val context = LocalContext.current
    var bitmap by remember(page.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.uri) {
        withContext(Dispatchers.IO) { bitmap = decodeSampled(context, page.uri) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = page.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("加载中…", color = androidx.compose.ui.graphics.Color(0xFFC9B88C), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReaderPlayPage(page: ShelfItem, onPlayPage: (ShelfItem) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(MutableInteractionSource(), indication = null) { onPlayPage(page) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("▶", color = androidx.compose.ui.graphics.Color(0xFFFFF6E3), fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Text(page.name, color = androidx.compose.ui.graphics.Color(0xFFC9B88C), fontSize = 12.sp, maxLines = 2)
            Text("点击播放", color = androidx.compose.ui.graphics.Color(0xFF8A7A5A), fontSize = 11.sp)
        }
    }
}

/** 屏幕尺寸降采样解码（设计 §8：对齐 loadCover 的防御风格，失败 null）。 */
private suspend fun decodeSampled(context: android.content.Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val metrics = context.resources.displayMetrics
            val reqW = metrics.widthPixels.coerceAtLeast(1)
            val reqH = metrics.heightPixels.coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }
