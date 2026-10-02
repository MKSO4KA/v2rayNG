package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.StreamSettingsBean
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil

object CoreConfigDomainResolver {
    fun resolveOutboundDomainsToHosts(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD) != "1") return
        val proxyOutboundList = v2rayConfig.getAllProxyOutbound()
        val dns = v2rayConfig.dns ?: return
        val newHosts = dns.hosts?.toMutableMap() ?: mutableMapOf()
        val preferIpv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true

        for (item in proxyOutboundList) {
            val domain = item.getServerAddress()
            if (domain.isNullOrEmpty()) continue
            if (newHosts.containsKey(domain)) {
                item.ensureSockopt().domainStrategy = "UseIP"
                item.ensureSockopt().happyEyeballs = StreamSettingsBean.HappyEyeballsBean(prioritizeIPv6 = preferIpv6, interleave = 2)
                continue
            }
            val resolvedIps = HttpUtil.resolveHostToIP(domain, preferIpv6)
            if (resolvedIps.isNullOrEmpty()) continue
            item.ensureSockopt().domainStrategy = "UseIP"
            item.ensureSockopt().happyEyeballs = StreamSettingsBean.HappyEyeballsBean(prioritizeIPv6 = preferIpv6, interleave = 2)
            newHosts[domain] = if (resolvedIps.size == 1) resolvedIps[0] else resolvedIps
        }
        dns.hosts = newHosts
    }
}
