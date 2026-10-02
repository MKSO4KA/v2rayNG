package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class MainActionHandler(
    private val dataSource: MainDataSource,
    private val groupManager: MainGroupManager,
    private val operations: MainServerOperations,
    private val ioDispatcher: CoroutineDispatcher
) {
    suspend fun importBatch(configText: String, groupId: String, onSuccess: () -> Unit, onError: () -> Unit) = withContext(ioDispatcher) {
        try {
            val (count, countSub) = operations.importBatchConfig(configText, groupId)
            if (count > 0 || countSub > 0) onSuccess() else onError()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogUtil.e(AppConfig.TAG, "Import batch failed", e)
            onError()
        }
    }

    suspend fun updateSubs(groupId: String, onResult: (String, Boolean) -> Unit) = withContext(ioDispatcher) {
        try {
            val result = operations.updateSubscriptions(groupId)
            val msg = dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount)
            onResult(msg, result.configCount > 0)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LogUtil.e(AppConfig.TAG, "Sub update failed", e)
        }
    }

    suspend fun removeAll(groupId: String, onFinish: (Int) -> Unit) = withContext(ioDispatcher) {
        val count = operations.removeAllServers(groupId, groupManager.currentServers(groupId))
        groupManager.clearCache()
        onFinish(count)
    }

    suspend fun removeDuplicate(groupId: String, onFinish: (Int) -> Unit) = withContext(ioDispatcher) {
        val count = operations.removeDuplicateServers(groupManager.currentServers(groupId))
        onFinish(count)
    }

    suspend fun removeInvalid(groupId: String, onFinish: (Int) -> Unit) = withContext(ioDispatcher) {
        val count = operations.removeInvalidServers(groupId, groupManager.currentServers(groupId))
        groupManager.clearCache()
        onFinish(count)
    }

    suspend fun sortServers(groupId: String, onFinish: () -> Unit) = withContext(ioDispatcher) {
        operations.sortByTestResults(groupId)
        groupManager.clearCache()
        onFinish()
    }
}
