package com.v2ray.ang.handler

import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object GistRuleProvider {

    data class GistRuleDto(
        val remarks: String?,
        val regex: String?,
        val strategy: String?,
        val tolerance: Double?
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

    fun parseBlocklistFromJson(json: String): List<String>? {
        try {
            val dtos = JsonUtil.fromJson(json, Array<GistBlockRuleDto>::class.java) ?: return null
            return dtos.mapNotNull { it.pattern?.trim()?.takeIf { p -> p.isNotEmpty() } }
        } catch (e: Exception) {
            LogUtil.e("GistRuleProvider", "Failed to parse blocklist json", e)
            return null
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

