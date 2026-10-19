package top.linkdown.android.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SettingsRepository 单测。
 * 默认值（mp3/max/2）/ setter round-trip / 非法值回退（存储被手改或旧版本残留非法键时读侧兜底）。
 * DataStore Preferences 同 QueuePersistenceTest 同款 JVM 直构（独立 scope，结束 cancel）。
 */
class SettingsRepositoryTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "sr-${System.nanoTime()}").apply { mkdirs() }

    private fun create(root: File, scope: CoroutineScope): SettingsRepository =
        SettingsRepository.create(File(root, "settings.preferences_pb"), scope)

    @Test fun `默认值 mp3 max 并发2`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            assertEquals(AppSettings("mp3", "max", 2), repo.settings.first())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `三 setter round trip`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            repo.setAudioFormat("m4a")
            repo.setQuality("1080p")
            repo.setMaxConcurrency(4)
            assertEquals(AppSettings("m4a", "1080p", 4), repo.settings.first())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `setter 覆盖旧值（重复写读回最新）`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            repo.setMaxConcurrency(3)
            repo.setMaxConcurrency(2)
            assertEquals(2, repo.settings.first().maxConcurrency)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `存储残留非法值读侧回退默认`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
 // 绕过 setter 直接写非法值（模拟旧版本残留/手改存储）
            repo.dataStore.edit { p ->
                p[SettingsRepositoryKeys.AUDIO_FORMAT] = "flac"
                p[SettingsRepositoryKeys.QUALITY] = "4k"
                p[SettingsRepositoryKeys.MAX_CONCURRENCY] = 99
            }
            assertEquals(AppSettings("mp3", "max", 2), repo.settings.first())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `部分非法值仅该键回退 其余保留`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            repo.setAudioFormat("m4a")
            repo.setMaxConcurrency(3)
            repo.dataStore.edit { p ->
                p[SettingsRepositoryKeys.QUALITY] = "8k" // 仅画质档非法
            }
            assertEquals(AppSettings("m4a", "max", 3), repo.settings.first())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `损坏文件读回默认设置不崩`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(root, "settings.preferences_pb")
            file.writeText("not-a-datastore-file-garbage!!!")
            val repo = create(root, scope)
            assertEquals(AppSettings("mp3", "max", 2), repo.settings.first())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `货架默认显示书架`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            assertEquals(true, repo.isShelfBoardMode())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `书架切换后按用户上次决定持久化`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            repo.setShelfBoardMode(false)
            assertEquals(false, repo.isShelfBoardMode())
            repo.setShelfBoardMode(true)
            assertEquals(true, repo.isShelfBoardMode())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `启动自动检查更新默认开`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            assertEquals(true, repo.isAutoCheckUpdate())
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `自动检查更新开关按用户决定持久化`() = runTest {
        val root = tempRoot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = create(root, scope)
            repo.setAutoCheckUpdate(false)
            assertEquals(false, repo.isAutoCheckUpdate())
            assertEquals(false, repo.settings.first().autoCheckUpdate) // 设置页渲染流同步（不再提醒→开关回传）
            repo.setAutoCheckUpdate(true)
            assertEquals(true, repo.isAutoCheckUpdate())
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
