package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.isComplexType

internal class MainServerOperations(
    private val dataSource: MainDataSource
) {
    suspend fun importBatchConfig(
        configText: String,
        selectedGroupId: String
    ): Pair<Int, Int> = dataSource.importBatchConfig(configText, selectedGroupId, true)

    fun updateSubscriptions(subId: String): SubscriptionUpdateResult =
        if (subId.isEmpty()) {
            dataSource.updateConfigViaSubAll()
        } else {
            val item = dataSource.getSubscriptionItem(subId)
                ?: return SubscriptionUpdateResult(0, 0, 0, 0)
            dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
        }

    fun exportConfigs(selectedGroupId: String, servers: List<ServersCache>): Int {
        val guids = if (selectedGroupId.isEmpty()) {
            dataSource.getServerGuidList("")
        } else {
            servers.map { it.guid }
        }
        return dataSource.shareNonCustomConfigsToClipboard(guids)
    }

    fun removeAllServers(selectedGroupId: String, servers: List<ServersCache>): Int =
        if (selectedGroupId.isEmpty()) {
            dataSource.removeAllServer()
        } else {
            servers.forEach { dataSource.removeServer(it.guid) }
            servers.size
        }

    fun removeDuplicateServers(servers: List<ServersCache>): Int {
        val seen = HashSet<ProfileItem>()
        val duplicates = ArrayList<String>()
        servers.forEach { server ->
            val profile = server.profile
            if (!profile.configType.isComplexType()) {
                val identity = profile.duplicateIdentity()
                if (!seen.add(identity)) duplicates += server.guid
            }
        }
        duplicates.forEach { dataSource.removeServer(it) }
        return duplicates.size
    }

    fun removeInvalidServers(selectedGroupId: String, servers: List<ServersCache>): Int {
        val visibleServersOnly = selectedGroupId.isNotEmpty()
        return if (visibleServersOnly) {
            servers.sumOf { dataSource.removeInvalidServerByGuid(it.guid) }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    fun sortByTestResults(selectedGroupId: String) {
        val subs = if (selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }
}
