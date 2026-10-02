package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.ReorderableGridItem
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun GroupPagerPage(
    groupId: String,
    mainViewModel: MainViewModel,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    doubleColumnDisplay: Boolean,
    searchQuery: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String, String) -> Unit,
    contentPadding: PaddingValues
) {
    val groupStateFlow = remember(groupId) { mainViewModel.serverGroupState(groupId) }
    val groupState by groupStateFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    val actions = remember(onSelectServer, onEditServer, onShareServer, onMoreServer, onRemoveServer) {
        ServerRowActions(onSelectServer, onEditServer, onShareServer, onMoreServer, onRemoveServer)
    }
    ServerListPage(
        rows = groupState.rows,
        selectedGuid = selectedGuid,
        locateTarget = locateTarget?.takeIf { it.groupId == groupId },
        canReorder = canReorder,
        doubleColumnDisplay = doubleColumnDisplay,
        groupId = groupId,
        lazyListStates = lazyListStates,
        lazyGridStates = lazyGridStates,
        actions = actions,
        onLocateHandled = { mainViewModel.onAction(MainAction.LocateHandled) },
        onMoveServer = { from, to -> mainViewModel.moveServer(groupId, from, to) },
        contentPadding = contentPadding
    )
}

@Composable
private fun ServerListPage(
    rows: List<ServerRowUiModel>,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    canReorder: Boolean,
    doubleColumnDisplay: Boolean,
    groupId: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    actions: ServerRowActions,
    onLocateHandled: () -> Unit,
    onMoveServer: (Int, Int) -> Unit,
    contentPadding: PaddingValues
) {
    if (doubleColumnDisplay) {
        val gridState = remember(groupId) { lazyGridStates.getOrPut(groupId) { LazyGridState() } }
        val reorderableGridState = if (canReorder) rememberReorderableLazyGridState(gridState) { from, to -> onMoveServer(from.index, to.index) } else null
        LocateTargetEffect(locateTarget, rows, gridState, onLocateHandled)
        LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize().verticalScrollbar(gridState), contentPadding = contentPadding) {
            itemsIndexed(items = rows, key = { _, item -> item.guid }) { _, row ->
                val content: @Composable () -> Unit = { ServerItemColumn(row, row.guid == selectedGuid, true, actions) }
                if (canReorder && reorderableGridState != null) {
                    ReorderableItem(reorderableGridState, key = row.guid) { isDragging ->
                        ReorderableGridItem(scope = this, isDragging = isDragging) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    } else {
        val listState = remember(groupId) { lazyListStates.getOrPut(groupId) { LazyListState() } }
        val reorderableState = if (canReorder) rememberReorderableLazyListState(listState) { from, to -> onMoveServer(from.index, to.index) } else null
        LocateTargetEffect(locateTarget, rows, listState, onLocateHandled)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().verticalScrollbar(listState), contentPadding = contentPadding) {
            itemsIndexed(items = rows, key = { _, item -> item.guid }) { _, row ->
                if (canReorder && reorderableState != null) {
                    ReorderableItem(reorderableState, key = row.guid) { isDragging ->
                        ReorderableListItem(scope = this, isDragging = isDragging) {
                            ServerItemRow(row, row.guid == selectedGuid, actions)
                        }
                        ItemDivider()
                    }
                } else {
                    ServerItemRow(row, row.guid == selectedGuid, actions)
                    ItemDivider()
                }
            }
        }
    }
}

