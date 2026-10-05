package com.v2ray.ang.handler

import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AngSubscriptionUpdater {
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            MmkvManager.decodeSubscriptions().fold(SubscriptionUpdateResult()) { acc, sub -> acc + updateConfigViaSub(sub) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    fun updateConfigViaSub(
        it: SubscriptionCache,
        callerIntervalMs: Long = 0L,
        force: Boolean = false
    ): SubscriptionUpdateResult {
        if (!it.subscription.enabled || TextUtils.isEmpty(it.guid) || TextUtils.isEmpty(it.subscription.remarks) || TextUtils.isEmpty(it.subscription.url)) {
            return SubscriptionUpdateResult(skipCount = 1)
        }
        if (!com.v2ray.ang.smartpool.SubscriptionUpdateCoordinator.canUpdate(it.guid, callerIntervalMs, force, it.subscription.lastUpdated)) {
            return SubscriptionUpdateResult(skipCount = 1)
        }
        val url = HttpUtil.toIdnUrl(it.subscription.url)
        if (!Utils.isValidUrl(url) || (!it.subscription.allowInsecureUrl && !Utils.isValidSubUrl(url))) {
            return SubscriptionUpdateResult(failureCount = 1)
        }
        LogUtil.i(com.v2ray.ang.smartpool.SmartPoolConstants.TAG, "📥 [Подписка] Запрос обновления: '${it.subscription.remarks}' (URL: $url)")
        var configText = runCatching {
            com.v2ray.ang.smartpool.SmartSubFetcher.fetchRawContentWithCascade(
                url,
                profile = it.subscription.toMimicryProfile()
            )
        }.getOrDefault("")

        if (configText.isEmpty()) {
            val req = UrlContentRequest(
                url = url,
                userAgent = it.subscription.userAgent,
                requestHeaders = it.subscription.requestHeaders,
                timeout = 15000,
                httpPort = SettingsManager.getHttpPort(),
                proxyUsername = SettingsManager.getSocksUsername(),
                proxyPassword = SettingsManager.getSocksPassword()
            )
            configText = runCatching { HttpUtil.getUrlContentWithUserAgent(req) }.getOrDefault("")
        }
        if (configText.isEmpty()) {
            LogUtil.w(com.v2ray.ang.smartpool.SmartPoolConstants.TAG, "❌ [Подписка] Не удалось получить контент для '${it.subscription.remarks}'")
            return SubscriptionUpdateResult(failureCount = 1)
        }

        val count = parseConfigViaSub(configText, it.guid, false)
        LogUtil.i(com.v2ray.ang.smartpool.SmartPoolConstants.TAG, "📋 [Подписка] Распарсено $count узлов для группы '${it.subscription.remarks}'")
        return if (count > 0) {
            val serverGuids = MmkvManager.decodeServerList(it.guid)
            val parsedNodes = serverGuids.mapNotNull { guid -> MmkvManager.decodeServerConfig(guid) }
            val newHash = parsedNodes.map { node -> com.v2ray.ang.smartpool.SmartNodeHasher.computeNodeHash(node) }
                .sorted().joinToString(",").let { joined -> com.v2ray.ang.smartpool.SmartPoolSubAutoUpdater.computeHash(joined) }
            val oldHashKey = "sub_nodes_hash_${it.guid}"
            val oldHash = MmkvManager.decodeSettingsString(oldHashKey)
            if (oldHash != null && oldHash == newHash) {
                LogUtil.i(com.v2ray.ang.smartpool.SmartPoolConstants.TAG, "🔍 [Сверка хэша] Хэши всех ${parsedNodes.size} нод подписки '${it.subscription.remarks}' идентичны (${newHash.take(10)}...). Структурных изменений нет.")
            } else {
                LogUtil.i(com.v2ray.ang.smartpool.SmartPoolConstants.TAG, "⚡ [Сверка хэша] Обнаружены изменения в составе нод '${it.subscription.remarks}'! Старый хэш: ${oldHash?.take(10) ?: "none"}, Новый: ${newHash.take(10)}...")
                MmkvManager.encodeSettings(oldHashKey, newHash)
            }
            com.v2ray.ang.smartpool.SmartPoolManager.onProxiesUpdated(it.guid)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GIST_AUTO_SYNC_ENABLED, true)) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    com.v2ray.ang.smartpool.gist.GistSyncManager.syncAllFromGist()
                }
            }
            it.subscription.lastUpdated = System.currentTimeMillis()
            com.v2ray.ang.smartpool.SubscriptionUpdateCoordinator.markUpdated(it.guid)
            MmkvManager.encodeSubscription(it.guid, it.subscription)
            SubscriptionUpdateResult(configCount = count, successCount = 1)
        } else {
            SubscriptionUpdateResult(failureCount = 1)
        }
    }

    fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = AngConfigBatchImporter.parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) count = AngConfigBatchImporter.parseBatchConfig(server, subid, append)
        if (count <= 0) {
            val smartNodes = com.v2ray.ang.smartpool.SmartSubFetcher.parseNodesFromPayload(server.orEmpty())
            if (smartNodes.isNotEmpty()) {
                val parsedProfiles = smartNodes.map { node ->
                    node.subscriptionId = subid
                    node.description = AngConfigManager.generateDescription(node)
                    AngConfigBatchImporter.ParsedProfile(node)
                }
                AngConfigBatchImporter.commitProfiles(parsedProfiles, subid, append)
                count = smartNodes.size
            }
        }
        if (count <= 0) count = AngConfigBatchImporter.parseCustomConfigServer(server, subid, append)
        return count
    }

    fun parseBatchSubscription(servers: String?): Int {
        if (servers == null) return 0
        var count = 0
        servers.lines().distinct().forEach { if (Utils.isValidSubUrl(it)) count += importUrlAsSubscription(it) }
        return count
    }

    fun importUrlAsSubscription(url: String): Int {
        if (MmkvManager.decodeSubscriptions().any { it.subscription.url == url }) return 0
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem(remarks = uri.fragment ?: "import sub", url = url)
        MmkvManager.encodeSubscription("", subItem)
        return 1
    }

    fun removeInvalidServer(subId: String) {
        val invalid = MmkvManager.decodeServerList(subId).filter { guid ->
            val config = MmkvManager.decodeServerConfig(guid)
            config?.configType != com.v2ray.ang.enums.EConfigType.SMART_POOL &&
                MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis?.let { d -> d < 0L } == true
        }
        MmkvManager.removeServers(invalid, subId)
    }

    fun sortByTestResultsForSub(subId: String) {
        val list = MmkvManager.decodeServerList(subId)
        if (list.isEmpty()) return
        val sorted = list.map { it to (MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L).let { d -> if (d <= 0L) Long.MAX_VALUE else d } }
            .sortedBy { it.second }.map { it.first }.toMutableList()
        MmkvManager.encodeServerList(sorted, subId)
    }
}