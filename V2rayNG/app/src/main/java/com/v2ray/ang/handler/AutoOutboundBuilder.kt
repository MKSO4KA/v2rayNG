package com.v2ray.ang.handler

import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

object AutoOutboundBuilder {

    /**
     * Replaces human-friendly shorthands in the regex with their actual unicode equivalents.
     * - {flag} becomes a regex character class matching any country flag emoji.
     * - {flag:XX} becomes the exact unicode flag string for that country code (e.g. {flag:RU} -> 🇷🇺).
     */
    private fun expandFlagShorthands(regex: String): String {
        // General flag matches any 2 Regional Indicator Symbols (the unicode block for flags)
        var expanded = regex.replace("{flag}", "[\uD83C\uDDE6-\uD83C\uDDFF]{2}")
        
        // Specific flag matches a specific country ISO code, e.g. {flag:RU}
        val specificFlagPattern = Regex("\\{flag:([A-Za-z]{2})\\}")
        expanded = specificFlagPattern.replace(expanded) { matchResult ->
            val code = matchResult.groupValues[1].uppercase()
            // Regional Indicator symbols start at U+1F1E6 ('A')
            val c1 = String(Character.toChars(0x1F1E6 + (code[0] - 'A')))
            val c2 = String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
            c1 + c2
        }
        return expanded
    }

    /**
     * Ensures the dynamic Policy Groups are created, updated, or removed based on the subscription's autoGroupRules.
     * Integrates Gist synchronization to safely merge external rules with local rules.
     */
    fun ensurePolicyGroups(subId: String) {
        if (subId.isBlank()) return
        val subItem = MmkvManager.decodeSubscription(subId) ?: return

        // 1. Handle Gist Synchronization (Run synchronously as this is often called on a background thread already)
        val localRules = subItem.autoGroupRules.filter { !it.isFromGist }.toMutableList()
        val gistUrl = subItem.autoGroupGistUrl
        
        if (!gistUrl.isNullOrBlank()) {
            val (fetchedRules, fetchedJson) = GistRuleProvider.fetchAndParseRules(gistUrl)
            if (fetchedRules != null && fetchedJson != null) {
                subItem.lastGistRulesJson = fetchedJson
                localRules.addAll(fetchedRules)
            } else if (!subItem.lastGistRulesJson.isNullOrBlank()) {
                // Network failed or Gist was unreachable -> Fallback to the last cached JSON
                val cachedRules = GistRuleProvider.parseRulesFromJson(subItem.lastGistRulesJson!!)
                if (cachedRules != null) {
                    localRules.addAll(cachedRules)
                }
            }
        }
        
        subItem.autoGroupRules = localRules
        MmkvManager.encodeSubscription(subId, subItem)

        // 2. Scan proxies and apply rules
        val serverList = MmkvManager.decodeServerList(subId)
        val serverConfigs = serverList.associateWith { MmkvManager.decodeServerConfig(it) }
            .filterValues { it != null } as Map<String, ProfileItem>

        val regularProxies = serverConfigs.filterValues { 
            it.configType != EConfigType.POLICYGROUP && it.configType != EConfigType.CUSTOM 
        }
        val existingPolicyGroups = serverConfigs.filterValues { it.configType == EConfigType.POLICYGROUP }

        val generatedGuids = mutableListOf<String>()

        subItem.autoGroupRules.forEach { rule ->
            // Apply the flag shorthand expansion
            val processedRegex = expandFlagShorthands(rule.regex)

            // Check if there is at least one proxy matching the regex
            val regexPattern = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
            val hasMatch = regularProxies.values.any { config ->
                // Expand search space to include protocol type for more powerful regex matching
                val searchString = "[${config.configType.name}] ${config.remarks}"
                regexPattern?.containsMatchIn(searchString) ?: searchString.contains(processedRegex)
            }

            if (hasMatch) {
                var existingGuid: String? = null
                var existingConfig: ProfileItem? = null

                for ((guid, config) in existingPolicyGroups) {
                    if (config.remarks == rule.remarks && config.policyGroupFilter == processedRegex) {
                        existingGuid = guid
                        existingConfig = config
                        break
                    }
                }

                val config = existingConfig ?: ProfileItem.create(EConfigType.POLICYGROUP)
                config.remarks = rule.remarks
                config.policyGroupType = rule.type
                config.policyGroupSubscriptionId = subId
                config.policyGroupFilter = processedRegex
                config.policyGroupTolerance = rule.tolerance ?: 50.0
                config.subscriptionId = subId
                config.description = "${rule.remarks} Auto Filter"

                val guidToSave = existingGuid ?: ""
                val savedGuid = MmkvManager.encodeServerConfig(guidToSave, config)
                generatedGuids.add(savedGuid)
            }
        }

        // 3. Remove orphaned auto-generated policy groups
        existingPolicyGroups.forEach { (guid, config) ->
            if (!generatedGuids.contains(guid)) {
                if (config.description?.endsWith("Auto Filter") == true) {
                    MmkvManager.removeServer(guid)
                }
            }
        }
    }
}

