package com.v2ray.ang.handler

import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AngApplication
import com.v2ray.ang.util.MessageUtil

object GistRuleProvider {

    data class GistRuleDto(
        val remarks: String?,
        val regex: String?,
        val strategy: String?,
        val tolerance: Double?,
        val interval: String?
    )

    data class GistBlockRuleDto(
        val pattern: String?,
        val comment: String?
    )

    fun fetchAndParseRules(url: String): Pair<List<AutoGroupRule>?, String?> {
        if (url.isBlank()) return null to null
        try {
            val cacheBusterUrl = if (url.contains("?")) {
                "$url&nocache=${System.currentTimeMillis()}"
            } else {
                "$url?nocache=${System.currentTimeMillis()}"
            }
            
            val json = HttpUtil.getUrlContent(cacheBusterUrl, 15000)
            if (json.isNullOrBlank()) return null to null

            val rules = parseRulesFromJson(json)
            return rules to json
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to fetch or parse gist rules", e)
            return null to null
        }
    }

    fun parseRulesFromJson(json: String): List<AutoGroupRule>? {
        try {
            val dtos = JsonUtil.fromJson(json, Array<GistRuleDto>::class.java) ?: return null
            return dtos.mapNotNull { dto ->
                if (dto.remarks.isNullOrBlank() || dto.regex.isNullOrBlank()) return@mapNotNull null

                AutoGroupRule(
                    id = Utils.getUuid(),
                    remarks = dto.remarks,
                    regex = dto.regex,
                    type = mapStrategyToType(dto.strategy),
                    tolerance = dto.tolerance ?: 50.0,
                    interval = dto.interval ?: "3m",
                    isFromGist = true
                )
            }
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to parse json", e)
            return null
        }
    }

    fun syncBlocklist(subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        val url = subItem.blocklistGistUrl
        if (url.isNullOrBlank()) return
        try {
            val cacheBusterUrl = if (url.contains("?")) "$url&nocache=${System.currentTimeMillis()}" else "$url?nocache=${System.currentTimeMillis()}"
            val json = HttpUtil.getUrlContent(cacheBusterUrl, 15000)
            if (!json.isNullOrBlank()) {
                val dtos = JsonUtil.fromJson(json, Array<GistBlockRuleDto>::class.java)
                if (dtos != null) {
                    subItem.lastBlocklistJson = json
                    MmkvManager.encodeSubscription(subId, subItem)
                }
            }
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to fetch or parse gist blocklist", e)
        }
    }

    fun fetchBlocklistContent(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val cacheBusterUrl = if (url.contains("?")) "$url&nocache=${System.currentTimeMillis()}" else "$url?nocache=${System.currentTimeMillis()}"
            HttpUtil.getUrlContent(cacheBusterUrl, 15000)
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to fetch blocklist content", e)
            null
        }
    }

    fun parseBlocklistFromJson(json: String): List<String>? {
        try {
            val dtos = JsonUtil.fromJson(json, Array<GistBlockRuleDto>::class.java) ?: return null
            return dtos.mapNotNull { it.pattern?.trim()?.takeIf { p -> p.isNotEmpty() } }
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to parse blocklist json", e)
            return null
        }
    }

    fun syncQuickTileGists() {
        val rulesUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_GIST_URL, "") ?: ""
        val blocklistUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_BLOCKLIST_URL, "") ?: ""
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_MODE, "0")?.toIntOrNull() ?: 0

        var configUpdated = false

        if (mode == 4 && rulesUrl.isNotBlank()) {
            val (rules, _) = fetchAndParseRules(rulesUrl)
            if (rules != null && rules.isNotEmpty()) {
                val savedRemarks = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_RULE_REMARKS, "")
                val matchedRule = rules.find { it.remarks == savedRemarks }
                if (matchedRule != null && !matchedRule.regex.isNullOrBlank()) {
                    val currentVal = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_VAL, "")
                    if (currentVal != matchedRule.regex) {
                        MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, matchedRule.regex)
                        configUpdated = true
                    }
                }
            }
        }

        if (blocklistUrl.isNotBlank()) {
            val json = fetchBlocklistContent(blocklistUrl)
            if (!json.isNullOrBlank()) {
                val currentJson = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_BLOCKLIST_JSON, "")
                if (currentJson != json) {
                    MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_BLOCKLIST_JSON, json)
                    configUpdated = true
                }
            }
        }

        if (configUpdated) {
            val targetVal = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_VAL, "") ?: ""
            val interval = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_INTERVAL, "3m") ?: "3m"
            val tolerance = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_TOLERANCE, "50.0")?.toDoubleOrNull() ?: 50.0

            val allServers = MmkvManager.decodeAllServerList()
            val globalGroupGuid = allServers.find { guid ->
                MmkvManager.decodeServerConfig(guid)?.remarks == "Global QS Target"
            }
            
            if (globalGroupGuid != null) {
                val config = MmkvManager.decodeServerConfig(globalGroupGuid)
                if (config != null) {
                    config.policyGroupFilter = targetVal
                    config.policyGroupInterval = interval
                    config.policyGroupTolerance = tolerance
                    MmkvManager.encodeServerConfig(globalGroupGuid, config)
                }
            }
            
            if (V2RayServiceManager.isRunning()) {
                LogUtil.i(AppConfig.TAG, "Gist rules updated, restarting V2Ray service...")
                MessageUtil.sendMsg2Service(AngApplication.application, AppConfig.MSG_STATE_RESTART, "")
            }
        }
    }

    private fun mapStrategyToType(strategy: String?): String {
        return when (strategy?.trim()?.lowercase()) {
            "least load" -> "1"
            "random" -> "2"
            "round robin" -> "3"
            else -> "0"
        }
    }
}

