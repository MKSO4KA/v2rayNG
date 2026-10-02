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

object AngSubscriptionUpdater {
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            MmkvManager.decodeSubscriptions().fold(SubscriptionUpdateResult()) { acc, sub -> acc + updateConfigViaSub(sub) }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        if (!it.subscription.enabled || TextUtils.isEmpty(it.guid) || TextUtils.isEmpty(it.subscription.remarks) || TextUtils.isEmpty(it.subscription.url)) {
            return SubscriptionUpdateResult(skipCount = 1)
        }
        val url = HttpUtil.toIdnUrl(it.subscription.url)
        if (!Utils.isValidUrl(url) || (!it.subscription.allowInsecureUrl && !Utils.isValidSubUrl(url))) {
            return SubscriptionUpdateResult(failureCount = 1)
        }
        val req = UrlContentRequest(
            url = url,
            userAgent = it.subscription.userAgent,
            requestHeaders = it.subscription.requestHeaders,
            timeout = 15000,
            httpPort = SettingsManager.getHttpPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword()
        )
        var configText = runCatching { HttpUtil.getUrlContentWithUserAgent(req) }.getOrDefault("")
        if (configText.isEmpty()) {
            configText = runCatching {
                HttpUtil.getUrlContentWithUserAgent(
                    UrlContentRequest(
                        url = url,
                        userAgent = it.subscription.userAgent,
                        requestHeaders = it.subscription.requestHeaders
                    )
                )
            }.getOrDefault("")
        }
        if (configText.isEmpty()) return SubscriptionUpdateResult(failureCount = 1)
        val count = parseConfigViaSub(configText, it.guid, false)
        return if (count > 0) {
            it.subscription.lastUpdated = System.currentTimeMillis()
            MmkvManager.encodeSubscription(it.guid, it.subscription)
            SubscriptionUpdateResult(configCount = count, successCount = 1)
        } else {
            SubscriptionUpdateResult(failureCount = 1)
        }
    }

    fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        var count = AngConfigBatchImporter.parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) count = AngConfigBatchImporter.parseBatchConfig(server, subid, append)
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
        val invalid = MmkvManager.decodeServerList(subId).filter { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis?.let { d -> d < 0L } == true }
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
