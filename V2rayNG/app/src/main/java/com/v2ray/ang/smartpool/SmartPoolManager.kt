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
import com.v2ray.ang.smartpool.SmartPoolStatusServer
import java.util.UUID

object SmartPoolManager {
    private var dispatcher: SmartPoolDispatcher? = null
    private var balancer: SmartPoolBalancer? = null
    var prober: SmartPoolProber? = null

    private var autoUpdater: SmartPoolSubAutoUpdater? = null
    var recruiter: SmartPoolRecruiter? = null
    private var radar: SmartRadarCapture? = null
    var activeProfile: ProfileItem? = null
    @Volatile var currentLeaderRemarks: String? = null
    private val poolSessionToken: String = UUID.randomUUID().toString()
    private var statusServer: SmartPoolStatusServer? = null

    val radarCountState = MutableStateFlow(0)
    val radarRunningState = MutableStateFlow(false)

    fun isSmartPoolConfig(profile: ProfileItem?): Boolean = profile?.configType == EConfigType.SMART_POOL

    fun getPoolAuthCredentials(): Pair<String, String> {
        return Pair(SmartPoolConstants.INTERNAL_POOL_USER, poolSessionToken)
    }

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
            recruiter?.clearWarmStash()
            recruiter?.triggerRecruitment(isCritical = true)
            prober?.calibrateOnce()
            bal.getCurrentLeader()?.let { leader ->
                LogUtil.i(AppConfig.TAG, "SmartPool: proxies updated, leader is '${leader.profile.remarks}' (127.0.0.1:${leader.localPort})")
                currentLeaderRemarks = leader.profile.remarks
                try {
                    com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
                } catch (_: Throwable) {}
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
            try {
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            } catch (_: Throwable) {}
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
        val targetSub = profile.smartPoolTargetSubId ?: profile.subscriptionId
        val candidates = getValidPoolCandidates(profile.smartPoolFilterRegex, targetSub)
        startSession(candidates, profile, enableDispatcher = true)

        val subUpdateStr = profile.smartPoolSubUpdateInterval
        autoUpdater = SmartPoolSubAutoUpdater(targetSub, subUpdateStr)
    }

    fun onCoreStarted() {
        recruiter?.start()
        prober?.start()
        autoUpdater?.start()
        statusServer?.start()
    }

    /**
     * Unified session starter used by both the Android service flow and unit tests.
     */
    fun startSession(
        candidates: List<ProfileItem>,
        profile: ProfileItem,
        probeFunc: ((localPort: Int, timeoutMs: Long) -> Long)? = null,
        enableDispatcher: Boolean = true
    ): SmartPoolBalancer {
        // Ensure a clean state before starting a new session
        stop()
        activeProfile = profile
        val portLimit = profile.smartPoolPortLimit ?: SmartPoolConstants.DEFAULT_PORT_LIMIT
        val limitedCandidates = candidates.take(portLimit)
        LogUtil.i(AppConfig.TAG, "SmartPool: starting session for '${profile.remarks}', pool size: ${limitedCandidates.size}, portLimit: $portLimit")

        val bal = SmartPoolBalancer(limitedCandidates, portLimit)
        bal.onLeaderChanged = { leader ->
            LogUtil.i(AppConfig.TAG, "SmartPool: active leader → '${leader.profile.remarks}' (127.0.0.1:${leader.localPort})")
            currentLeaderRemarks = leader.profile.remarks
            try {
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            } catch (_: Throwable) {}
        }
        balancer = bal

        if (enableDispatcher) {
            val disp = SmartPoolDispatcher(bal)
            dispatcher = disp
            disp.start()
            LogUtil.i(AppConfig.TAG, "SmartPool: dispatcher listening on 127.0.0.1:${SmartPoolConstants.DISPATCHER_PORT}")
        }

        bal.getCurrentLeader()?.let { leader ->
            currentLeaderRemarks = leader.profile.remarks
            try {
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            } catch (_: Throwable) {}
        }

        val probeInterval = SmartPoolSubAutoUpdater.parseIntervalToMillis(profile.smartPoolInterval)
        val tolerance = profile.smartPoolTolerance ?: 30.0
        val probeUrls = com.v2ray.ang.smartpool.gist.GistPoolResolver.resolveTestUrls(profile.remarks)
        val baselineUrl = com.v2ray.ang.smartpool.gist.GistPoolResolver.resolveBaselineUrl(profile.remarks)

        val prb = SmartPoolProber(bal, probeInterval, tolerance, probeUrls, baselineUrl, probeFunc, recruiterProvider = { recruiter })
        prober = prb

        val rec = SmartPoolRecruiter(balancer = bal, toleranceMs = tolerance, probeIntervalMs = probeInterval, probeFunc = probeFunc ?: prb::probeLocalSocks)


        rec.onWarmPoolFilled = { prb.triggerEarlyProbe() }
        bal.onDeficitDetected = { isCrit -> rec.notifyDeficit(isCrit) }
        recruiter = rec

        val sServer = SmartPoolStatusServer(bal, rec)
        statusServer = sServer

        statusServer?.start()
        recruiter?.start()
        prober?.start()

        return bal
    }

    fun stop() {
        activeProfile = null
        currentLeaderRemarks = null
        autoUpdater?.stop()
        autoUpdater = null
        recruiter?.stop()
        recruiter = null
        dispatcher?.stop()
        dispatcher = null
        prober?.stop()
        prober = null
        balancer = null
        statusServer?.stop()
        statusServer = null
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
