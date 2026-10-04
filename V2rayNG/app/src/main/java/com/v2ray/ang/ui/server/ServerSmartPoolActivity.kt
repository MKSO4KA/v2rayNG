package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.smartpool.SmartPoolManager
import com.v2ray.ang.smartpool.SmartPoolNodeFilter
import com.v2ray.ang.smartpool.SmartRegexMatcher
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

class ServerSmartPoolActivity : BaseComponentActivity() {
    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false) && editGuid.isNotEmpty() && editGuid == MmkvManager.getSelectServer()
    }
    private lateinit var initialConfig: ProfileItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialConfig = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.SMART_POOL)
    }

    @Composable
    override fun ScreenContent() {
        val uiState = remember { SmartPoolUiState.fromProfileItem(initialConfig) }
        val allSubs = remember { MmkvManager.decodeSubscriptions() }

        val subOptions = remember(allSubs) {
            listOf("Все группы") + allSubs.map { it.subscription.remarks }
        }

        val allNodes = remember(uiState.subscriptionId) {
            val guids = if (uiState.subscriptionId.isBlank()) {
                SmartPoolManager.getAllServerGuids()
            } else {
                MmkvManager.decodeServerList(uiState.subscriptionId)
            }
            guids.mapNotNull { MmkvManager.decodeServerConfig(it) }
                .filter { it.configType != EConfigType.SMART_POOL && it.configType != EConfigType.CUSTOM }
        }

        val matchedNodes by remember(allNodes, uiState.filterRegex) {
            derivedStateOf {
                SmartPoolNodeFilter.filterAndDeduplicate(allNodes, uiState.filterRegex)
            }
        }

        ServerEditorScaffold(
            title = stringResource(R.string.title_server),
            editGuid = editGuid,
            isRunning = isRunning,
            remarks = initialConfig.remarks,
            onBackClick = { finish() },
            onSaveClick = { saveSmartPool(uiState) },
            onDeleteClick = { deleteServer(editGuid) }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(
                    label = stringResource(R.string.server_lab_remarks),
                    value = uiState.remarks,
                    onValueChange = { uiState.remarks = it },
                    isError = uiState.isRemarksError
                )

                val policyLabels = listOf(
                    stringResource(R.string.smartpool_policy_lowest_latency),
                    stringResource(R.string.smartpool_policy_random),
                    stringResource(R.string.smartpool_policy_round_robin)
                )
                val currentPolicyLabel = when (uiState.policyType) {
                    SmartPoolUiState.POLICY_RANDOM -> stringResource(R.string.smartpool_policy_random)
                    SmartPoolUiState.POLICY_ROUND_ROBIN -> stringResource(R.string.smartpool_policy_round_robin)
                    else -> stringResource(R.string.smartpool_policy_lowest_latency)
                }
                FormDropdownField(
                    label = stringResource(R.string.smartpool_policy_type),
                    value = currentPolicyLabel,
                    options = policyLabels,
                    onValueChange = { selected ->
                        uiState.policyType = when (selected) {
                            policyLabels[1] -> SmartPoolUiState.POLICY_RANDOM
                            policyLabels[2] -> SmartPoolUiState.POLICY_ROUND_ROBIN
                            else -> SmartPoolUiState.POLICY_LOWEST_LATENCY
                        }
                    }
                )

                val currentSubName = if (uiState.subscriptionId.isBlank()) {
                    "Все группы"
                } else {
                    allSubs.firstOrNull { it.guid == uiState.subscriptionId }?.subscription?.remarks ?: "Все группы"
                }
                FormDropdownField(
                    label = stringResource(R.string.smartpool_source_group),
                    value = currentSubName,
                    options = subOptions,
                    onValueChange = { chosenName ->
                        val targetSub = allSubs.firstOrNull { it.subscription.remarks == chosenName }
                        uiState.subscriptionId = targetSub?.guid.orEmpty()
                    }
                )

                FormTextField(
                    label = stringResource(R.string.smartpool_filter_regex),
                    value = uiState.filterRegex,
                    onValueChange = {
                        uiState.filterRegex = it
                        uiState.isRegexError = it.isNotBlank() && SmartRegexMatcher.compileSafe(it) == null
                    },
                    isError = uiState.isRegexError,
                    placeholder = "^(?!.*{flag:RU})(?!.*(?i)(?:ост|left|expire)).+$",
                    supportingText = if (uiState.isRegexError) "Некорректный синтаксис регулярного выражения" else null
                )

                FormTextField(
                    label = stringResource(R.string.smartpool_interval),
                    value = uiState.interval,
                    onValueChange = { uiState.interval = it }
                )

                FormTextField(
                    label = stringResource(R.string.smartpool_tolerance),
                    value = uiState.tolerance,
                    onValueChange = { uiState.tolerance = it }
                )

                val valMethods = listOf(
                    stringResource(R.string.smartpool_val_normal_ping),
                    stringResource(R.string.smartpool_val_okhttp)
                )
                val currentValMethod = if (uiState.validationMethod == SmartPoolUiState.METHOD_OKHTTP) valMethods[1] else valMethods[0]
                FormDropdownField(
                    label = stringResource(R.string.smartpool_validation_method),
                    value = currentValMethod,
                    options = valMethods,
                    onValueChange = { selected ->
                        uiState.validationMethod = if (selected == valMethods[1]) SmartPoolUiState.METHOD_OKHTTP else SmartPoolUiState.METHOD_NORMAL_PING
                    }
                )

                FormTextField(
                    label = stringResource(R.string.smartpool_sub_update_interval),
                    value = uiState.subUpdateInterval,
                    onValueChange = { uiState.subUpdateInterval = it },
                    placeholder = "e.g. 60m"
                )

                SmartPoolMatchedList(
                    matchedNodes = matchedNodes,
                    interval = uiState.interval,
                    tolerance = uiState.tolerance
                )
            }
        }
    }

    private fun saveSmartPool(state: SmartPoolUiState): Boolean {
        state.isRemarksError = state.remarks.isBlank()
        if (state.isRemarksError) return false

        val config = state.toProfileItem(initialConfig)
        val savedGuid = MmkvManager.encodeServerConfig(editGuid, config)
        SmartPoolManager.onProxiesUpdated(state.subscriptionId)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.finishSaved(this, savedGuid, isRunning)
        return true
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
