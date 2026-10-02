package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.VpnInterfaceAddressConfig
import com.v2ray.ang.handler.MmkvManager.decodeAllServerList
import com.v2ray.ang.handler.MmkvManager.decodeServerConfig
import com.v2ray.ang.handler.MmkvManager.decodeSubsList
import com.v2ray.ang.handler.MmkvManager.encodeSubscription
import com.v2ray.ang.handler.MmkvManager.removeSubscription
import com.v2ray.ang.util.Utils
import kotlin.random.Random

object SettingsManager {
    @Volatile
    private var runtimeSocksPort: Int? = null

    fun initApp(context: Context) {
        SettingsMigrationHelper.ensureDefaultSettings()
        SettingsMigrationHelper.migrateServerListToSubscriptions()
        SettingsMigrationHelper.migrateHysteria2PinSHA256()
    }

    fun routingRulesetsBypassLan(): Boolean {
        val vpnBypassLan = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)
        return vpnBypassLan != "2"
    }

    fun getProfileRemarks(excludeConfigTypes: Set<EConfigType> = setOf(EConfigType.CUSTOM)): List<String> {
        return decodeAllServerList()
            .asSequence()
            .mapNotNull { guid -> decodeServerConfig(guid) }
            .filter { profile -> profile.configType !in excludeConfigTypes }
            .map { it.remarks.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    fun removeSubscriptionWithDefault(subid: String) {
        SubscriptionUpdater.cancelOne(subId = subid)
        removeSubscription(subid)
        val subsList2 = decodeSubsList()
        if (subsList2.isNotEmpty()) return
        val defaultSub = SubscriptionItem(remarks = "Default")
        encodeSubscription(DEFAULT_SUBSCRIPTION_ID, defaultSub)
    }

    fun getSocksPort(): Int {
        val port = if (IsDynamicSocksPort()) runtimeSocksPort ?: refreshRuntimeSocksPort()
        else Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT), AppConfig.PORT_SOCKS.toInt())
        return port ?: AppConfig.PORT_SOCKS.toInt()
    }

    @Synchronized
    fun refreshRuntimeSocksPort(): Int? {
        if (IsDynamicSocksPort()) {
            runtimeSocksPort = generateRandomSocksPort()
            return runtimeSocksPort
        }
        return null
    }

    fun getSocksUsername(): String? = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
    fun getSocksPassword(): String? = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PASSWORD)?.trim()?.takeIf { it.isNotEmpty() }
    fun getHttpPort(): Int = getSocksPort() + if (Utils.isXray()) 0 else 1
    private fun IsDynamicSocksPort(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    private fun generateRandomSocksPort(): Int = Random.nextInt(10000, 65535)

    fun initAssets(context: Context, assets: AssetManager) = SettingsAssetHelper.initAssets(context, assets)

    fun getDomesticDnsServers(): List<String> {
        val domesticDns = MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS) ?: AppConfig.DNS_DIRECT
        val ret = domesticDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        return if (ret.isEmpty()) listOf(AppConfig.DNS_DIRECT) else ret
    }

    fun getRemoteDnsServers(): List<String> {
        val remoteDns = MmkvManager.decodeSettingsString(AppConfig.PREF_REMOTE_DNS) ?: AppConfig.DNS_PROXY
        val ret = remoteDns.split(",").filter { Utils.isPureIpAddress(it) || Utils.isCoreDNSAddress(it) }
        return if (ret.isEmpty()) listOf(AppConfig.DNS_PROXY) else ret
    }

    fun getVpnDnsServers(): List<String> {
        val vpnDns = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS) ?: AppConfig.DNS_VPN
        return vpnDns.split(",").filter { Utils.isPureIpAddress(it) }
    }

    fun getDelayTestUrl(second: Boolean = false): String {
        return if (second) AppConfig.DELAY_TEST_URL2
        else MmkvManager.decodeSettingsString(AppConfig.PREF_DELAY_TEST_URL) ?: AppConfig.DELAY_TEST_URL
    }

    fun getRealPingConcurrency(): Int {
        val value = MmkvManager.decodeSettingsString(AppConfig.PREF_REAL_PING_CONCURRENCY)?.toIntOrNull() ?: 16
        return value.coerceIn(1, 128)
    }

    fun getCurrentVpnInterfaceAddressConfig(): VpnInterfaceAddressConfig {
        val selectedIndex = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")?.toInt()
        return VpnInterfaceAddressConfig.getConfigByIndex(selectedIndex ?: 0)
    }

    fun getVpnMtu(): Int = Utils.parseInt(MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_MTU), AppConfig.VPN_MTU)
    fun isUsingHevTun(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true)
    fun isVpnMode(): Boolean {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE)
        return mode == null || mode == VPN
    }
}

