package com.v2ray.ang.smartpool.gist

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.gist.GistBlacklistItem
import com.v2ray.ang.dto.gist.GistPoolItem
import com.v2ray.ang.dto.gist.GistRuleItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.smartpool.SmartPoolManager
import com.v2ray.ang.smartpool.SmartSubFetcher
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

object GistSyncManager {
    const val PREF_GIST_BLACKLIST = "pref_gist_blacklist_patterns"
    const val PREF_GIST_RULES_CACHE = "pref_gist_rules_cache"

    fun fetchUrlContent(url: String): String {
        return SmartSubFetcher.fetchRawContentWithCascade(url)
    }

    fun parseRulesJson(json: String): List<GistRuleItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            JsonUtil.fromJson(json, Array<GistRuleItem>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun parsePoolJson(json: String): List<GistPoolItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            JsonUtil.fromJson(json, Array<GistPoolItem>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun parseBlacklistJson(json: String): List<GistBlacklistItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            JsonUtil.fromJson(json, Array<GistBlacklistItem>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun applyBlacklist(items: List<GistBlacklistItem>): Int {
        val patterns = items.map { it.pattern.trim() }.filter { it.isNotBlank() }.distinct()
        if (MmkvManager.isInitialized) {
            MmkvManager.encodeSettings(PREF_GIST_BLACKLIST, JsonUtil.toJson(patterns))
        }
        LogUtil.i(SmartPoolConstants.TAG, "[GistSync] Blacklist patterns saved: ${patterns.size}")
        return patterns.size
    }

    fun getSavedBlacklistPatterns(): List<String> {
        if (!MmkvManager.isInitialized) return emptyList()
        val json = MmkvManager.decodeSettingsString(PREF_GIST_BLACKLIST)
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun syncRulesToSmartPool(rules: List<GistRuleItem>): Int {
        if (rules.isEmpty()) return 0
        SmartPoolManager.ensureSmartPoolGroup()
        if (MmkvManager.isInitialized) {
            MmkvManager.encodeSettings(PREF_GIST_RULES_CACHE, JsonUtil.toJson(rules))
        }
        val existingGuids = MmkvManager.decodeServerList(SmartPoolConstants.SMART_POOL_GROUP_ID)
        val existingMap = existingGuids.mapNotNull { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { guid to it }
        }.toMap()

        var count = 0
        for (rule in rules) {
            val existingEntry = existingMap.entries.firstOrNull { it.value.remarks == rule.remarks }
            val profile = existingEntry?.value ?: ProfileItem.create(EConfigType.SMART_POOL)
            profile.remarks = rule.remarks
            profile.subscriptionId = SmartPoolConstants.SMART_POOL_GROUP_ID
            profile.server = AppConfig.LOOPBACK
            profile.serverPort = SmartPoolConstants.DISPATCHER_PORT.toString()
            profile.smartPoolFilterRegex = rule.regex
            profile.smartPoolPolicyType = rule.strategy
            profile.smartPoolInterval = rule.interval
            profile.smartPoolTolerance = rule.tolerance
            profile.smartPoolValidationMethod = rule.validationMethod
            profile.smartPoolPortLimit = rule.portLimit
            profile.smartPoolSubUpdateInterval = rule.subUpdateInterval
            profile.smartPoolTargetSubId = rule.sourceGroup

            val targetGuid = existingEntry?.key.orEmpty()
            MmkvManager.encodeServerConfig(targetGuid, profile)
            count++
        }
        LogUtil.i(SmartPoolConstants.TAG, "[GistSync] SmartPool profiles synced from Gist rules: $count")
        return count
    }

    suspend fun syncAllFromGist(activePorts: List<Int> = emptyList()): GistSyncResult {
        val rulesUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_GIST_RULES_URL).orEmpty()
        val poolUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_GIST_POOL_URL).orEmpty()
        val blacklistUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_GIST_BLACKLIST_URL).orEmpty()

        val targets = listOf(rulesUrl, poolUrl, blacklistUrl).filter { it.isNotBlank() }
        if (targets.isEmpty()) return GistSyncResult()

        val fetchedMap = GistSyncMatrixFetcher.fetchBatchMultiplexed(targets, activePorts)
        var rulesCount = 0
        var poolCount = 0
        var blacklistCount = 0

        fetchedMap[rulesUrl]?.let { text ->
            val rules = parseRulesJson(text)
            if (rules.isNotEmpty()) {
                rulesCount = syncRulesToSmartPool(rules)
            }
        }

        fetchedMap[poolUrl]?.let { text ->
            val pools = parsePoolJson(text)
            if (pools.isNotEmpty()) {
                GistPoolResolver.savePools(pools)
                poolCount = pools.size
            }
        }

        fetchedMap[blacklistUrl]?.let { text ->
            val blacklist = parseBlacklistJson(text)
            if (blacklist.isNotEmpty()) {
                blacklistCount = applyBlacklist(blacklist)
            }
        }

        return GistSyncResult(
            rulesCount = rulesCount,
            poolCount = poolCount,
            blacklistCount = blacklistCount,
            success = rulesCount > 0 || poolCount > 0 || blacklistCount > 0
        )
    }
}

data class GistSyncResult(
    val rulesCount: Int = 0,
    val poolCount: Int = 0,
    val blacklistCount: Int = 0,
    val success: Boolean = false
) {
    val hasAnyData: Boolean get() = rulesCount > 0 || poolCount > 0 || blacklistCount > 0
}
