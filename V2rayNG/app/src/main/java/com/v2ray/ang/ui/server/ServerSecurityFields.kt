package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig.REALITY
import com.v2ray.ang.AppConfig.TLS
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import kotlinx.coroutines.CoroutineScope

@Composable
fun CommonStreamSecurityFields(
    state: ServerUiState,
    options: FieldOptions,
    scope: CoroutineScope,
    buildProfileItem: () -> ProfileItem
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormDropdownField(stringResource(R.string.server_lab_stream_security), state.streamSecurity, options.streamSecurityOptions, { state.streamSecurity = it })
        if (state.streamSecurity.isBlank()) return@Column
        FormTextField(stringResource(R.string.server_lab_sni), state.sni, { state.sni = it })
        FormDropdownField(stringResource(R.string.server_lab_stream_fingerprint), state.fingerPrint, options.uTlsOptions, { state.fingerPrint = it })
        if (state.streamSecurity == TLS) {
            SettingsSwitchItem(stringResource(R.string.server_lab_allow_insecure), checked = state.allowInsecure, onCheckedChange = { state.allowInsecure = it })
            FormDropdownField(stringResource(R.string.server_lab_stream_alpn), state.alpn, options.alpnOptions, { state.alpn = it })
            FormTextField(stringResource(R.string.server_lab_pinned_ca256), state.pinnedCA256, { state.pinnedCA256 = it })
        } else if (state.streamSecurity == REALITY) {
            FormTextField(stringResource(R.string.server_lab_public_key), state.publicKeyReality, { state.publicKeyReality = it })
            FormTextField(stringResource(R.string.server_lab_short_id), state.shortId, { state.shortId = it })
            FormTextField(stringResource(R.string.server_lab_spider_x), state.spiderX, { state.spiderX = it })
        }
    }
}
