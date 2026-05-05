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

    /**
     * Determines if a profile is eligible to be probed and matched by the balancer.
     */
    fun isValidForAutoGroup(profile: ProfileItem): Boolean {
        val hasServer = profile.configType == EConfigType.CUSTOM || !profile.server.isNullOrEmpty()
        return hasServer && profile.configType != EConfigType.POLICYGROUP
    }

    /**
     * Determines if a profile matches the regex filter.
     */
    fun matchProfile(profile: ProfileItem, processedRegex: String, regex: Regex?): Boolean {
        val searchString = "[${profile.configType.name}] ${profile.remarks}"
        return regex?.containsMatchIn(searchString) ?: searchString.contains(processedRegex, ignoreCase = true)
    }

    /**
     * Centralized logic to get the exact, filtered list of proxy pairs (GUID -> ProfileItem) 
     * that will be fed into the core balancer. This ensures 100% consistency across the UI, 
     * Config Generator, and Notification Manager.
     */
    fun getFilteredRoutingProxies(filter: String?, targetSubId: String? = null): List<Pair<String, ProfileItem>> {
        val serverList = MmkvManager.decodeAllServerList()
        return serverList.mapNotNull {
            val profile = MmkvManager.decodeServerConfig(it)
            if (profile != null) Pair(it, profile) else null
        }
        .filter { isValidForAutoGroup(it.second) }
        .filter { 
            if (it.second.configType == EConfigType.CUSTOM) true
            else !Utils.isPureIpAddress(it.second.server!!) || Utils.isValidUrl(it.second.server!!) 
        }
        .filter { 
            if (targetSubId.isNullOrBlank()) true else it.second.subscriptionId == targetSubId
        }
        .filter { 
            if (filter.isNullOrBlank()) true
            else {
                val expanded = expandFlagShorthands(filter)
                val regex = try { Regex(expanded, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
                matchProfile(it.second, expanded, regex)
            }
        }
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

        val regularProxies = serverConfigs.filterValues { isValidForAutoGroup(it) }
        val existingPolicyGroups = serverConfigs.filterValues { it.configType == EConfigType.POLICYGROUP }

        val generatedGuids = mutableListOf<String>()

        subItem.autoGroupRules.forEach { rule ->
            val processedRegex = expandFlagShorthands(rule.regex)
            val regexPattern = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
            
            val hasMatch = regularProxies.values.any { config ->
                matchProfile(config, processedRegex, regexPattern)
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

