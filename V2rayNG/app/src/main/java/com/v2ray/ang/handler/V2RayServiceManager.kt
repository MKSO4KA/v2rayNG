package com.v2ray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.service.V2RayProxyOnlyService
import com.v2ray.ang.service.V2RayVpnService
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            V2RayNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    fun startVServiceFromToggle(context: Context): Boolean {
        val targetGuid = QuickTileManager.resolveQsTileTarget()
        if (targetGuid != null) {
            MmkvManager.setSelectServer(targetGuid)
        }
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startContextService(context)
        return true
    }

    fun startVService(context: Context, guid: String? = null) {
        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }
        startContextService(context)
    }

    fun stopVService(context: Context) {
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    fun isRunning() = coreController.isRunning

    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    private fun startContextService(context: Context) {
        if (coreController.isRunning) {
            return
        }
        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            return
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            return
        }
        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            return
        }
        SettingsManager.refreshRuntimeSocksPort()

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            context.toast(R.string.toast_warning_pref_proxysharing_short)
        } else {
            context.toast(R.string.toast_services_start)
        }

        val isVpnMode = SettingsManager.isVpnMode()
        val intent = if (isVpnMode) {
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to start service", e)
        }
    }

    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (coreController.isRunning) {
            return false
        }
        val service = getService()
        if (service == null) {
            return false
        }
        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            return false
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            return false
        }
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            return false
        }
        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            return false
        }
        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }
        try {
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            return false
        }
        if (coreController.isRunning == false) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }
        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
        } catch (e: Exception) {
            return false
        }
        return true
    }

    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false
        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        NotificationManager.cancelNotification()
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }
        return true
    }

    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    fun getCoreDelay(): Long {
        if (coreController.isRunning == false) return -1L
        return try {
            coreController.measureDelay(SettingsManager.getDelayTestUrl())
        } catch (e: Exception) {
            -1L
        }
    }

    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""
            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            }
            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    private class CoreCallback : CoreCallbackHandler {
        private val recentPings = ConcurrentHashMap<String, Long>()

        override fun startup(): Long {
            return 0
        }
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                -1
            }
        }
        override fun onEmitStatus(l: Long, s: String?): Long {
            if (s == null) return 0
            
            if (l == 2L) { // Observatory ping update
                val parts = s.split(":")
                if (parts.size == 2) {
                    val tag = parts[0]
                    val latency = parts[1].toLongOrNull() ?: -1L
                    
                    val activeGuid = V2rayConfigManager.groupTagMap[tag]
                    if (activeGuid != null) {
                        val activeProfile = MmkvManager.decodeServerConfig(activeGuid)
                        if (activeProfile != null) {
                            recentPings[activeProfile.remarks] = latency
                            NotificationManager.updateNodeLatency(activeProfile.remarks, latency)
                        }
                    }
                }
            } else if (l == 1L) { // Switch active node
                val activeGuid = V2rayConfigManager.groupTagMap[s]
                var activeProfile = if (activeGuid != null) MmkvManager.decodeServerConfig(activeGuid) else null
                
                if (activeProfile == null && s != AppConfig.TAG_DIRECT && s != AppConfig.TAG_BLOCKED) {
                    activeProfile = SettingsManager.getServerViaRemarks(s)
                }

                if (activeProfile != null) {
                    val summary = recentPings.entries.joinToString(", ") { "${it.key}: ${if (it.value >= 0) "${it.value}ms" else "Timeout"}" }
                    if (summary.isNotBlank()) {
                        LogUtil.i(AppConfig.TAG, "[Bridge-API] Tag Selected: $s -> Mapped to: ${activeProfile.remarks}")
                        LogUtil.i(AppConfig.TAG, "[Bridge-API] Current Latency Table: [$summary]")
                    } else {
                        LogUtil.i(AppConfig.TAG, "[Bridge-API] Tag Selected: $s -> Mapped to: ${activeProfile.remarks}")
                    }
                    recentPings.clear()

                    NotificationManager.updateActiveNode(activeProfile.remarks)
                    val service = serviceControl?.get()?.getService()
                    if (service != null) {
                        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                    }
                } else if (s == AppConfig.TAG_DIRECT || s == AppConfig.TAG_BLOCKED) {
                    LogUtil.d(AppConfig.TAG, "[Bridge-API] Internal detour: $s")
                } else {
                    LogUtil.w(AppConfig.TAG, "[Bridge-API] Unmapped tag selected: $s")
                }
            }
            return 0
        }
    }

    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)
        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }
            if (destIP.isBlank() || destPort == 0L) {
                return -1L
            }
            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                uid
            } catch (e: Exception) {
                -1L
            }
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControlInstance = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControlInstance.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControlInstance.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }
                AppConfig.MSG_UNREGISTER_CLIENT -> {}
                AppConfig.MSG_STATE_START -> {}
                AppConfig.MSG_STATE_STOP -> {
                    serviceControlInstance.stopService()
                }
                AppConfig.MSG_STATE_RESTART -> {
                    if (serviceControlInstance is V2RayVpnService) {
                        serviceControlInstance.softRestart()
                    } else {
                        serviceControlInstance.stopService()
                        Thread.sleep(500L)
                        startVService(serviceControlInstance.getService())
                    }
                }
                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    NotificationManager.stopSpeedNotification(currentConfig)
                }
                Intent.ACTION_SCREEN_ON -> {
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}

