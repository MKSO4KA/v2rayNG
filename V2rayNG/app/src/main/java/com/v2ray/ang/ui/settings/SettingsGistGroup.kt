package com.v2ray.ang.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.smartpool.gist.GistSyncManager
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import kotlinx.coroutines.launch

@Composable
fun GistSettingsGroup(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    CollapsiblePreferenceGroupHeader(
        title = stringResource(R.string.title_gist_settings),
        expanded = expanded,
        onExpandedChange = onExpandedChange
    )
    if (!expanded) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSyncing by remember { mutableStateOf(false) }

    var rulesUrl by rememberMmkvString(AppConfig.PREF_GIST_RULES_URL, "")
    var poolUrl by rememberMmkvString(AppConfig.PREF_GIST_POOL_URL, "")
    var blacklistUrl by rememberMmkvString(AppConfig.PREF_GIST_BLACKLIST_URL, "")
    var autoSync by rememberMmkvBool(AppConfig.PREF_GIST_AUTO_SYNC_ENABLED, true)

    SettingsEditItem(
        title = stringResource(R.string.title_pref_gist_rules_url),
        value = rulesUrl,
        onValueChanged = { rulesUrl = it }
    )
    SettingsEditItem(
        title = stringResource(R.string.title_pref_gist_pool_url),
        value = poolUrl,
        onValueChanged = { poolUrl = it }
    )
    SettingsEditItem(
        title = stringResource(R.string.title_pref_gist_blacklist_url),
        value = blacklistUrl,
        onValueChanged = { blacklistUrl = it }
    )
    SettingsSwitchItem(
        title = stringResource(R.string.title_pref_gist_auto_sync),
        summary = stringResource(R.string.summary_pref_gist_auto_sync),
        checked = autoSync,
        onCheckedChange = { autoSync = it }
    )

    Button(
        onClick = {
            if (isSyncing) return@Button
            isSyncing = true
            coroutineScope.launch {
                val ports = (0 until 9).map { SmartPoolConstants.BASE_POOL_PORT + it }
                val result = GistSyncManager.syncAllFromGist(ports)
                isSyncing = false
                if (result.success) {
                    context.toastSuccess(R.string.toast_gist_sync_success)
                } else if (result.hasAnyData) {
                    context.toast(R.string.toast_gist_sync_partial)
                } else {
                    context.toast(R.string.toast_gist_sync_failed)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        enabled = !isSyncing
    ) {
        Text(text = if (isSyncing) "Syncing..." else stringResource(R.string.action_gist_sync_now))
    }
}
