package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ServersCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class MainTestCoordinator(
    private val dataSource: MainDataSource,
    private val groupManager: MainGroupManager,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher
) {
    val testRequests = MainTestRequests()
    private var bulkTestJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    val isTesting: Boolean
        get() = testRequests.isTesting

    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        dataSource.cancelAllPing()
    }

    fun testCurrentServerRealPing(): String? {
        val requestId = testRequests.beginCurrent()
        dataSource.testCurrentServerRealPing(requestId)
        return requestId
    }

    fun startBulkPing(
        groupId: String,
        servers: List<ServersCache>,
        filteredGuids: List<String>?,
        onlyTcp: Boolean
    ): MainTestRequests.Bulk? {
        cancelAllPing()
        if (servers.isEmpty()) return null

        val serverGuids = servers.map { it.guid }
        groupManager.mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { if (it.testDelayMillis == 0L) it else it.copy(testDelayMillis = 0L) },
                rows = current.rows.map { if (it.testDelayMillis == 0L) it else it.copy(testDelayMillis = 0L) }
            )
        }
        val request = testRequests.beginBulk(groupId)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = filteredGuids ?: emptyList(),
            onlyTcp = onlyTcp
        )
        bulkTestJob = scope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                groupManager.updateCachedServerDelays(groupId) { cached ->
                    cached.map {
                        if (it.guid !in resetGuids || it.testDelayMillis == 0L) it
                        else it.copy(testDelayMillis = 0L)
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
        return request
    }

    fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = scope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(500L)
                flushPendingTestResults(request)
            }
        }
    }

    fun scheduleFinish(requestId: String, onFinished: suspend (String) -> Unit) {
        val request = testRequests.bulk?.takeIf { it.id == requestId } ?: return
        val scheduledFlush = testResultFlushJob
        testResultFlushJob = scope.launch {
            scheduledFlush?.cancelAndJoin()
            if (testRequests.bulk?.id != request.id) return@launch
            flushPendingTestResults(request)
            onFinished(request.id)
        }
    }

    fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty() || testRequests.bulk?.id != request.id) return
        val drained = pendingTestResults.toMap()
        pendingTestResults.clear()
        groupManager.updateCachedServerDelays(request.groupId) { cached ->
            applyTestDelayResults(cached, drained)
        }
        if (testRequests.bulk?.id != request.id) return
        groupManager.mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, drained),
                rows = applyTestDelayResultsToRows(current.rows, drained)
            )
        }
    }

    private fun applyTestDelayResults(
        servers: List<ServersCache>,
        updates: Map<String, Long>
    ): List<ServersCache> = servers.map { server ->
        val delayMillis = updates[server.guid]
        if (delayMillis == null || delayMillis == server.testDelayMillis) server
        else server.copy(testDelayMillis = delayMillis)
    }

    private fun applyTestDelayResultsToRows(
        rows: List<ServerRowUiModel>,
        updates: Map<String, Long>
    ): List<ServerRowUiModel> = rows.map { row ->
        val delayMillis = updates[row.guid]
        if (delayMillis == null || delayMillis == row.testDelayMillis) row
        else row.copy(testDelayMillis = delayMillis)
    }
}
