package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.verticalScrollbar

@Composable
fun ServerEditorScaffold(
    title: String,
    editGuid: String,
    isRunning: Boolean,
    remarks: String,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = title,
                onBackClick = onBackClick,
                actions = {
                    if (editGuid.isNotEmpty() && !isRunning) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = onSaveClick) {
                        Icon(painterResource(R.drawable.ic_fab_check), stringResource(R.string.acc_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding().verticalScroll(scrollState).verticalScrollbar(scrollState).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
            NavigationBarsSpacer()
        }
    }
    if (showDeleteDialog) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_profile),
            itemName = remarks,
            onConfirm = { showDeleteDialog = false; onDeleteClick() },
            onDismiss = { showDeleteDialog = false }
        )
    }
}
