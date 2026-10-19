package top.linkdown.android.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.linkdown.android.engine.BatchInfo
import top.linkdown.android.pipeline.DownloadModes
import top.linkdown.android.queue.QueueItem
import top.linkdown.android.queue.QueueState
import top.linkdown.android.queue.QueueUiState

/**
 * 功能任务 队列流语义断言 + 弹窗/登录折叠/剪贴板/出货回归。
 * 注：androidTest 源集方法名禁用空格（D8 dexing 限制）
 *
 * ── 单任务卡五条断言 → 队列流语义迁移对照（按设计对照）──
 * 1. running_shows_cancel_and_no_start
 * → queue_running_card_shows_cancel：旧「运行中主按钮变取 消、下单消失」；新「Running 卡自带
 * 取 消按钮；下 单！常驻可继续排队（队列化后取消/重试移入各卡）」。
 * 2. failed_shows_retry_enabled
 * → queue_failed_card_shows_retry_enabled：失败卡「重 试」存在且可点（queue.retry 语义不变）。
 * 3. retry_disabled_without_url
 * → queue_failed_card_shows_error_text：旧「无 URL 禁重试」的禁用条件消亡——队列项自带 url，
 * 重试不依赖主页输入框；就近替代=失败卡渲染 error 人话（报障载体，设计）。
 * 4. done_returns_to_order_button
 * → queue_completed_not_in_queue_area：旧「完成后回下单按钮」；新「Completed 不入队列区
 * （出货去货架/通知），下 单！仍在」。
 * 5. cancelled_returns_to_order_button
 * → queue_cancelled_not_in_queue_area：旧「取消后回下单按钮」；新「Cancelled 不入队列区，
 * 下 单！仍在且无取消按钮残留」。
 */
class MainScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun qItem(
        state: QueueState,
        id: String = "run-1",
        cancelling: Boolean = false,
        error: String? = null,
        modeLabel: String = "",
        fraction: Float = 0f,
    ) = QueueItem(
        id = id, url = "https://x.com/a/1", platform = "YTDLP",
        modes = DownloadModes(false, false, false), state = state,
        title = "某个视频", taskDir = "/tmp/task", createdAt = 1L,
        error = error, cancelling = cancelling, progressFraction = fraction, modeLabel = modeLabel,
    )

    private fun set(
        url: String = "",
        modes: DownloadModes = DownloadModes(false, false, false),
        queue: QueueUiState = QueueUiState(items = emptyList(), paused = false),
        clipboardUrl: String? = null,
        sessExpanded: Boolean = false,
        hasCookie: Map<String, Boolean> = emptyMap(),
        shelf: List<ShelfItem> = emptyList(),
        onStart: () -> Unit = {}, // M3.5 起 MainScreen.onStart=(modes,url)，harness 转发吞参
        onModeChange: (DownloadModes) -> Unit = {},
        onTogglePaused: () -> Unit = {},
        onImportClipboard: () -> Unit = {},
        onOpenLoginHub: () -> Unit = {},
    ) {
        rule.setContent {
            PixelTheme {
                MainScreen(
                    state = MainUiState(
                        url = url, modes = modes, sessExpanded = sessExpanded,
                        hasCookie = hasCookie, clipboardUrl = clipboardUrl,
                    ),
                    queue = queue,
                    shelf = shelf,
                    onUrlChange = {}, onModeChange = onModeChange, onSessExpandedChange = {}, onSessChange = {},
                    onOpenLoginHub = onOpenLoginHub,
                    onStart = { _, _ -> onStart() }, onCancel = {}, onRetry = {}, onTogglePaused = onTogglePaused,
                    onImportClipboard = onImportClipboard, onOpenShelf = {}, onPlayShelfItem = {},
                    onOpenSettings = {},
                    onEnqueueGroup = { _, _, _, _ -> }, // M3.5 批量入口（本页用例不触达）
                    enumerateBatch = { BatchInfo("", emptyList(), 0) }, // M3.5 入口枚举缝（不触达）
                )
            }
        }
    }

    @Test fun start_disabled_without_url() {
        set()
        rule.onNodeWithText("下 单！").assertIsNotEnabled()
    }

    @Test fun queue_running_card_shows_cancel() {
 // 迁移自 running_shows_cancel_and_no_start（对照表见类注释 #1）
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Running, modeLabel = "视频", fraction = 0.4f)), paused = false))
        rule.onNodeWithText("取 消").assertExists()
        rule.onNodeWithText("下 单！").assertExists() // 下单常驻：运行中可继续排队
    }

    @Test fun queue_failed_card_shows_retry_enabled() {
 // 迁移自 failed_shows_retry_enabled（对照表见类注释 #2）
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Failed, error = "网络不给力：请检查网络后重试")), paused = false))
        rule.onNodeWithText("重 试").assertExists()
        rule.onNodeWithText("重 试").assertIsEnabled()
    }

    @Test fun queue_failed_card_shows_error_text() {
 // 迁移自 retry_disabled_without_url（对照表见类注释 #3：禁用条件消亡，就近替代=error 人话渲染）
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Failed, error = "网络不给力：请检查网络后重试")), paused = false))
        rule.onNodeWithText("网络不给力：请检查网络后重试").assertExists()
    }

    @Test fun queue_completed_not_in_queue_area() {
 // 迁移自 done_returns_to_order_button（对照表见类注释 #4）
        set(url = "https://x.com/a/1", queue = QueueUiState(items = listOf(qItem(QueueState.Completed)), paused = false))
        rule.onNodeWithText("下 单！").assertIsEnabled()
        rule.onNodeWithText("重 试").assertDoesNotExist()
        rule.onNodeWithText("后厨", substring = true).assertDoesNotExist() // 队列区不渲染
    }

    @Test fun queue_cancelled_not_in_queue_area() {
 // 迁移自 cancelled_returns_to_order_button（对照表见类注释 #5）
        set(url = "https://x.com/a/1", queue = QueueUiState(items = listOf(qItem(QueueState.Cancelled)), paused = false))
        rule.onNodeWithText("下 单！").assertExists()
        rule.onNodeWithText("取 消").assertDoesNotExist()
        rule.onNodeWithText("后厨", substring = true).assertDoesNotExist()
    }

    @Test fun queue_cancelling_shows_disabled_button() {
 // 设计：Cancelling（Running+cancelling 子态）显「取消中…」且禁用
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Running, cancelling = true, modeLabel = "视频", fraction = 0.6f)), paused = false))
        rule.onNodeWithText("取消中…").assertIsNotEnabled()
    }

    @Test fun queue_queued_card_shows_position() {
 // 设计：Queued 卡显排位（Running 占 1，本项第 2 位）
        set(
            queue = QueueUiState(
                items = listOf(
                    qItem(id = "run-1", state = QueueState.Running, modeLabel = "视频", fraction = 0.1f),
                    qItem(id = "run-2", state = QueueState.Queued),
                ),
                paused = false,
            ),
        )
        rule.onNodeWithText("排队中 第 2 位").assertExists()
    }

    @Test fun queue_waiting_network_card_shows_label_and_cancel() {
 // 设计：WaitingNetwork 卡显「等待网络」+ 可取消（暂停不禁取消，设计）
        set(queue = QueueUiState(items = listOf(qItem(QueueState.WaitingNetwork)), paused = false))
        rule.onNodeWithText("等待网络").assertExists()
        rule.onNodeWithText("取 消").assertExists()
    }

    @Test fun queue_exists_card_shows_shelf_hint_and_direct_button() {
 // 设计：库判重命中卡「已在货架上」+「去 货 架」直达（真机验收）
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Exists)), paused = false))
        rule.onNodeWithText("已在货架上").assertExists()
        rule.onNodeWithText("去 货 架").assertExists()
    }

    @Test fun queue_pause_button_shows_when_not_paused() {
        var toggles = 0
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Running, modeLabel = "视频", fraction = 0.2f)), paused = false), onTogglePaused = { toggles++ })
        rule.onNodeWithText("暂 停").assertExists().performClick()
        rule.runOnIdle { assertTrue("暂停按钮应触发 onTogglePaused", toggles == 1) }
    }

    @Test fun queue_continue_button_shows_when_paused() {
        set(queue = QueueUiState(items = listOf(qItem(QueueState.Running, modeLabel = "视频", fraction = 0.2f)), paused = true), onTogglePaused = {})
        rule.onNodeWithText("继 续").assertExists()
    }

    @Test fun typing_url_enables_start() {
        val url = androidx.compose.runtime.mutableStateOf("")
        rule.setContent {
            PixelTheme {
                MainScreen(
                    state = MainUiState(url = url.value),
                    queue = QueueUiState(items = emptyList(), paused = false),
                    shelf = emptyList(),
                    onUrlChange = { url.value = it }, onModeChange = {}, onSessExpandedChange = {},
                    onSessChange = {}, onOpenLoginHub = {}, onStart = { _, _ -> }, onCancel = {}, onRetry = {},
                    onTogglePaused = {}, onImportClipboard = {}, onOpenShelf = {}, onPlayShelfItem = {},
                    onOpenSettings = {}, onEnqueueGroup = { _, _, _, _ -> }, enumerateBatch = { BatchInfo("", emptyList(), 0) },
                )
            }
        }
        rule.onNodeWithTag("urlField").performTextInput("https://x.com/a/1")
        assertEquals("https://x.com/a/1", url.value)
        rule.onNodeWithText("下 单！").assertIsEnabled()
    }

    @Test fun main_screen_has_no_resident_mode_selection() {
 // 产品拍板：主界面不常驻模式选择，纯音乐平台不该默认视频
        set(url = "https://x.com/a/1")
        rule.onNodeWithText("要点什么").assertDoesNotExist()
        rule.onNodeWithText("视频 mp4").assertDoesNotExist()
        rule.onNodeWithText("仅音频").assertDoesNotExist()
        rule.onNodeWithText("封面").assertDoesNotExist()
    }

    @Test fun order_opens_dialog_start_disabled_without_any_mode() {
        set(url = "https://x.com/a/1")
        rule.onNodeWithText("下 单！").assertIsEnabled().performClick()
        rule.onNodeWithText("要点什么").assertExists()
        rule.onNodeWithText("开 始").assertIsNotEnabled()
    }

    @Test fun dialog_check_flow_enables_start_and_confirms() {
        var started = false
        var reported: DownloadModes? = null
        set(url = "https://x.com/a/1", onStart = { started = true }, onModeChange = { reported = it })
        rule.onNodeWithText("下 单！").performClick()
        rule.onNodeWithText("开 始").assertIsNotEnabled()
        rule.onNodeWithText("视频 mp4").performClick()
        rule.onNodeWithText("封面").performClick()
        rule.onNodeWithText("开 始").assertIsEnabled().performClick()
        rule.onNodeWithText("要点什么").assertDoesNotExist() // 确认后弹窗收起
        rule.runOnIdle {
            assertTrue("弹窗确认应触发 onStart（主页入队）", started)
            assertEquals(DownloadModes(video = true, audio = false, cover = true), reported)
        }
    }

    @Test fun dialog_cancel_closes_without_start() {
        var started = false
        set(url = "https://x.com/a/1", onStart = { started = true })
        rule.onNodeWithText("下 单！").performClick()
        rule.onNodeWithText("取 消").performClick()
        rule.onNodeWithText("要点什么").assertDoesNotExist()
        rule.runOnIdle { assertTrue("取消不应触发 onStart", !started) }
    }

    @Test fun login_fold_entry_opens_cookie_login() {
 // fix round 1/2 回归：折叠区单入口「去 登 录」进登录选择页；状态行双平台
        var hubOpened = false
        set(
            sessExpanded = true,
            hasCookie = mapOf("bilibili" to true, "netease" to false),
            onOpenLoginHub = { hubOpened = true },
        )
        rule.onNodeWithText("去 登 录").assertExists().assertIsEnabled().performClick()
        rule.onNodeWithText("B站 有✓ · 网易云 无").assertExists()
        rule.runOnIdle { assertTrue("登录入口应触发 openLoginHub", hubOpened) }
    }

    @Test fun login_fold_sits_between_order_button_and_shipment_panel() {
 // fix round 3：登录块挪到「下 单！」之下、今日出货之上，链接面板与下单之间不放任何东西
        set(url = "https://x.com/a/1", sessExpanded = true)
        val orderY = rule.onNodeWithText("下 单！").fetchSemanticsNode().positionInRoot.y
        val foldY = rule.onNodeWithText("登录账号（可选）", substring = true).fetchSemanticsNode().positionInRoot.y
        val shipmentY = rule.onNodeWithText("今日出货 0 件").fetchSemanticsNode().positionInRoot.y
        rule.runOnIdle {
            assertTrue("登录折叠区应在「下 单！」之下", orderY < foldY)
            assertTrue("登录折叠区应在今日出货面板之上", foldY < shipmentY)
        }
    }

    @Test fun login_fold_status_row_shows_absent_cookie() {
        set(sessExpanded = true)
        rule.onNodeWithText("B站 无 · 网易云 无").assertExists()
    }

    @Test fun clipboard_chip_click_imports() {
        var imported = false
        set(clipboardUrl = "https://b23.tv/abc", onImportClipboard = { imported = true })
        rule.onNodeWithText("剪贴板有一条 · 点此带入").assertExists().performClick()
        rule.runOnIdle { assertTrue("chip 点击应触发带入回调", imported) }
    }

    @Test fun shipment_area_shows_count_and_items() {
        val item = ShelfItem(Uri.EMPTY, "某视频.mp4", "哔哩哔哩", 128L shl 20, ShelfKind.VIDEO, 1L)
        set(shelf = listOf(item))
        rule.onNodeWithText("今日出货 1 件").assertExists()
        rule.onNodeWithText("哔哩哔哩").assertExists()
    }
}
