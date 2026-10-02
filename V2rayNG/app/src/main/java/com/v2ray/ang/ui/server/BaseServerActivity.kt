package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import kotlinx.coroutines.CoroutineScope

abstract class BaseServerActivity : BaseComponentActivity() {
    protected abstract val serverConfigType: EConfigType
    protected val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    protected val isRunning by lazy { intent.getBooleanExtra("isRunning", false) && editGuid.isNotEmpty() && editGuid == MmkvManager.getSelectServer() }
    protected val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }
    protected lateinit var initialConfig: ProfileItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialConfig = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(serverConfigType)
    }

    @Composable
    protected fun rememberFieldOptions(): FieldOptions = FieldOptions(
        networkOptions = stringArrayResource(R.array.networks).toList(),
        tcpHeaderOptions = stringArrayResource(R.array.header_type_tcp).toList(),
        kcpHeaderOptions = stringArrayResource(R.array.header_type_kcp_and_quic).toList(),
        grpcModeOptions = stringArrayResource(R.array.mode_type_grpc).toList(),
        xhttpModeOptions = stringArrayResource(R.array.xhttp_mode).toList(),
        streamSecurityOptions = stringArrayResource(R.array.streamsecurityxs).toList(),
        uTlsOptions = stringArrayResource(R.array.streamsecurity_utls).toList(),
        alpnOptions = stringArrayResource(R.array.streamsecurity_alpn).toList(),
        browserDialerOptions = stringArrayResource(R.array.browser_dialer_mode_value).toList()
    )

    @Composable
    protected fun CommonBasicFields(state: ServerUiState) = com.v2ray.ang.ui.server.CommonBasicFields(state)
    @Composable
    protected fun CommonNetworkFields(state: ServerUiState, options: FieldOptions) = com.v2ray.ang.ui.server.CommonNetworkFields(state, options)
    @Composable
    protected fun CommonStreamSecurityFields(state: ServerUiState, options: FieldOptions, scope: CoroutineScope, buildProfileItem: () -> ProfileItem) =
        com.v2ray.ang.ui.server.CommonStreamSecurityFields(state, options, scope, buildProfileItem)

    protected fun validateBasicConfig(state: ServerUiState): Boolean {
        state.isRemarksError = state.remarks.isBlank()
        state.isAddressError = state.address.isBlank()
        state.isPortError = state.configType != EConfigType.HYSTERIA2 && (state.port.toIntOrNull() ?: 0) <= 0
        return !state.isRemarksError && !state.isAddressError && !state.isPortError
    }

    protected open fun validateProtocolConfig(config: ProfileItem): Boolean = true
    protected open fun validateCommonConfig(state: ServerUiState, config: ProfileItem): Boolean {
        if (config.password.isNullOrBlank()) {
            state.isPasswordError = true
            return false
        }
        return true
    }

    protected fun saveServer(state: ServerUiState): Boolean {
        if (!validateBasicConfig(state)) return false
        val config = state.toProfileItem(initialConfig)
        if (!validateCommonConfig(state, config)) return false
        if (!validateProtocolConfig(config)) return false
        config.description = AngConfigManager.generateDescription(config)
        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) config.subscriptionId = subscriptionId.orEmpty()
        val savedGuid = MmkvManager.encodeServerConfig(editGuid, config)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.finishSaved(this, savedGuid, isRunning)
        return true
    }

    @Composable
    protected fun ServerEditorScaffold(title: String, onSaveClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        ServerEditorScaffold(
            title = title, editGuid = editGuid, isRunning = isRunning, remarks = initialConfig.remarks,
            onBackClick = { finish() }, onSaveClick = onSaveClick, onDeleteClick = { deleteServer(editGuid) }, content = content
        )
    }

    private fun deleteServer(guid: String) {
        if (guid.isEmpty() || guid == MmkvManager.getSelectServer()) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        MmkvManager.removeServer(guid)
        ProfileEditorResult.finishDeleted(this, guid)
    }
}

