package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

object CoreConfigInboundBuilder {
    fun needTun(): Boolean = SettingsManager.isVpnMode() && !SettingsManager.isUsingHevTun()

    fun configureInbounds(v2rayConfig: V2rayConfig) {
        val vpn = SettingsManager.isVpnMode()
        val useHev = SettingsManager.isUsingHevTun()
        val forcedByHev = vpn && useHev
        val enableLocalProxy = forcedByHev || MmkvManager.decodeSettingsBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val inbound1 = v2rayConfig.inbounds[0]
        if (inbound1.settings == null) inbound1.settings = V2rayConfig.InboundBean.InSettingsBean()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) != true) inbound1.listen = AppConfig.LOOPBACK
        inbound1.port = socksPort
        inbound1.settings?.udp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SOCKS_ENABLE_UDP, AppConfig.DEFAULT_SOCKS_ENABLE_UDP)
        if (socksUsername != null && socksPassword != null) {
            inbound1.settings?.auth = "password"
            inbound1.settings?.accounts = listOf(V2rayConfig.InboundBean.InSettingsBean.SocksAccountBean(user = socksUsername, pass = socksPassword))
        } else {
            inbound1.settings?.auth = "noauth"
            inbound1.settings?.accounts = null
        }
        val fakedns = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        val sniffAllTlsAndHttp = MmkvManager.decodeSettingsBool(AppConfig.PREF_SNIFFING_ENABLED, true) != false
        inbound1.sniffing?.enabled = fakedns || sniffAllTlsAndHttp
        inbound1.sniffing?.routeOnly = MmkvManager.decodeSettingsBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
        if (!sniffAllTlsAndHttp) inbound1.sniffing?.destOverride?.clear()
        if (fakedns) inbound1.sniffing?.destOverride?.add("fakedns")

        if (!Utils.isXray()) {
            val inbound2 = JsonUtil.fromJson(JsonUtil.toJson(inbound1), V2rayConfig.InboundBean::class.java) ?: error("Failed to clone inbound template")
            inbound2.tag = EConfigType.HTTP.name.lowercase()
            inbound2.port = SettingsManager.getHttpPort()
            inbound2.protocol = EConfigType.HTTP.name.lowercase()
            inbound2.settings?.auth = null
            inbound2.settings?.udp = null
            v2rayConfig.inbounds.add(inbound2)
        }
        if (!enableLocalProxy) {
            v2rayConfig.inbounds.removeIf { it.protocol == "socks" || it.protocol == "http" }
        }
        if (needTun()) {
            val inboundTun = v2rayConfig.inbounds.firstOrNull { e -> e.tag == "tun" }
            inboundTun?.settings?.mtu = SettingsManager.getVpnMtu()
            inboundTun?.sniffing = inbound1.sniffing
        }
    }
}
