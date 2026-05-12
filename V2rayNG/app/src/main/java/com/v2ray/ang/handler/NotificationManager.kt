package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val QUERY_INTERVAL_MS = 3000L

    private var lastQueryTime = 0L
    private var mBuilder: NotificationCompat.Builder? = null
    private var speedNotificationJob: Job? = null
    private var mNotificationManager: NotificationManager? = null
    
    @Volatile private var externalActiveNodeName: String? = null
    @Volatile private var externalActiveNodeLatency: String? = null
    private val nodeLatencyMap = ConcurrentHashMap<String, String>()

    fun updateActiveNode(nodeName: String) {
        externalActiveNodeName = nodeName
        val latency = nodeLatencyMap[nodeName] ?: "..."
        externalActiveNodeLatency = latency
        
        val title = "[V2RayNG] Active: $nodeName • $latency"
        mBuilder?.setContentTitle(title)
        getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
    }
    
    fun updateNodeLatency(nodeName: String, latencyMs: Long) {
        val latencyStr = if (latencyMs >= 0) "${latencyMs}ms" else "Timeout"
        nodeLatencyMap[nodeName] = latencyStr
        
        if (nodeName == externalActiveNodeName) {
            externalActiveNodeLatency = latencyStr
            val title = "[V2RayNG] Active: $nodeName • $latencyStr"
            mBuilder?.setContentTitle(title)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Starts the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun startSpeedNotification(currentConfig: ProfileItem?) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) return
        if (speedNotificationJob != null || V2RayServiceManager.isRunning() == false) return

        var lastZeroSpeed = false
        
        val outboundTags = mutableListOf<String>()
        val tagToNameMap = mutableMapOf<String, String>()
        var initialActiveProxyName: String? = null

        if (currentConfig?.configType == EConfigType.POLICYGROUP) {
            val configPairs = AutoOutboundBuilder.getFilteredRoutingProxies(currentConfig.policyGroupFilter, currentConfig.policyGroupSubscriptionId)
            val bestPair = configPairs.minByOrNull { 
                val delay = MmkvManager.decodeServerAffiliationInfo(it.first)?.testDelayMillis ?: 0L
                if (delay <= 0L) 999999L else delay
            }
            initialActiveProxyName = bestPair?.second?.remarks
            if (externalActiveNodeName == null) {
                externalActiveNodeName = initialActiveProxyName
            }

            configPairs.forEachIndexed { index, pair ->
                val tag = "proxy-${index + 1}"
                outboundTags.add(tag)
                tagToNameMap[tag] = pair.second.remarks
            }
        } else {
            currentConfig?.getAllOutboundTags()?.let { tags ->
                tags.remove(AppConfig.TAG_DIRECT)
                outboundTags.addAll(tags)
                tags.forEach { tagToNameMap[it] = currentConfig.remarks }
            }
        }

        var lastDelayText = "..."
        var cycleCount = 0
        val lastTraffic = mutableMapOf<String, Long>()

        speedNotificationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val queryTime = System.currentTimeMillis()
                val sinceLastQueryIn = (queryTime - lastQueryTime)

                if (sinceLastQueryIn < QUERY_INTERVAL_MS) {
                    lastQueryTime = queryTime
                    delay(QUERY_INTERVAL_MS)
                    continue
                }
                val sinceLastQueryInSeconds = sinceLastQueryIn / 1000.0

                var proxyTotal = 0L
                val text = StringBuilder()
                val currentTraffic = mutableMapOf<String, Long>()

                outboundTags.forEach { tag ->
                    val up = V2RayServiceManager.queryStats(tag, AppConfig.UPLINK)
                    val down = V2RayServiceManager.queryStats(tag, AppConfig.DOWNLINK)
                    val total = up + down
                    currentTraffic[tag] = total

                    if (total > 0) {
                        appendSpeedString(text, tagToNameMap[tag] ?: tag, up / sinceLastQueryInSeconds, down / sinceLastQueryInSeconds)
                        proxyTotal += total
                    }
                }
                lastTraffic.putAll(currentTraffic)

                if (cycleCount % 5 == 0 && V2RayServiceManager.isRunning()) {
                    val delay = V2RayServiceManager.getCoreDelay()
                    lastDelayText = if (delay >= 0) "${delay}ms" else "Error"
                    // We don't log generic core delay continuously unless needed for debug
                }
                cycleCount++

                val directUplink = V2RayServiceManager.queryStats(AppConfig.TAG_DIRECT, AppConfig.UPLINK)
                val directDownlink = V2RayServiceManager.queryStats(AppConfig.TAG_DIRECT, AppConfig.DOWNLINK)
                val zeroSpeed = proxyTotal == 0L && directUplink == 0L && directDownlink == 0L
                
                if (!zeroSpeed || !lastZeroSpeed) {
                    if (proxyTotal == 0L) {
                        appendSpeedString(text, outboundTags.firstOrNull()?.let { tagToNameMap[it] ?: it }, 0.0, 0.0)
                    }
                    appendSpeedString(
                        text, AppConfig.TAG_DIRECT, directUplink / sinceLastQueryInSeconds,
                        directDownlink / sinceLastQueryInSeconds
                    )
                    
                    val activeProxyName = externalActiveNodeName ?: initialActiveProxyName
                    
                    val title = if (activeProxyName != null) {
                        val lat = externalActiveNodeLatency ?: lastDelayText
                        "[V2RayNG] Active: $activeProxyName • $lat"
                    } else {
                        "[V2RayNG] Active: ${currentConfig?.remarks} • $lastDelayText"
                    }
                    
                    updateNotification(title, text.toString(), proxyTotal, directDownlink + directUplink)
                }
                lastZeroSpeed = zeroSpeed
                lastQueryTime = queryTime
                delay(QUERY_INTERVAL_MS)
            }
        }
    }

    /**
     * Shows the notification.
     * @param currentConfig The current profile configuration.
     */
    fun showNotification(currentConfig: ProfileItem?) {
        val service = getService() ?: return

        lastQueryTime = System.currentTimeMillis()

        var initialTitle = "[V2RayNG] Active: ${currentConfig?.remarks ?: "None"}"
        if (currentConfig?.configType == EConfigType.POLICYGROUP) {
            val configPairs = AutoOutboundBuilder.getFilteredRoutingProxies(currentConfig.policyGroupFilter, currentConfig.policyGroupSubscriptionId)
            val bestPair = configPairs.minByOrNull { 
                val delay = MmkvManager.decodeServerAffiliationInfo(it.first)?.testDelayMillis ?: 0L
                if (delay <= 0L) 999999L else delay
            }
            if (bestPair != null) {
                val latency = nodeLatencyMap[bestPair.second.remarks] ?: "..."
                initialTitle = "[V2RayNG] Active: ${bestPair.second.remarks} • $latency"
                externalActiveNodeName = bestPair.second.remarks
                externalActiveNodeLatency = latency
            }
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            } else {
                ""
            }

        mBuilder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(initialTitle)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_delete_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        service.startForeground(NOTIFICATION_ID, mBuilder?.build())
    }

    /**
     * Cancels the notification.
     */
    fun cancelNotification() {
        val service = getService() ?: return
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

        mBuilder = null
        speedNotificationJob?.cancel()
        speedNotificationJob = null
        mNotificationManager = null
    }

    /**
     * Stops the speed notification.
     * @param currentConfig The current profile configuration.
     */
    fun stopSpeedNotification(currentConfig: ProfileItem?) {
        speedNotificationJob?.let {
            it.cancel()
            speedNotificationJob = null
            updateNotification("[V2RayNG] Active: ${currentConfig?.remarks}", "", 0, 0)
        }
    }

    /**
     * Creates a notification channel for Android O and above.
     * @return The channel ID.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = AppConfig.RAY_NG_CHANNEL_NAME
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_HIGH
        )
        chan.lightColor = Color.DKGRAY
        chan.importance = NotificationManager.IMPORTANCE_NONE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager()?.createNotificationChannel(chan)
        return channelId
    }

    /**
     * Updates the notification with the given content text and traffic data.
     * @param title The dynamic title of the notification.
     * @param contentText The content text.
     * @param proxyTraffic The proxy traffic.
     * @param directTraffic The direct traffic.
     */
    private fun updateNotification(title: String?, contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        if (mBuilder != null) {
            if (proxyTraffic < NOTIFICATION_ICON_THRESHOLD && directTraffic < NOTIFICATION_ICON_THRESHOLD) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_name)
            } else if (proxyTraffic > directTraffic) {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_proxy)
            } else {
                mBuilder?.setSmallIcon(R.drawable.ic_stat_direct)
            }
            mBuilder?.setContentTitle(title)
            mBuilder?.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            mBuilder?.setContentText(contentText)
            getNotificationManager()?.notify(NOTIFICATION_ID, mBuilder?.build())
        }
    }

    /**
     * Gets the notification manager.
     * @return The notification manager.
     */
    private fun getNotificationManager(): NotificationManager? {
        if (mNotificationManager == null) {
            val service = getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        return mNotificationManager
    }

    /**
     * Appends the speed string to the given text.
     * @param text The text to append to.
     * @param name The name of the tag.
     * @param up The uplink speed.
     * @param down The downlink speed.
     */
    private fun appendSpeedString(text: StringBuilder, name: String?, up: Double, down: Double) {
        var n = name ?: "no tag"
        n = n.take(min(n.length, 6))
        text.append(n)
        for (i in n.length..6 step 2) {
            text.append("\t")
        }
        text.append("•  ${up.toLong().toSpeedString()}↑  ${down.toLong().toSpeedString()}↓\n")
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    private fun getService(): Service? {
        return V2RayServiceManager.serviceControl?.get()?.getService()
    }
}

