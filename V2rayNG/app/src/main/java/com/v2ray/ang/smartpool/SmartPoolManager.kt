package com.v2ray.ang.smartpool

import android.content.Context
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigBatchImporter
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow

object SmartPoolManager {
    private var dispatcher: SmartPoolDispatcher? = null
    private var balancer: SmartPoolBalancer? = null
    private var prober: SmartPoolProber? = null
    private var radar: SmartRadarCapture? = null

    val radarCountState = MutableStateFlow(0)
    val radarRunningState = MutableStateFlow(false)

    fun isSmartPoolConfig(profile: ProfileItem?): Boolean {
        return profile?.configType == EConfigType.SMART_POOL
    }

    fun getAllServerGuids(): List<String> {
        val result = mutableListOf<String>()
        result.addAll(MmkvManager.decodeServerList(""))
        MmkvManager.decodeSubscriptions().forEach { sub ->
            result.addAll(MmkvManager.decodeServerList(sub.guid))
        }
        return result.distinct()
    }

    fun getValidPoolCandidates(): List<ProfileItem> {
        val allGuids = getAllServerGuids()
        val raw = allGuids.mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { it.configType != EConfigType.SMART_POOL && it.configType != EConfigType.CUSTOM }
        return SmartPoolNodeFilter.filterAndDeduplicate(raw)
    }

    fun onProxiesUpdated(subscriptionId: String = "") {
        runCatching {
            val subs = MmkvManager.decodeSubscriptions()
            if (subs.any { it.guid == "group_smart_proxy" }) {
                MmkvManager.removeSubscription("group_smart_proxy")
            }
        }

        val candidates = getValidPoolCandidates()
        val countText = if (candidates.isNotEmpty()) " (${candidates.size} узлов)" else ""
        val poolRemarks = "${SmartPoolConstants.SMART_POOL_REMARKS}$countText"

        if (subscriptionId.isNotBlank()) {
            ensureSmartPoolInGroup(subscriptionId, poolRemarks)
        }
        MmkvManager.decodeSubscriptions().forEach { sub ->
            ensureSmartPoolInGroup(sub.guid, poolRemarks)
        }
        ensureSmartPoolInGroup("", poolRemarks)

        if (balancer != null && candidates.isNotEmpty()) {
            val bal = SmartPoolBalancer(candidates)
            bal.onLeaderChanged = { leader ->
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            }
            balancer = bal
            prober?.calibrateOnce()
            bal.getCurrentLeader()?.let { leader ->
                com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
            }
            LogUtil.i(SmartPoolConstants.TAG, "SmartPool live-reloaded with ${candidates.size} nodes")
        }
    }

    fun ensureDefaultSmartPoolNode(): String {
        runCatching {
            val subs = MmkvManager.decodeSubscriptions()
            if (subs.any { it.guid == "group_smart_proxy" }) {
                MmkvManager.removeSubscription("group_smart_proxy")
            }
        }

        val candidates = getValidPoolCandidates()
        val countText = if (candidates.isNotEmpty()) " (${candidates.size} узлов)" else ""
        val poolRemarks = "${SmartPoolConstants.SMART_POOL_REMARKS}$countText"

        var firstGuid = ""
        MmkvManager.decodeSubscriptions().forEach { sub ->
            val g = ensureSmartPoolInGroup(sub.guid, poolRemarks)
            if (firstGuid.isEmpty()) firstGuid = g
        }
        val defaultGuid = ensureSmartPoolInGroup("", poolRemarks)
        return if (firstGuid.isNotEmpty()) firstGuid else defaultGuid
    }

    fun ensureSmartPoolInGroup(groupId: String, remarks: String): String {
        val list = MmkvManager.decodeServerList(groupId)
        for (guid in list) {
            val cfg = MmkvManager.decodeServerConfig(guid)
            if (cfg?.configType == EConfigType.SMART_POOL) {
                if (cfg.remarks != remarks) {
                    cfg.remarks = remarks
                    MmkvManager.encodeServerConfig(guid, cfg)
                }
                if (list.indexOf(guid) != 0) {
                    list.remove(guid)
                    list.add(0, guid)
                    MmkvManager.encodeServerList(list, groupId)
                }
                return guid
            }
        }

        val poolNode = ProfileItem.create(EConfigType.SMART_POOL).apply {
            this.remarks = remarks
            server = "127.0.0.1"
            serverPort = SmartPoolConstants.DISPATCHER_PORT.toString()
            this.subscriptionId = groupId
        }
        val parsed = AngConfigBatchImporter.ParsedProfile(poolNode, rawConfig = "smart_pool")
        AngConfigBatchImporter.commitProfiles(listOf(parsed), groupId, append = true)

        val updatedList = MmkvManager.decodeServerList(groupId)
        val createdGuid = updatedList.firstOrNull {
            MmkvManager.decodeServerConfig(it)?.configType == EConfigType.SMART_POOL
        } ?: ""

        if (createdGuid.isNotEmpty() && updatedList.indexOf(createdGuid) != 0) {
            updatedList.remove(createdGuid)
            updatedList.add(0, createdGuid)
            MmkvManager.encodeServerList(updatedList, groupId)
        }
        return createdGuid
    }

    fun getCurrentLeader(): SmartNodeState? = balancer?.getCurrentLeader()

    fun getNotificationTitle(defaultTitle: String): String {
        val leader = getCurrentLeader()
        return if (leader != null && leader.profile.remarks.isNotBlank()) {
            "${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}"
        } else {
            defaultTitle
        }
    }

    fun generateMultiInboundJson(context: Context): String {
        val candidates = getValidPoolCandidates()
        val v2rayConfig = SmartPoolConfigBuilder.buildMultiInboundConfig(context, candidates)
        return JsonUtil.toJsonPretty(v2rayConfig).orEmpty()
    }

    fun onCoreStarting(context: Context, profile: ProfileItem) {
        if (!isSmartPoolConfig(profile)) return
        stop()
        LogUtil.i(SmartPoolConstants.TAG, "Starting SmartPool Manager...")
        val candidates = getValidPoolCandidates()
        val bal = SmartPoolBalancer(candidates)
        bal.onLeaderChanged = { leader ->
            com.v2ray.ang.handler.NotificationManager.updateTitle("${SmartPoolConstants.SMART_POOL_REMARKS} → ${leader.profile.remarks}")
        }
        balancer = bal
        LogUtil.i(SmartPoolConstants.TAG, "🚀 [SmartPool] Менеджер запущен: 1 Лидер + ${bal.getStandbyCount()} Standbys в горячем пуле (из ${candidates.size} доступных нод)")
        val disp = SmartPoolDispatcher(bal)
        dispatcher = disp
        disp.start()

        val prb = SmartPoolProber(bal)
        prober = prb
        prb.start()
    }

    fun stop() {
        dispatcher?.stop()
        dispatcher = null
        prober?.stop()
        prober = null
        balancer = null
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
