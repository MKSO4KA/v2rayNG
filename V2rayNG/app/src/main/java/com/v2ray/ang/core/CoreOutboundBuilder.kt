package com.v2ray.ang.core

import com.v2ray.ang.dto.StreamSettingsBean
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

object CoreOutboundBuilder {
    fun convert(profileItem: ProfileItem): OutboundBean? {
        return when (profileItem.configType) {
            EConfigType.VMESS -> CoreOutboundProtocolBuilder.toOutboundVmess(profileItem)
            EConfigType.SHADOWSOCKS -> CoreOutboundProtocolBuilder.toOutboundShadowsocks(profileItem)
            EConfigType.SOCKS -> CoreOutboundProtocolBuilder.toOutboundSocks(profileItem)
            EConfigType.VLESS -> CoreOutboundProtocolBuilder.toOutboundVless(profileItem)
            EConfigType.TROJAN -> CoreOutboundProtocolBuilder.toOutboundTrojan(profileItem)
            EConfigType.WIREGUARD -> CoreOutboundProtocolBuilder.toOutboundWireguard(profileItem)
            EConfigType.HYSTERIA2 -> CoreOutboundProtocolBuilder.toOutboundHysteria2(profileItem)
            EConfigType.HTTP -> CoreOutboundProtocolBuilder.toOutboundHttp(profileItem)
            else -> null
        }
    }

    fun createInitOutbound(configType: EConfigType): OutboundBean? {
        return when (configType) {
            EConfigType.VMESS,
            EConfigType.VLESS,
            EConfigType.SHADOWSOCKS,
            EConfigType.SOCKS,
            EConfigType.HTTP,
            EConfigType.TROJAN -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = StreamSettingsBean()
            )
            EConfigType.WIREGUARD -> OutboundBean(
                protocol = configType.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(
                    secretKey = "",
                    peers = listOf(OutboundBean.OutSettingsBean.WireGuardBean())
                )
            )
            EConfigType.HYSTERIA,
            EConfigType.HYSTERIA2 -> OutboundBean(
                protocol = EConfigType.HYSTERIA.name.lowercase(),
                settings = OutboundBean.OutSettingsBean(),
                streamSettings = StreamSettingsBean()
            )
            else -> null
        }
    }

    fun populateTransportSettings(streamSettings: StreamSettingsBean, profileItem: ProfileItem): String? =
        CoreOutboundTransportBuilder.populateTransportSettings(streamSettings, profileItem)

    fun populateTlsSettings(streamSettings: StreamSettingsBean, profileItem: ProfileItem, sniExt: String?) =
        CoreOutboundTlsBuilder.populateTlsSettings(streamSettings, profileItem, sniExt)

    fun updateOutboundFinalMask(streamSettings: StreamSettingsBean, profileItem: ProfileItem) =
        CoreOutboundTlsBuilder.updateOutboundFinalMask(streamSettings, profileItem)
}
