package top.linkdown.android.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.net.Socket

/**
 * 设备日志导出工具（无需 adb，由测试自行导出）：`logcat -d` 全量快照经模拟器 NAT（10.0.2.2=宿主
 * 回环）推给宿主侧 `nc -l 9876 > device-log.txt` 监听；宿主无监听时自动跳过。
 * 用法：
 *   nc -l 9876 > device-log.txt
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=top.linkdown.android.ui.LogDumpTest
 */
class LogDumpTest {
    @Test(timeout = 60_000L)
    fun dump_logcat_to_host_listener() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        // 读侧用子线程收，规避主线程网络限制；fd 读完即含全量快照
        val pfd = ui.executeShellCommand("logcat -d -v time")
        val sender = Thread {
            runCatching {
                Socket("10.0.2.2", 9876).use { out ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        input.copyTo(out.getOutputStream())
                    }
                }
            }
        }
        sender.start()
        sender.join(30_000)
    }
}
