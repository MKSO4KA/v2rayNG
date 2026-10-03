package com.v2ray.ang.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.smartpool.SmartPoolManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application, private val dataSource: MainDataSource) : BaseViewModel(application) {
    private val ioDispatcher = Dispatchers.IO
    private val defaultDispatcher = Dispatchers.Default
    private val preloadDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val groupManager = MainGroupManager(dataSource, viewModelScope, ioDispatcher, preloadDispatcher)
    private val operations = MainServerOperations(dataSource)
    private val actionHandler = MainActionHandler(dataSource, groupManager, operations, ioDispatcher)
    private val testCoordinator = MainTestCoordinator(dataSource, groupManager, viewModelScope, ioDispatcher)

    private val _uiState = MutableStateFlow(MainUiState(selectedGroupId = dataSource.getSelectedSubscriptionId(), selectedGuid = dataSource.getSelectServer(), confirmRemove = dataSource.getConfirmRemove(), doubleColumnDisplay = dataSource.getDoubleColumnDisplay()))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    @Volatile private var keywordFilter: String = ""
    private var filterJob: Job? = null
    private var setupGroupJob: Job? = null
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val initialPageReady = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                when (event) {
                    MainServiceEvent.StateRunning -> updateRunningState(true, clearTestingText = false)
                    MainServiceEvent.StateNotRunning -> updateRunningState(false, clearTestingText = false)
                    MainServiceEvent.StateStartSuccess -> { toastSuccess(R.string.toast_services_success); updateRunningState(true) }
                    is MainServiceEvent.StateStartFailure -> { toastError(event.message?.ifBlank { null } ?: dataSource.getString(R.string.toast_services_failure)); updateRunningState(false) }
                    MainServiceEvent.StateStopSuccess -> updateRunningState(false)
                    is MainServiceEvent.MeasureDelayResult -> if (uiState.value.isRunning && testCoordinator.testRequests.completeCurrent(event.requestId)) _uiState.update { it.copy(isTesting = testCoordinator.isTesting, status = MainStatus.ConnectionTest(event.result)) }
                    is MainServiceEvent.MeasureConfigSuccess -> testCoordinator.testRequests.bulk?.takeIf { it.id == event.requestId }?.let { testCoordinator.queueTestResult(event.result, it) }
                    is MainServiceEvent.MeasureConfigNotify -> if (event.requestId == testCoordinator.testRequests.bulk?.id) _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                    is MainServiceEvent.MeasureConfigFinish -> testCoordinator.scheduleFinish(event.requestId) { resetTestStatus(); viewModelScope.launch(ioDispatcher) { groupManager.clearCache(); setupGroupTab(true) } }
                    is MainServiceEvent.MeasureDelayCancelled, is MainServiceEvent.MeasureConfigCancelled -> resetTestStatus()
                }
            }
        }
        setupGroupTab()
    }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> = groupManager.serverGroupState(groupId)
    fun getSubscriptions(): List<SubscriptionCache> = groupManager.getSubscriptions()

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(R.string.connection_running_task_left, status.progress)
        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank { dataSource.getString(R.string.connection_test_empty_message) }
            dataSource.getString(R.string.connection_test_error, detail)
        }
        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) return status
        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    fun refreshUiSettings() {
        _uiState.update {
            it.copy(confirmRemove = dataSource.getConfirmRemove(), doubleColumnDisplay = dataSource.getDoubleColumnDisplay())
        }
    }

    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = groupManager.mutableServerGroupState(groupId)
            groupState.map { it.servers }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = groupState.value.servers
            )
        }

    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> viewModelScope.launch(preloadDispatcher) {
                SmartPoolManager.ensureDefaultSmartPoolNode()
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
            }
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            MainAction.TestRealAllServers -> testAllRealPing(false)
            MainAction.CancelTesting -> { testCoordinator.cancelAllPing(); resetTestStatus() }
            MainAction.RemoveAllServers -> launchLoading { actionHandler.removeAll(uiState.value.selectedGroupId) { setupGroupTab(true) } }
            MainAction.RemoveDuplicateServers -> launchLoading { actionHandler.removeDuplicate(uiState.value.selectedGroupId) { setupGroupTab(true) } }
            MainAction.RemoveInvalidServers -> launchLoading { actionHandler.removeInvalid(uiState.value.selectedGroupId) { setupGroupTab(true) } }
            MainAction.SortByTestResults -> launchLoading { actionHandler.sortServers(uiState.value.selectedGroupId) { setupGroupTab(true) } }
            MainAction.UpdateSubscriptions -> launchLoading {
                actionHandler.updateSubs(uiState.value.selectedGroupId) { msg, refresh ->
                    SmartPoolManager.onProxiesUpdated(uiState.value.selectedGroupId)
                    toast(msg)
                    if (refresh) setupGroupTab(true)
                }
            }
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> launchLoading { actionHandler.importBatch(action.configText, uiState.value.selectedGroupId, { setupGroupTab(true) }, { toastError(R.string.toast_failure) }) }
            MainAction.LocateHandled -> _uiState.update { it.copy(locateTarget = null) }
            is MainAction.ShareQRCode -> _uiState.update { it.copy(shareQRCodeBitmap = dataSource.share2QRCode(action.guid)) }
            MainAction.DismissQRCodeDialog -> _uiState.update { it.copy(shareQRCodeBitmap = null) }
            else -> {}
        }
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        return viewModelScope.launch(ioDispatcher) {
            if (forceRefresh) groupManager.clearCache()
            val groups = groupManager.getSubscriptions().map { GroupMapItem(it.guid, it.subscription.remarks) }
            val selectedGroup = groupManager.resolveSelectedGroup(groups, uiState.value.selectedGroupId)
            _uiState.update { it.copy(groups = groups, selectedGroupId = selectedGroup, selectedGuid = dataSource.getSelectServer()) }
            val servers = groupManager.loadGroup(selectedGroup, forceRefresh)
            updateGroupUi(selectedGroup, servers)
            if (!initialPageReady.isCompleted) initialPageReady.complete(Unit)
        }.also { setupGroupJob = it }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filtered = if (keywordFilter.isEmpty()) servers else servers.filter { it.profile.remarks.contains(keywordFilter, true) }
        groupManager.mutableServerGroupState(groupId).value = ServerGroupUiState(filtered, filtered.map { buildServerRowUiModel(it, "") })
    }

    fun subscriptionIdChanged(id: String) {
        dataSource.setSelectedSubscriptionId(id)
        _uiState.update { it.copy(selectedGroupId = id) }
        viewModelScope.launch(ioDispatcher) { updateGroupUi(id, groupManager.loadGroup(id)) }
    }

    fun updateSelectedGuid(guid: String) { dataSource.setSelectServer(guid); _uiState.update { it.copy(selectedGuid = guid) } }
    fun refreshSelectedGuid() { _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) } }
    fun removeServerAndRefresh(guid: String) = viewModelScope.launch(ioDispatcher) { dataSource.removeServer(guid); groupManager.clearCache(); setupGroupTab(true) }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        viewModelScope.launch(ioDispatcher) { updateGroupUi(groupId, groupManager.loadGroup(groupId, forceRefresh = true)) }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, groupManager.loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, groupManager.loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            _uiState.update { it.copy(selectedGroupId = groupId, locateTarget = LocateTarget(groupId, selected)) }
        }
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val reqId = testCoordinator.testCurrentServerRealPing()
        if (reqId != null) {
            _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        }
    }

    fun moveServer(groupId: String, from: Int, to: Int) {
        val state = groupManager.mutableServerGroupState(groupId).value
        val srvs = state.servers.toMutableList()
        if (!srvs.moveItem(from, to)) return
        val rows = state.rows.toMutableList().apply { moveItem(from, to) }
        groupManager.mutableServerGroupState(groupId).value = ServerGroupUiState(srvs, rows)
        viewModelScope.launch(ioDispatcher) { dataSource.encodeServerList(srvs.map { it.guid }, groupId) }
    }

    private fun testAllRealPing(onlyTcp: Boolean) {
        val servers = groupManager.currentServers(uiState.value.selectedGroupId)
        if (testCoordinator.startBulkPing(uiState.value.selectedGroupId, servers, null, onlyTcp) != null) _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
    }

    private fun resetTestStatus() = _uiState.update { it.copy(isTesting = testCoordinator.isTesting, status = if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected) }
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) = _uiState.update { it.copy(isRunning = running, isTesting = testCoordinator.isTesting, status = if (running) MainStatus.Connected else MainStatus.Disconnected) }
    fun filterConfig(keyword: String) { keywordFilter = keyword; updateGroupUi(uiState.value.selectedGroupId, groupManager.currentServers(uiState.value.selectedGroupId)) }

    class Factory(private val app: Application, private val ds: MainDataSource) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app, ds) as T
    }
}
