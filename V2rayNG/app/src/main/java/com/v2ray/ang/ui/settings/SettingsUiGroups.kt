package com.v2ray.ang.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem

@Composable
fun UiSettingsGroup(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    CollapsiblePreferenceGroupHeader(title = stringResource(R.string.title_ui_settings), expanded = expanded, onExpandedChange = onExpandedChange)
    if (!expanded) return
    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)
    var confirmRemove by rememberMmkvBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    var doubleColumnDisplay by rememberMmkvBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    var language by rememberMmkvString(AppConfig.PREF_LANGUAGE, "auto")
    var qsTileMode by rememberMmkvString(AppConfig.PREF_QS_TILE_MODE, "0")
    var qsTileTargetGuid by rememberMmkvString(AppConfig.PREF_QS_TILE_TARGET_GUID, "")

    SettingsSwitchItem(stringResource(R.string.title_pref_speed_enabled), stringResource(R.string.summary_pref_speed_enabled), checked = speedEnabled, onCheckedChange = { speedEnabled = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_confirm_remove), stringResource(R.string.summary_pref_confirm_remove), checked = confirmRemove, onCheckedChange = { confirmRemove = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_double_column_display), stringResource(R.string.summary_pref_double_column_display), checked = doubleColumnDisplay, onCheckedChange = { doubleColumnDisplay = it; SettingsChangeManager.makeSetupGroupTab() })
    SettingsListItem(stringResource(R.string.title_language), stringArrayResource(R.array.language_select).toList(), stringArrayResource(R.array.language_select_value).toList(), selectedValue = language, onSelected = { language = it; AppLocaleManager.setApplicationLanguage(it) })
    SettingsListItem(stringResource(R.string.title_pref_qs_tile_mode), stringArrayResource(R.array.qs_tile_mode_entries).toList(), stringArrayResource(R.array.qs_tile_mode_values).toList(), selectedValue = qsTileMode, onSelected = { qsTileMode = it })

    if (qsTileMode == "1") {
        val smartPoolEntries = remember {
            val smartPoolGuids = MmkvManager.decodeServerList(SmartPoolConstants.SMART_POOL_GROUP_ID)
            val allGuids = (smartPoolGuids + MmkvManager.decodeAllServerList()).distinct()
            allGuids.mapNotNull {
                val cfg = MmkvManager.decodeServerConfig(it)
                if (cfg?.configType == EConfigType.SMART_POOL) it to cfg.remarks else null
            }
        }
        if (smartPoolEntries.isNotEmpty()) {
            val entries = smartPoolEntries.map { it.second }
            val values = smartPoolEntries.map { it.first }
            val selected = if (values.contains(qsTileTargetGuid)) qsTileTargetGuid else values.first()
            SettingsListItem(
                title = stringResource(R.string.title_pref_qs_tile_target_smartpool),
                entries = entries,
                values = values,
                selectedValue = selected,
                onSelected = { qsTileTargetGuid = it }
            )
        }
    }
}

@Composable
fun VpnSettingsGroup(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, isVpn: Boolean) {
    CollapsiblePreferenceGroupHeader(title = stringResource(R.string.title_vpn_settings), expanded = expanded, onExpandedChange = onExpandedChange)
    if (!expanded) return
    var ipv6Enabled by rememberMmkvBool(AppConfig.PREF_IPV6_ENABLED, false)
    var preferIpv6 by rememberMmkvBool(AppConfig.PREF_PREFER_IPV6, false)
    var localDns by rememberMmkvBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
    var fakeDns by rememberMmkvBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)
    var vpnDns by rememberMmkvString(AppConfig.PREF_VPN_DNS, "")
    var vpnMtu by rememberMmkvString(AppConfig.PREF_VPN_MTU, "")
    var useHevTun by rememberMmkvBool(AppConfig.PREF_USE_HEV_TUNNEL, true)

    SettingsSwitchItem(stringResource(R.string.title_pref_ipv6_enabled), stringResource(R.string.summary_pref_ipv6_enabled), checked = ipv6Enabled, onCheckedChange = { ipv6Enabled = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_prefer_ipv6), stringResource(R.string.summary_pref_prefer_ipv6), checked = preferIpv6, onCheckedChange = { preferIpv6 = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_local_dns_enabled), stringResource(R.string.summary_pref_local_dns_enabled), checked = localDns, enabled = isVpn, onCheckedChange = { localDns = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_fake_dns_enabled), stringResource(R.string.summary_pref_fake_dns_enabled), checked = fakeDns, enabled = isVpn && localDns, onCheckedChange = { fakeDns = it })
    SettingsEditItem(stringResource(R.string.title_pref_vpn_dns), value = vpnDns, enabled = isVpn && !localDns, onValueChanged = { vpnDns = it })
    SettingsEditItem(stringResource(R.string.title_pref_vpn_mtu), value = vpnMtu, enabled = isVpn, keyboardNumber = true, onValueChanged = { vpnMtu = it })
    SettingsSwitchItem(stringResource(R.string.title_pref_use_hev_tunnel), stringResource(R.string.summary_pref_use_hev_tunnel), checked = useHevTun, enabled = isVpn, onCheckedChange = { useHevTun = it })
}
