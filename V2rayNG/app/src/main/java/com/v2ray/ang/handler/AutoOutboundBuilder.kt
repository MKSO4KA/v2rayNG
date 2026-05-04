package com.v2ray.ang.handler

import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

object AutoOutboundBuilder {

    /**
     * Replaces human-friendly shorthands in the regex with their actual unicode equivalents.
     */
    fun expandFlagShorthands(regex: String): String {
        var expanded = regex.replace("{flag}", "[\\uD83C\\uDDE6-\\uD83C\\uDDFF]{2}")
        val specificFlagPattern = Regex("\\{flag:([A-Za-z]{2})\\}")
        expanded = specificFlagPattern.replace(expanded) { matchResult ->
            val code = matchResult.groupValues[1].uppercase()
            val c1 = String(Character.toChars(0x1F1E6 + (code[0] - 'A')))
            val c2 = String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
            c1 + c2
        }
        return expanded
    }

    fun ensurePolicyGroups(subId: String) {
        if (subId.isBlank()) return
        val subItem = MmkvManager.decodeSubscription(subId) ?: return

        val localRules = subItem.autoGroupRules.filter { !it.isFromGist }.toMutableList()
        val gistUrl = subItem.autoGroupGistUrl
        
        if (!gistUrl.isNullOrBlank()) {
            val (fetchedRules, fetchedJson) = GistRuleProvider.fetchAndParseRules(gistUrl)
            if (fetchedRules != null && fetchedJson != null) {
                subItem.lastGistRulesJson = fetchedJson
                localRules.addAll(fetchedRules)
            } else if (!subItem.lastGistRulesJson.isNullOrBlank()) {
                val cachedRules = GistRuleProvider.parseRulesFromJson(subItem.lastGistRulesJson!!)
                if (cachedRules != null) localRules.addAll(cachedRules)
            }
        }
        
        subItem.autoGroupRules = localRules
        MmkvManager.encodeSubscription(subId, subItem)

        val serverList = MmkvManager.decodeServerList(subId)
        val serverConfigs = serverList.associateWith { MmkvManager.decodeServerConfig(it) }
            .filterValues { it != null } as Map<String, ProfileItem>

        val regularProxies = serverConfigs.filterValues { 
            it.configType != EConfigType.POLICYGROUP && it.configType != EConfigType.CUSTOM 
        }
        val existingPolicyGroups = serverConfigs.filterValues { it.configType == EConfigType.POLICYGROUP }

        val generatedGuids = mutableListOf<String>()

        subItem.autoGroupRules.forEach { rule ->
            val processedRegex = expandFlagShorthands(rule.regex)
            val regexPattern = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
            
            val hasMatch = regularProxies.values.any { config ->
                val searchString = "[${config.configType.name}] ${config.remarks}"
                regexPattern?.containsMatchIn(searchString) ?: searchString.contains(processedRegex, ignoreCase = true)
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
                config.policyGroupInterval = rule.interval ?: "3m"
                config.subscriptionId = subId
                config.description = "${rule.remarks} Auto Filter"

                val savedGuid = MmkvManager.encodeServerConfig(existingGuid ?: "", config)
                generatedGuids.add(savedGuid)
            }
        }

        existingPolicyGroups.forEach { (guid, config) ->
            if (!generatedGuids.contains(guid) && config.description?.endsWith("Auto Filter") == true) {
                MmkvManager.removeServer(guid)
            }
        }
    }
}

