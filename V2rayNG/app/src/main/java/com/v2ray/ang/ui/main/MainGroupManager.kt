package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class MainGroupManager(
    private val dataSource: MainDataSource,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val preloadDispatcher: CoroutineDispatcher
) {
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private var preloadJob: Job? = null

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    fun currentServers(groupId: String): List<ServersCache> =
        mutableServerGroupState(groupId).value.servers

    suspend fun clearCache() {
        cacheMutex.withLock { groupDataCache.clear() }
    }

    suspend fun cacheSnapshot(): Map<String, List<ServersCache>> =
        cacheMutex.withLock { groupDataCache.toMap() }

    suspend fun updateGroupDataCache(groupId: String, servers: List<ServersCache>) {
        cacheMutex.withLock { groupDataCache[groupId] = servers }
    }

    suspend fun getCachedServers(groupId: String): List<ServersCache>? =
        cacheMutex.withLock { groupDataCache[groupId] }

    suspend fun updateCachedServerDelays(
        groupId: String,
        transform: (List<ServersCache>) -> List<ServersCache>
    ) {
        cacheMutex.withLock {
            groupDataCache[groupId]?.let {
                groupDataCache[groupId] = transform(it)
            }
        }
    }

    fun retainValidGroups(validIds: Set<String>) {
        groupUiFlows.keys.removeAll { it !in validIds }
        groupLoadMutexes.keys.removeAll { it !in validIds }
    }

    suspend fun loadGroup(groupId: String, forceRefresh: Boolean = false): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    fun resolveSelectedGroup(groups: List<GroupMapItem>, current: String): String {
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    fun startRadialPreload(
        groups: List<GroupMapItem>,
        selectedGroup: String,
        forceRefresh: Boolean,
        onGroupLoaded: (String, List<ServersCache>) -> Unit
    ) {
        preloadJob?.cancel()
        val selectedIndex = groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
        val preloadOrder = radialPreloadOrder(groups, selectedIndex)
        preloadJob = scope.launch(preloadDispatcher) {
            preloadOrder.forEach { groupId ->
                ensureActive()
                delay(32)
                val servers = loadGroup(groupId, forceRefresh)
                onGroupLoaded(groupId, servers)
            }
        }
    }

    fun cancelPreload() {
        preloadJob?.cancel()
        preloadJob = null
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }
}
