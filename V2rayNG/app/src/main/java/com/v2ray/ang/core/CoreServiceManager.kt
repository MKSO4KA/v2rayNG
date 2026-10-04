package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object CoreServiceManager {
    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = CoreReceiveMessageHandler { reqId -> measureV2rayDelay(reqId) }
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    private var networkMonitor: NetworkMonitor? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isReloading = false
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    fun isRunning() = coreController.isRunning
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) return false
        val service = getService() ?: return false
        return try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
            currentVpnInterface = vpnInterface
            launchCore(service, vpnInterface)
            startNetworkMonitor(service)
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            false
        }
    }

    @Throws(Exception::class)
    private fun launchCore(service: Service, vpnInterface: ParcelFileDescriptor?, isReload: Boolean = false) {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        currentConfig = config
        com.v2ray.ang.smartpool.SmartPoolManager.onCoreStarting(service, config)
        var tunFd = if (SettingsManager.isUsingHevTun()) 0 else (vpnInterface?.fd ?: 0)
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) "127.0.0.1:${Utils.findRandomFreePort()}" else ""
        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        coreController.startLoop(result.content, tunFd)
        if (!isRunning()) error("Core failed to start")
        com.v2ray.ang.smartpool.SmartPoolManager.onCoreStarted()
        browserDialer?.stop()
        browserDialer = null
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> DialerNativeService().also { it.start(service, dialerAddr); browserDialer = it }
            BrowserDialerMode.WEBVIEW -> DialerWebviewService().also { it.start(service, dialerAddr); browserDialer = it }
            else -> {}
        }
        if (!isReload) MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        NotificationManager.startSpeedNotification()
    }

    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
        val service = getService() ?: return false
        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { coreController.stopLoop() }.onFailure { LogUtil.e(AppConfig.TAG, "Failed to stop V2Ray loop", it) }
            }
        }
        com.v2ray.ang.smartpool.SmartPoolManager.stop()
        CoreNativeManager.reconcileBrowserDialer("")
        browserDialer?.stop()
        browserDialer = null
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()
        runCatching { service.unregisterReceiver(mMsgReceive) }
        return true
    }

    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || networkMonitor != null) return
        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { reloadCore() },
        ).also { it.register() }
    }

    private fun reloadCore(): Boolean {
        if (isReloading) return false
        val service = getService() ?: return false
        if (!isRunning()) return false
        return try {
            isReloading = true
            connectionTestScope.coroutineContext.cancelChildren()
            coreController.stopLoop()
            launchCore(service, currentVpnInterface, isReload = true)
            true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            isReloading = false
        }
    }

    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        if (!isRunning()) return emptyList()
        val payload = coreController.queryAllOutboundTrafficStats()
        val result = ArrayList<OutboundTrafficStat>()
        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            result.add(OutboundTrafficStat(tag = parts[0], direction = parts[1], value = value))
        }
        return result
    }

    private fun measureV2rayDelay(requestId: String) {
        CoreDelayTester.measureV2rayDelay(connectionTestScope, coreController, getService(), requestId, ::isRunning) { isReloading }
    }

    private fun getService(): Service? = serviceControl?.get()?.getService()

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}

