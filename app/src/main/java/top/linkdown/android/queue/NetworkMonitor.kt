package top.linkdown.android.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 网络连通观测（设计WaitingNetwork 判定 + 派发门「网络可用」条件 + 恢复自动重派发）。
 * 接口化：JVM 单测注入假实现；生产用 [AndroidNetworkMonitor]（ConnectivityManager 平台 API）。
 */
interface NetworkMonitor {
    fun isOnline(): Boolean

 /** 注册恢复回调（网络由断转通时触发；DownloadQueue 沿此回队列重派发）。幂等，重复调用先注销。 */
    fun start(onRestored: () -> Unit)
    fun stop()
}

/** ConnectivityManager 平台实现：registerNetworkCallback + 当前连通查询（NET_CAPABILITY_VALIDATED）。 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered: ConnectivityManager.NetworkCallback? = null
    private var wasOnline = false

    override fun isOnline(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun start(onRestored: () -> Unit) {
        stop()
        wasOnline = isOnline()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateOnline(isOnline(), onRestored)
            }

 // 仅 onAvailable 不够（评审修复 Minor②）： captive portal/VPN 切换时 network 已 available
 // 但 VALIDATED 迟到——断网飞行测试（T6）恢复信号靠 onCapabilitiesChanged 携带 VALIDATED
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateOnline(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    onRestored,
                )
            }

            override fun onLost(network: Network) {
                updateOnline(isOnline(), onRestored)
            }
        }
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )
        registered = callback
    }

 /** 断→通沿边沿触发 [onRestored]（仅上升沿；同态重复上报去重）。 */
    private fun updateOnline(now: Boolean, onRestored: () -> Unit) {
        val was = wasOnline
        wasOnline = now
        if (now && !was) onRestored()
    }

    override fun stop() {
        registered?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        registered = null
    }
}
