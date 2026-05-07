package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType

object QuickTileManager {
    fun resolveQsTileTarget(): String? {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_MODE, "0")
        val value = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_VAL, "")
        return when (mode) {
            "1" -> SettingsManager.getBestPingGuid()
            "2" -> {
                if (value.isNullOrBlank()) return SettingsManager.getBestPingGuid()
                MmkvManager.decodeAllServerList().find { guid ->
                    val config = MmkvManager.decodeServerConfig(guid)
                    config?.configType == EConfigType.POLICYGROUP && config.remarks.equals(value, true)
                } ?: SettingsManager.getBestPingGuid()
            }
            "3", "4" -> {
                MmkvManager.decodeAllServerList().find { guid ->
                    val config = MmkvManager.decodeServerConfig(guid)
                    config?.configType == EConfigType.POLICYGROUP && config.remarks == "Global QS Target"
                } ?: SettingsManager.getBestPingGuid()
            }
            else -> MmkvManager.getSelectServer() ?: SettingsManager.getBestPingGuid()
        }
    }

    suspend fun getQtStatusTextAsync(config: ProfileItem?): String {
        return if (config?.remarks == "Global QS Target") {
            val count = AutoOutboundBuilder.getFilteredRoutingProxies(config.policyGroupFilter).size
            "QT Active ($count)"
        } else {
            "Quick Tile"
        }
    }
}
