package com.v2ray.ang.smartpool

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow

object SmartPoolManager {
    private var dispatcher: SmartPoolDispatcher? = null
    private var balancer: SmartPoolBalancer? = null
    private var prober: SmartPoolProber? = null
    private var autoUpdater: SmartPoolSubAutoUpdater? = null
    private var radar: SmartRadarCapture? = null
    var activeProfile: ProfileItem? = null
    @Volatile var currentLeaderRemarks: String? = null

    val radarCountState = MutableStateFlow(0)
    val radarRunningState = MutableStateFlow(false)

    fun isSmartPoolConfig(profile: ProfileItem?): Boolean = profile?.configType == EConfigType.SMART_POOL

    fun ensureSmartPoolGroup() {
        if (!MmkvManager.isInitialized) return
        val current = MmkvManager.decodeSubscription(SmartPoolConstants.SMART_POOL_GROUP_ID)
        if (current == null) {
            val subItem = SubscriptionItem(
                remarks = SmartPoolConstants.SMART_POOL_REMARKS,
                url = "",
                autoUpdate = false
            )
            MmkvManager.encodeSubscription(SmartPoolConstants.SMART_POOL_GROUP_ID, subItem)
        }
        reconcileSmartPoolProfiles()
    }

    fun reconcileSmartPoolProfiles() {
        if (!MmkvManager.isInitialized) return
        val allGuids = MmkvManager.decodeAllServerList().distinct()
        val smartPoolGuids = mutableListOf<String>()
        val seenRemarks = mutableMapOf<String, String>()

        for (guid in allGuids) {
            val config = MmkvManager.decodeServerConfig(guid) ?: continue
            if (config.configType == EConfigType.SMART_POOL) {
                val existingGuidForRemarks = seenRemarks[config.remarks]
                if (existingGuidForRemarks != null && existingGuidForRemarks != guid) {
                    MmkvManager.removeServer(guid)
                    continue
                }
                seenRemarks[config.remarks] = guid
                if (config.subscriptionId != SmartPoolConstants.SMART_POOL_GROUP_ID) {
                    config.subscriptionId = SmartPoolConstants.SMART_POOL_GROUP_ID
                    MmkvManager.encodeServerConfig(guid, config)
                }
                smartPoolGuids.add(guid)
            }
        }

        val defaultList = MmkvManager.decodeServerList(DEFAULT_SUBSCRIPTION_ID)
        if (defaultList.removeAll(smartPoolGuids.toSet())) {
            MmkvManager.encodeServerList(defaultList, DEFAULT_SUBSCRIPTION_ID)
        }

        MmkvManager.decodeSubscriptions().forEach { sub ->
            if (sub.guid != SmartPoolConstants.SMART_POOL_GROUP_ID) {
                val subList = MmkvManager.decodeServerList(sub.guid)
                if (subList.removeAll(smartPoolGuids.toSet())) {
                    MmkvManager.encodeServerList(subList, sub.guid)
                }
            }
        }

        val currentPoolList = MmkvManager.decodeServerList(SmartPoolConstants.SMART_POOL_GROUP_ID)
        val merged = (smartPoolGuids + currentPoolList).distinct().toMutableList()
        MmkvManager.encodeServerList(merged, SmartPoolConstants.SMART_POOL_GROUP_ID)
    }

    fun getAllServerGuids(): List<String> {
        val result = mutableListOf<String>()
        result.addAll(MmkvManager.decodeServerList(""))
        MmkvManager.decodeSubscriptions().forEach { sub ->
            if (sub.guid != SmartPoolConstants.SMART_POOL_GROUP_ID) {
                result.addAll(MmkvManager.decodeServerList(sub.guid))
            }
        }
        return result.distinct()
    }

    fun getValidPoolCandidates(targetFilterRegex: String? = null, targetSubId: String? = null): List<ProfileItem> {
        val effectiveSubId = targetSubId ?: activeProfile?.smartPoolTargetSubId
        val effectiveRegex = targetFilterRegex ?: activeProfile?.smartPoolFilterRegex ?: run {
            val sel = MmkvManager.getSelectServer()
            if (!sel.isNullOrBlank()) MmkvManager.decodeServerConfig(sel)?.smartPoolFilterRegex else null
        }
        val allGuids = if (effectiveSubId.isNullOrBlank()) getAllServerGuids() else MmkvManager.decodeServerList(effectiveSubId)
        val raw = allGuids.mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { it.configType != EConfigType.SMART_POOL && it.configType != EConfigType.CUSTOM }
        return SmartPoolNodeFilter.filterAndDeduplicate(raw, effectiveRegex)
    }

    fun onProxiesUpdated(subscriptionId: String = "") {
        val bal = balancer ?: return
        val candidates = getValidPoolCandidates()
        if (candidates.isNotEmpty()) {
            bal.differentialUpdate(candidates)
            prober?.calibrateOnce()
            bal.getCurrentLeader()?.let { leader ->
                LogUtil.i(AppConfig.TAG, "SmartPool: proxies updated, leader is '${leader.profile.remarks}' (127.0.0.1:${leader.localPort})")
                currentLeaderRemarks = leader.profile.remarks
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            }
        }
    }

    fun getCurrentLeader(): SmartNodeState? = balancer?.getCurrentLeader()

    fun getNotificationTitle(defaultTitle: String): String {
        val remarks = currentLeaderRemarks
        if (!remarks.isNullOrBlank()) {
            return "${SmartPoolConstants.SMART_POOL_REMARKS} → $remarks"
        }
        val leader = getCurrentLeader()
        return if (leader != null && leader.profile.remarks.isNotBlank()) {
            currentLeaderRemarks = leader.profile.remarks
            "${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}"
        } else {
            defaultTitle
        }
    }

    fun generateMultiInboundJson(context: Context): String {
        val candidates = getValidPoolCandidates()
        val limit = activeProfile?.smartPoolPortLimit ?: SmartPoolConstants.DEFAULT_PORT_LIMIT
        val v2rayConfig = SmartPoolConfigBuilder.buildMultiInboundConfig(context, candidates, limit)
        return JsonUtil.toJsonPretty(v2rayConfig).orEmpty()
    }

    fun onCoreStarting(context: Context, profile: ProfileItem) {
        if (!isSmartPoolConfig(profile)) return
        stop()
        activeProfile = profile
        val portLimit = profile.smartPoolPortLimit ?: SmartPoolConstants.DEFAULT_PORT_LIMIT
        val targetSub = profile.smartPoolTargetSubId ?: profile.subscriptionId
        val candidates = getValidPoolCandidates(profile.smartPoolFilterRegex, targetSub).take(portLimit)
        LogUtil.i(AppConfig.TAG, "SmartPool: onCoreStarting for '${profile.remarks}', pool size: ${candidates.size}, portLimit: $portLimit")
        val bal = SmartPoolBalancer(candidates, portLimit)
        bal.onLeaderChanged = { leader ->
            LogUtil.i(AppConfig.TAG, "SmartPool: active leader -> '${leader.profile.remarks}' (127.0.0.1:${leader.localPort})")
            currentLeaderRemarks = leader.profile.remarks
            com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
        }
        balancer = bal
        val disp = SmartPoolDispatcher(bal)
        dispatcher = disp
        disp.start()
        LogUtil.i(AppConfig.TAG, "SmartPool: dispatcher listening on 127.0.0.1:${SmartPoolConstants.DISPATCHER_PORT}")

        bal.getCurrentLeader()?.let { leader ->
            currentLeaderRemarks = leader.profile.remarks
            com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
        }

        val probeInterval = SmartPoolSubAutoUpdater.parseIntervalToMillis(profile.smartPoolInterval)
        val tolerance = profile.smartPoolTolerance ?: 30.0
        val probeUrls = com.v2ray.ang.smartpool.gist.GistPoolResolver.resolveTestUrls(profile.remarks)
        val baselineUrl = com.v2ray.ang.smartpool.gist.GistPoolResolver.resolveBaselineUrl(profile.remarks)
        prober = SmartPoolProber(bal, probeInterval, tolerance, probeUrls, baselineUrl)

        val subUpdateStr = profile.smartPoolSubUpdateInterval
        autoUpdater = SmartPoolSubAutoUpdater(targetSub, subUpdateStr)
    }

    fun onCoreStarted() {
        prober?.start()
        autoUpdater?.start()
    }

    fun stop() {
        activeProfile = null
        currentLeaderRemarks = null
        autoUpdater?.stop()
        autoUpdater = null
        dispatcher?.stop()
        dispatcher = null
        prober?.stop()
        prober = null
        balancer = null
        LogUtil.i(AppConfig.TAG, "SmartPool: manager stopped")
    }

    fun startRadar(onCaptured: (MimicryProfile) -> Unit) {
        radar?.stop()
        radarCountState.value = 0
        radarRunningState.value = true
        val rad = SmartRadarCapture()
        radar = rad
        rad.start(
            onProgress = { count -> radarCountState.value = count },
            onComplete = {
                radarCountState.value = 3
                radarRunningState.value = false
                onCaptured(it)
            }
        )
    }

    fun stopRadar() {
        radar?.stop()
        radar = null
        radarRunningState.value = false
    }
}
