package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.util.JsonUtil

object SettingsMigrationHelper {
    fun ensureDefaultSettings() {
        ensureDefaultValue(AppConfig.PREF_MODE, VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_DNS, AppConfig.DNS_VPN)
        ensureDefaultValue(AppConfig.PREF_VPN_MTU, AppConfig.VPN_MTU.toString())
        ensureDefaultValue(AppConfig.PREF_SOCKS_PORT, AppConfig.PORT_SOCKS)
        ensureDefaultValue(AppConfig.PREF_REMOTE_DNS, AppConfig.DNS_PROXY)
        ensureDefaultValue(AppConfig.PREF_DOMESTIC_DNS, AppConfig.DNS_DIRECT)
        ensureDefaultValue(AppConfig.PREF_DELAY_TEST_URL, AppConfig.DELAY_TEST_URL)
        ensureDefaultValue(AppConfig.PREF_IP_API_URL, AppConfig.IP_API_URL)
        ensureDefaultValue(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, AppConfig.HEVTUN_RW_TIMEOUT)
    }

    private fun ensureDefaultValue(key: String, default: String) {
        if (MmkvManager.decodeSettingsString(key).isNullOrEmpty()) {
            MmkvManager.encodeSettings(key, default)
        }
    }

    fun migrateHysteria2PinSHA256() {
        val migrationKey = "hysteria2_pin_sha256_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) return
        val serverList = MmkvManager.decodeAllServerList()
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (profile.configType != EConfigType.HYSTERIA2) continue
            if (profile.pinSHA256.isNullOrEmpty() || !profile.pinnedCA256.isNullOrEmpty()) continue
            profile.pinnedCA256 = profile.pinSHA256
            profile.pinSHA256 = null
            MmkvManager.encodeServerConfig(guid, profile)
        }
        MmkvManager.encodeSettings(migrationKey, true)
    }

    fun migrateServerListToSubscriptions() {
        val migrationKey = "server_list_to_subscriptions_migrated"
        if (MmkvManager.decodeSettingsBool(migrationKey, false)) return
        ensureDefaultSubscription()
        val oldJson = MmkvManager.readLegacyServerList()
        if (oldJson.isNullOrBlank()) {
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }
        val guids = JsonUtil.fromJsonSafe(oldJson, Array<String>::class.java) ?: run {
            MmkvManager.encodeSettings(migrationKey, true)
            return
        }
        val subscriptionServerMap = mutableMapOf<String, MutableList<String>>()
        guids.forEach { guid ->
            val config = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            val subId = config.subscriptionId.ifEmpty { DEFAULT_SUBSCRIPTION_ID }
            subscriptionServerMap.getOrPut(subId) { mutableListOf() }.add(guid)
        }
        subscriptionServerMap.forEach { (subId, serverGuids) ->
            MmkvManager.encodeServerList(serverGuids, subId)
        }
        MmkvManager.encodeSettings(migrationKey, true)
    }

    fun ensureDefaultSubscription() {
        if (MmkvManager.decodeSubscription(DEFAULT_SUBSCRIPTION_ID) == null) {
            val defaultSub = SubscriptionItem(remarks = "Default")
            MmkvManager.encodeSubscription(DEFAULT_SUBSCRIPTION_ID, defaultSub)
            val subsList = MmkvManager.decodeSubsList()
            if (subsList.moveItem(subsList.lastIndex, 0)) {
                MmkvManager.encodeSubsList(subsList)
            }
        }
    }
}
