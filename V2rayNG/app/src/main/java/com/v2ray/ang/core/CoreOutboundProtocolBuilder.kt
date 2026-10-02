package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

object CoreOutboundProtocolBuilder {
    fun toOutboundVmess(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.VMESS)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.id = profileItem.password.orEmpty()
            it.security = profileItem.method
            it.level = AppConfig.DEFAULT_LEVEL
        }
        val sni = bean?.streamSettings?.let { CoreOutboundTransportBuilder.populateTransportSettings(it, profileItem) }
        bean?.streamSettings?.let { CoreOutboundTlsBuilder.populateTlsSettings(it, profileItem, sni) }
        return bean
    }

    fun toOutboundVless(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.VLESS)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.id = profileItem.password.orEmpty()
            it.encryption = profileItem.method
            it.flow = profileItem.flow
            it.level = AppConfig.DEFAULT_LEVEL
        }
        val sni = bean?.streamSettings?.let { CoreOutboundTransportBuilder.populateTransportSettings(it, profileItem) }
        bean?.streamSettings?.let { CoreOutboundTlsBuilder.populateTlsSettings(it, profileItem, sni) }
        return bean
    }

    fun toOutboundShadowsocks(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.SHADOWSOCKS)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.password = profileItem.password
            it.method = profileItem.method
            it.level = AppConfig.DEFAULT_LEVEL
        }
        val sni = bean?.streamSettings?.let { CoreOutboundTransportBuilder.populateTransportSettings(it, profileItem) }
        bean?.streamSettings?.let { CoreOutboundTlsBuilder.populateTlsSettings(it, profileItem, sni) }
        return bean
    }

    fun toOutboundTrojan(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.TROJAN)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.password = profileItem.password
            it.flow = profileItem.flow
            it.level = AppConfig.DEFAULT_LEVEL
        }
        val sni = bean?.streamSettings?.let { CoreOutboundTransportBuilder.populateTransportSettings(it, profileItem) }
        bean?.streamSettings?.let { CoreOutboundTlsBuilder.populateTlsSettings(it, profileItem, sni) }
        return bean
    }

    fun toOutboundSocks(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.SOCKS)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.level = AppConfig.DEFAULT_LEVEL
            if (profileItem.username.isNotNullEmpty()) {
                it.user = profileItem.username.orEmpty()
                it.pass = profileItem.password.orEmpty()
            }
        }
        return bean
    }

    fun toOutboundHttp(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.HTTP)
        bean?.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.level = AppConfig.DEFAULT_LEVEL
            if (profileItem.username.isNotNullEmpty()) {
                it.user = profileItem.username.orEmpty()
                it.pass = profileItem.password.orEmpty()
            }
        }
        return bean
    }

    fun toOutboundWireguard(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.WIREGUARD)
        val rawAddresses = profileItem.localAddress?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null } ?: listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4)
        val addresses = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) rawAddresses else rawAddresses.filter { !it.contains(":") }.ifEmpty { listOf(AppConfig.WIREGUARD_LOCAL_ADDRESS_V4) }
        val rawDNS = profileItem.remoteDNS?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null } ?: listOf(AppConfig.WIREGUARD_LOCAL_REMOTE_DNS)
        val remotes = if (rawDNS.size == 1 && rawDNS[0] == "local") rawDNS else if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) rawDNS else rawDNS.filter { !it.contains(":") }.ifEmpty { listOf(AppConfig.WIREGUARD_LOCAL_REMOTE_DNS) }

        bean?.settings?.let { wireguard ->
            wireguard.secretKey = profileItem.secretKey
            wireguard.address = addresses
            wireguard.port = null
            wireguard.peers?.firstOrNull()?.let {
                it.publicKey = profileItem.publicKey.orEmpty()
                it.preSharedKey = profileItem.preSharedKey?.nullIfBlank()
                it.endpoint = Utils.getIpv6Address(profileItem.server) + ":${profileItem.serverPort}"
            }
            wireguard.mtu = profileItem.mtu
            wireguard.remoteDNS = remotes
            wireguard.reserved = profileItem.reserved?.takeIf { it.isNotBlank() }?.split(",")?.filter { it.isNotBlank() }?.map { it.trim().toInt() }
        }
        if (!profileItem.finalMask.isNullOrBlank()) {
            bean?.streamSettings = OutboundBean.StreamSettingsBean()
            bean?.streamSettings?.let {
                CoreOutboundTlsBuilder.updateOutboundFinalMask(it, profileItem)
                it.network = null
            }
        }
        return bean
    }

    fun toOutboundHysteria2(profileItem: ProfileItem): OutboundBean? {
        val bean = CoreOutboundBuilder.createInitOutbound(EConfigType.HYSTERIA2) ?: return null
        profileItem.network = NetworkType.HYSTERIA.type
        profileItem.alpn = "h3"
        bean.settings?.let {
            it.address = CoreOutboundTlsBuilder.getServerAddress(profileItem)
            it.port = profileItem.serverPort.orEmpty().toInt()
            it.version = 2
        }
        val sni = bean.streamSettings?.let { CoreOutboundTransportBuilder.populateTransportSettings(it, profileItem) }
        bean.streamSettings?.let { CoreOutboundTlsBuilder.populateTlsSettings(it, profileItem, sni) }
        return bean
    }
}
