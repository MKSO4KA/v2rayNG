package com.v2ray.ang.ui.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.v2ray.ang.dto.LocateTarget
import kotlin.math.abs

@Composable
internal fun LocateTargetEffect(target: LocateTarget?, rows: List<ServerRowUiModel>, state: LazyListState, onHandled: () -> Unit) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
internal fun LocateTargetEffect(target: LocateTarget?, rows: List<ServerRowUiModel>, state: LazyGridState, onHandled: () -> Unit) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

internal suspend fun PagerState.navigateToPageOptimized(targetPage: Int, animateAdjacentPage: Boolean = true) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    val current = settledPage.coerceIn(0, pageCount - 1)
    if (target == current) return
    if (abs(target - current) == 1 && animateAdjacentPage) animateScrollToPage(target)
    else scrollToPage(target)
}
