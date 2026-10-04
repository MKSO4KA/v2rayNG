package com.v2ray.ang.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.smartpool.SmartPoolConstants

class SmartPoolUiState(
    remarks: String = SmartPoolConstants.SMART_POOL_REMARKS,
    subscriptionId: String = "",
    policyType: String = POLICY_LOWEST_LATENCY,
    filterRegex: String = "",
    interval: String = "20s",
    tolerance: String = "30.0",
    validationMethod: String = METHOD_NORMAL_PING,
    subUpdateInterval: String = ""
) {
    var remarks by mutableStateOf(remarks)
    var subscriptionId by mutableStateOf(subscriptionId)
    var policyType by mutableStateOf(policyType)
    var filterRegex by mutableStateOf(filterRegex)
    var interval by mutableStateOf(interval)
    var tolerance by mutableStateOf(tolerance)
    var validationMethod by mutableStateOf(validationMethod)
    var subUpdateInterval by mutableStateOf(subUpdateInterval)

    var isRemarksError by mutableStateOf(false)
    var isRegexError by mutableStateOf(false)

    fun toProfileItem(initialConfig: ProfileItem): ProfileItem {
        return initialConfig.copy(
            configType = EConfigType.SMART_POOL,
            remarks = remarks,
            server = AppConfig.LOOPBACK,
            serverPort = SmartPoolConstants.DISPATCHER_PORT.toString(),
            subscriptionId = subscriptionId,
            smartPoolPolicyType = policyType,
            smartPoolFilterRegex = filterRegex,
            smartPoolInterval = interval,
            smartPoolTolerance = tolerance.toDoubleOrNull() ?: 30.0,
            smartPoolValidationMethod = validationMethod,
            smartPoolSubUpdateInterval = subUpdateInterval
        )
    }

    companion object {
        const val POLICY_LOWEST_LATENCY = "lowest_latency"
        const val POLICY_RANDOM = "random"
        const val POLICY_ROUND_ROBIN = "round_robin"

        const val METHOD_NORMAL_PING = "normal_ping"
        const val METHOD_OKHTTP = "okhttp"

        fun fromProfileItem(profile: ProfileItem): SmartPoolUiState {
            return SmartPoolUiState(
                remarks = profile.remarks.ifBlank { SmartPoolConstants.SMART_POOL_REMARKS },
                subscriptionId = profile.subscriptionId,
                policyType = profile.smartPoolPolicyType ?: POLICY_LOWEST_LATENCY,
                filterRegex = profile.smartPoolFilterRegex ?: "",
                interval = profile.smartPoolInterval ?: "20s",
                tolerance = (profile.smartPoolTolerance ?: 30.0).toString(),
                validationMethod = profile.smartPoolValidationMethod ?: METHOD_NORMAL_PING,
                subUpdateInterval = profile.smartPoolSubUpdateInterval ?: ""
            )
        }
    }
}
