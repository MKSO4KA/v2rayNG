package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

data class FieldOptions(
    val networkOptions: List<String>,
    val tcpHeaderOptions: List<String>,
    val kcpHeaderOptions: List<String>,
    val grpcModeOptions: List<String>,
    val xhttpModeOptions: List<String>,
    val streamSecurityOptions: List<String>,
    val uTlsOptions: List<String>,
    val alpnOptions: List<String>,
    val browserDialerOptions: List<String>
)

@Composable
fun CommonBasicFields(state: ServerUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(stringResource(R.string.server_lab_remarks), state.remarks, { state.remarks = it }, isError = state.isRemarksError)
        FormTextField(stringResource(R.string.server_lab_address), state.address, { state.address = it }, isError = state.isAddressError)
        FormTextField(stringResource(R.string.server_lab_port), state.port, { state.port = it }, keyboardType = KeyboardType.Number, isError = state.isPortError)
    }
}

@Composable
fun CommonNetworkFields(state: ServerUiState, options: FieldOptions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FormDropdownField(stringResource(R.string.server_lab_network), state.network, options.networkOptions, { state.network = it })
        val headerOptions = when (state.network) {
            NetworkType.TCP.type -> options.tcpHeaderOptions
            NetworkType.KCP.type -> options.kcpHeaderOptions
            NetworkType.GRPC.type -> options.grpcModeOptions
            NetworkType.XHTTP.type -> options.xhttpModeOptions
            else -> listOf("---")
        }
        if (headerOptions.size > 1) {
            val label = when (state.network) {
                NetworkType.GRPC.type -> R.string.server_lab_mode_type
                NetworkType.XHTTP.type -> R.string.server_lab_xhttp_mode
                else -> R.string.server_lab_head_type
            }
            val curVal = when (state.network) {
                NetworkType.GRPC.type -> state.mode
                NetworkType.XHTTP.type -> state.xhttpMode
                else -> state.headerType
            }
            FormDropdownField(stringResource(label), curVal, headerOptions, {
                when (state.network) {
                    NetworkType.GRPC.type -> state.mode = it
                    NetworkType.XHTTP.type -> state.xhttpMode = it
                    else -> state.headerType = it
                }
            })
        }
        FormTextField(stringResource(R.string.server_lab_request_host6), if (state.network == NetworkType.GRPC.type) state.authority else state.host, { if (state.network == NetworkType.GRPC.type) state.authority = it else state.host = it })
        if (state.network != NetworkType.KCP.type) {
            FormTextField(stringResource(R.string.server_lab_path), if (state.network == NetworkType.GRPC.type) state.serviceName else state.path, { if (state.network == NetworkType.GRPC.type) state.serviceName = it else state.path = it })
        }
        if (state.network == NetworkType.XHTTP.type) {
            FormTextField(stringResource(R.string.server_lab_xhttp_extra), state.xhttpExtra, { state.xhttpExtra = it })
        }
        FormTextField(stringResource(R.string.server_lab_final_mask), state.finalMask, { state.finalMask = it })
    }
}
