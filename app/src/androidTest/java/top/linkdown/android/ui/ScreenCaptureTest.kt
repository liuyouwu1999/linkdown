package top.linkdown.android.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

/**
 * 主页截图工具（无需 adb，由测试自行导出）：冷启动拉起真实 MainActivity → 亮屏/去锁屏 →
 * 自动清掉首启叠加弹窗（App 用户协议「同意」/通知权限「不允许」/系统 ANR「Wait」）→
 * UiAutomation 全屏截图 → PNG 原始字节经模拟器 NAT（10.0.2.2=宿主回环）推给宿主侧
 * `nc -l 9876 > screenshots/xxx.png` 监听。宿主没开监听时自动跳过（不拖垮常规测试跑）。
 * 用法：
 *   nc -l 9876 > screenshots/main.png   # 宿主先起监听
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.linkdown.android.ui.ScreenCaptureTest
 */
class ScreenCaptureTest {
    /** 一轮清掉所有窗口里的全部命中（ANR/权限弹窗不在活动窗口树里也能处理）：
     * 外部包弹窗（权限/ANR 屏蔽无障碍点击防自动授权）用 shell 原始触摸注入节点中心；
     * 本 App 的 Compose 弹窗用 ACTION_CLICK。返回本轮是否处理过任何目标。 */
    private fun dismissOverlayOnce(): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val selfPkg = instrumentation.targetContext.packageName
        // 两边统一归一化（去排版撇号/空格）后再比对
        val targets = setOf("同意", "Don'tallow", "不允许", "Wait", "等待", "暂不")
        val roots = runCatching {
            // 顶层窗口优先（权限弹窗盖在 EULA 上时应先被处理）
            ui.windows.sortedByDescending { it.layer }.mapNotNull { it.root }
        }.getOrDefault(emptyList())
        var acted = false
        for (root in roots) {
            val isSelfRoot = root.packageName == selfPkg
            val queue = ArrayDeque<AccessibilityNodeInfo>().also { it.add(root) }
            while (queue.isNotEmpty()) {
                val n = runCatching { queue.removeFirst() }.getOrNull() ?: continue
                val label = runCatching {
                    (n.text?.toString() ?: "").replace("\u2019", "'").replace(" ", "").trim()
                }.getOrDefault("")
                if (label in targets) {
                    if (isSelfRoot) {
                        var node: AccessibilityNodeInfo? = n
                        var hops = 0
                        while (node != null && hops < 4) {
                            val ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) == true
                            if (ok) {
                                acted = true
                                break
                            }
                            node = runCatching { node.parent }.getOrNull()
                            hops++
                        }
                    } else {
                        val r = android.graphics.Rect()
                        runCatching { n.getBoundsInScreen(r) }
                        if (r.width() > 0) {
                            ui.executeShellCommand("input tap ${r.centerX()} ${r.centerY()}")
                            acted = true
                        }
                    }
                }
                runCatching {
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return acted
    }

    /** 看门狗 90s：系统 UI 半死（ANR 风暴）时 waitForIdleSync/wait idle 类调用会无限阻塞，
     * 绝不让 instrumentation 挂死整条 gradle 流水线。 */
    @Test(timeout = 90_000L)
    fun capture_home_screen_to_host_listener() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard")
        context.startActivity(launch)
        // 宿主导喂的兜底坐标（android layout/截图实测的弹窗按钮中心）——无障碍点击被系统
        // 弹窗屏蔽或节点时序不稳时的确定性绕法：①权限弹窗 Don't allow ②用户协议 同意
        // ③电池优化 暂不（弹窗为全屏居中式，坐标在本 AVD 上恒定）
        val args = InstrumentationRegistry.getArguments()
        val taps = listOf(
            args.getString("tapX")?.toIntOrNull() to args.getString("tapY")?.toIntOrNull(),
            args.getString("tap2X")?.toIntOrNull() to args.getString("tap2Y")?.toIntOrNull(),
            args.getString("tap3X")?.toIntOrNull() to args.getString("tap3Y")?.toIntOrNull(),
        ).mapNotNull { (x, y) -> if (x != null && y != null) x to y else null }
        // 冷启动全新安装首帧+弹窗链可能 5s+ 才出——先等本 App 窗口出现（至多 10s）
        var appUp = false
        var waited = 0
        while (!appUp && waited < 10_000) {
            SystemClock.sleep(500); waited += 500
            appUp = runCatching {
                ui.windows.any { it.root?.packageName == context.packageName }
            }.getOrDefault(false)
        }
        // 首启叠加层清理：用户协议/通知权限/电池优化/系统 ANR；弹窗链比点击慢一拍，
        // 允许连续 4 轮扑空才算干净
        if (taps.isNotEmpty()) {
            SystemClock.sleep(1500)
            taps.forEachIndexed { i, (x, y) ->
                ui.executeShellCommand("input tap $x $y")
                // ①权限 → ②协议（等页面反应）→ ③电池弹窗（协议关闭后 3s+ 才弹）
                SystemClock.sleep(if (i == 0) 1500L else if (i == 1) 5000L else 1500L)
            }
        }
        var misses = 0
        var rounds = 0
        while (rounds < 20 && misses < 4) {
            if (dismissOverlayOnce()) misses = 0 else misses++
            SystemClock.sleep(1200)
            rounds++
        }
        // 清弹窗后回前台补一拍（ANR Wait 后 App 可能被遮）；5s 越过「同意」后的页面重建过渡帧
        context.startActivity(launch)
        SystemClock.sleep(5000)

        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val bytes = ByteArrayOutputStream().also { bos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        }.toByteArray()
        // 宿主无监听时 connect 失败 → 跳过；子线程发送规避主线程网络限制
        val sender = Thread {
            runCatching {
                Socket("10.0.2.2", 9876).use { it.getOutputStream().write(bytes) }
            }
        }
        sender.start()
        sender.join(8000)
    }
}
