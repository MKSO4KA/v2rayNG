package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

object CoreConfigDnsBuilder {
    fun configureFakeDns(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true
        ) {
            v2rayConfig.fakedns = listOf(V2rayConfig.FakednsBean())
        }
    }

    fun configureLocalDns(v2rayConfig: V2rayConfig) {
        val localDnsEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true
        if (localDnsEnabled && MmkvManager.decodeSettingsBool(AppConfig.PREF_FAKE_DNS_ENABLED) == true) {
            v2rayConfig.dns?.servers?.add(
                0,
                V2rayConfig.DnsBean.ServersBean(address = "fakedns")
            )

        }
        if (SettingsManager.isVpnMode()) {
            val inboundTag = if (SettingsManager.isUsingHevTun()) "socks" else "tun"
            v2rayConfig.routing.rules.add(
                0,
                V2rayConfig.RoutingBean.RulesBean(
                    inboundTag = arrayListOf(inboundTag),
                    outboundTag = "dns-out",
                    port = "53",
                )
            )
            if (v2rayConfig.outbounds.none { e -> e.protocol == "dns" && e.tag == "dns-out" }) {
                v2rayConfig.outbounds.add(V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out", settings = null, streamSettings = null))
            }
        }
    }

    fun configureDns(v2rayConfig: V2rayConfig) {
        val servers = ArrayList<Any>()
        val remoteDns = SettingsManager.getRemoteDnsServers()
        val domesticDns = SettingsManager.getDomesticDnsServers()
        remoteDns.forEach { servers.add(it) }
        val domesticDnsTags = mutableListOf<String>()
        domesticDns.forEachIndexed { index, address ->
            val tag = "${AppConfig.TAG_DOMESTIC_DNS}_$index"
            servers.add(V2rayConfig.DnsBean.ServersBean(address = address, skipFallback = true, tag = tag))
            domesticDnsTags.add(tag)
        }
        val hosts = buildDnsHostsFromRoutingRules()
        v2rayConfig.dns = V2rayConfig.DnsBean(servers = servers, hosts = hosts, tag = AppConfig.TAG_DNS, enableParallelQuery = true)
        if (domesticDnsTags.isNotEmpty()) {
            v2rayConfig.routing.rules.add(
                V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT, inboundTag = ArrayList(domesticDnsTags), domain = null)
            )
        }
        v2rayConfig.routing.rules.add(
            V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_PROXY, inboundTag = arrayListOf(AppConfig.TAG_DNS), domain = null)
        )
    }

    fun buildDnsHostsFromRoutingRules(): MutableMap<String, Any> {
        val hosts = mutableMapOf<String, Any>()
        hosts[AppConfig.GOOGLEAPIS_CN_DOMAIN] = AppConfig.GOOGLEAPIS_COM_DOMAIN
        hosts[AppConfig.DNS_ALIDNS_DOMAIN] = AppConfig.DNS_ALIDNS_ADDRESSES
        hosts[AppConfig.DNS_CISCO_SSE_DOMAIN] = AppConfig.DNS_CISCO_SSE_ADDRESSES
        hosts[AppConfig.DNS_CISCO_UMBRELLA_DOMAIN] = AppConfig.DNS_CISCO_UMBRELLA_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONE_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONE_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_ONEDOT_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_COM_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_COM_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_DNS_DOMAIN] = AppConfig.DNS_CLOUDFLARE_DNS_ADDRESSES
        hosts[AppConfig.DNS_CLOUDFLARE_WARP_DOMAIN] = AppConfig.DNS_CLOUDFLARE_WARP_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOH_DOMAIN] = AppConfig.DNS_DNSPOD_DOH_ADDRESSES
        hosts[AppConfig.DNS_DNSPOD_DOT_DOMAIN] = AppConfig.DNS_DNSPOD_DOT_ADDRESSES
        hosts[AppConfig.DNS_GOOGLE_DOMAIN] = AppConfig.DNS_GOOGLE_ADDRESSES
        hosts[AppConfig.DNS_QUAD9_DOMAIN] = AppConfig.DNS_QUAD9_ADDRESSES
        hosts[AppConfig.DNS_SB_DOMAIN] = AppConfig.DNS_SB_ADDRESSES
        hosts[AppConfig.DNS_YANDEX_DOMAIN] = AppConfig.DNS_YANDEX_ADDRESSES

        val userHosts = MmkvManager.decodeSettingsString(AppConfig.PREF_DNS_HOSTS)
        if (userHosts.isNotNullEmpty()) {
            val userHostsMap = userHosts?.split(",").orEmpty()
                .filter { it.isNotBlank() && it.contains(":") }
                .associate {
                    val parts = it.split(":", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
            hosts.putAll(userHostsMap)
        }
        return hosts
    }
}
