package com.v2ray.ang.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem

@Composable
fun CoreSettingsGroup(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    CollapsiblePreferenceGroupHeader(title = stringResource(R.string.title_core_settings), expanded = expanded, onExpandedChange = onExpandedChange)
    if (!expanded) return
    var sniffingEnabled by rememberMmkvBool(AppConfig.PREF_SNIFFING_ENABLED, true)
    var socksPort by rememberMmkvString(AppConfig.PREF_SOCKS_PORT, "")
    var remoteDns by rememberMmkvString(AppConfig.PREF_REMOTE_DNS, "")
    var domesticDns by rememberMmkvString(AppConfig.PREF_DOMESTIC_DNS, "")

    SettingsSwitchItem(stringResource(R.string.title_pref_sniffing_enabled), stringResource(R.string.summary_pref_sniffing_enabled), checked = sniffingEnabled, onCheckedChange = { sniffingEnabled = it })
    SettingsEditItem(stringResource(R.string.title_pref_socks_port), value = socksPort, keyboardNumber = true, onValueChanged = { socksPort = it })
    SettingsEditItem(stringResource(R.string.title_pref_remote_dns), value = remoteDns, onValueChanged = { remoteDns = it })
    SettingsEditItem(stringResource(R.string.title_pref_domestic_dns), value = domesticDns, onValueChanged = { domesticDns = it })
}

@Composable
fun AdvancedSettingsGroup(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, systemVpnSettingsAvailable: Boolean, onSystemVpnSettingsClicked: () -> Unit) {
    CollapsiblePreferenceGroupHeader(title = stringResource(R.string.title_advanced), expanded = expanded, onExpandedChange = onExpandedChange)
    if (!expanded) return
    var isBooted by rememberMmkvBool(AppConfig.PREF_IS_BOOTED, false)
    var delayTestUrl by rememberMmkvString(AppConfig.PREF_DELAY_TEST_URL, "")

    SettingsSwitchItem(stringResource(R.string.title_pref_is_booted), stringResource(R.string.summary_pref_is_booted), checked = isBooted, onCheckedChange = { isBooted = it })
    if (systemVpnSettingsAvailable) {
        SettingsMenuItem(stringResource(R.string.title_system_vpn_settings), subtitle = stringResource(R.string.summary_system_vpn_settings), onClick = onSystemVpnSettingsClicked)
    }
    SettingsEditItem(stringResource(R.string.title_pref_delay_test_url), value = delayTestUrl, onValueChanged = { delayTestUrl = it })
}

@Composable
fun ModeSettingsGroup(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onModeHelpClicked: () -> Unit) {
    CollapsiblePreferenceGroupHeader(title = stringResource(R.string.title_mode_settings), expanded = expanded, onExpandedChange = onExpandedChange)
    if (!expanded) return
    var mode by rememberMmkvString(AppConfig.PREF_MODE, AppConfig.VPN)
    SettingsListItem(stringResource(R.string.title_mode), stringArrayResource(R.array.mode_entries).toList(), stringArrayResource(R.array.mode_value).toList(), selectedValue = mode, onSelected = { mode = it })
    SettingsMenuItem(stringResource(R.string.title_mode_help), onClick = onModeHelpClicked)
}
