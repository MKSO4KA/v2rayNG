package com.v2ray.ang.handler

import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object AutoOutboundBuilder {

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
            // Check if there is at least one proxy matching the regex
            val regexPattern = try { Regex(rule.regex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
            val hasMatch = regularProxies.values.any { config ->
                regexPattern?.containsMatchIn(config.remarks) ?: config.remarks.contains(rule.regex)
            }

            if (hasMatch) {
                var existingGuid: String? = null
                var existingConfig: ProfileItem? = null

                for ((guid, config) in existingPolicyGroups) {
                    if (config.remarks == rule.remarks && config.policyGroupFilter == rule.regex) {
                        existingGuid = guid
                        existingConfig = config
                        break
                    }
                }

                val config = existingConfig ?: ProfileItem.create(EConfigType.POLICYGROUP)
                config.remarks = rule.remarks
                config.policyGroupType = rule.type
                config.policyGroupSubscriptionId = subId
                config.policyGroupFilter = rule.regex
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

