package com.v2ray.ang.dto

import com.v2ray.ang.enums.EConfigType

data class V2rayConfig(
    var remarks: String? = null,
    var stats: Any? = null,
    val log: LogBean,
    var policy: PolicyBean? = null,
    val inbounds: ArrayList<InboundBean>,
    var outbounds: ArrayList<OutboundBean>,
    var dns: DnsBean? = null,
    val routing: RoutingBean,
    val api: Any? = null,
    val transport: Any? = null,
    val reverse: Any? = null,
    var fakedns: Any? = null,
    val browserForwarder: Any? = null
) {
    data class LogBean(
        val access: String? = null,
        val error: String? = null,
        var loglevel: String? = null,
        val dnsLog: Boolean? = null
    )

    data class InboundBean(
        var tag: String,
        var port: Int?,
        var protocol: String,
        var listen: String? = null,
        var settings: InSettingsBean? = null,
        var sniffing: SniffingBean? = null,
        val streamSettings: Any? = null,
        val allocate: Any? = null
    ) {
        data class InSettingsBean(
            var auth: String? = null,
            var udp: Boolean? = null,
            var userLevel: Int? = null,
            var accounts: List<SocksAccountBean>? = null,
            var name: String? = null,
            var mtu: Int? = null
        ) {
            data class SocksAccountBean(
                var user: String = "",
                var pass: String = ""
            )
        }

        data class SniffingBean(
            var enabled: Boolean,
            val destOverride: ArrayList<String>,
            val metadataOnly: Boolean? = null,
            var routeOnly: Boolean? = null
        )
    }

    data class OutboundBean(
        var tag: String = "proxy",
        var protocol: String,
        var settings: OutSettingsBean? = null,
        var streamSettings: StreamSettingsBean? = null,
        val sendThrough: String? = null
    ) {
        typealias StreamSettingsBean = com.v2ray.ang.dto.StreamSettingsBean

        data class OutSettingsBean(
            var address: Any? = null,
            var port: Int? = null,
            var level: Int? = null,
            var email: String? = null,
            var user: String? = null,
            var pass: String? = null,
            var headers: Map<String, String>? = null,
            var id: String? = null,
            var security: String? = null,
            var encryption: String? = null,
            var flow: String? = null,
            var password: String? = null,
            var method: String? = null,
            var version: Int? = null,
            var secretKey: String? = null,
            val peers: List<WireGuardBean>? = null,
            var reserved: List<Int>? = null,
            var mtu: Int? = null,
            var remoteDNS: List<String>? = null,
            var domainStrategy: String? = null,
        ) {
            data class WireGuardBean(
                var publicKey: String = "",
                var preSharedKey: String? = null,
                var endpoint: String = ""
            )
        }

        fun getServerAddress(): String? {
            return if (protocol.equals(EConfigType.WIREGUARD.name, true)) {
                settings?.peers?.firstOrNull()?.endpoint?.substringBeforeLast(":")
            } else {
                settings?.address as? String
            }
        }

        fun getServerPort(): Int? {
            return if (protocol.equals(EConfigType.WIREGUARD.name, true)) {
                settings?.peers?.firstOrNull()?.endpoint?.substringAfterLast(":")?.toIntOrNull()
            } else {
                settings?.port
            }
        }

        fun ensureSockopt(): com.v2ray.ang.dto.StreamSettingsBean.SockoptBean {
            val stream = streamSettings ?: com.v2ray.ang.dto.StreamSettingsBean().also { streamSettings = it }
            val sockopt = stream.sockopt ?: com.v2ray.ang.dto.StreamSettingsBean.SockoptBean().also { stream.sockopt = it }
            return sockopt
        }
    }

    data class DnsBean(
        var servers: ArrayList<Any>? = null,
        var hosts: Map<String, Any>? = null,
        val clientIp: String? = null,
        val disableCache: Boolean? = null,
        val queryStrategy: String? = null,
        val enableParallelQuery: Boolean? = null,
        val tag: String? = null
    ) {
        data class ServersBean(
            var address: String = "",
            var port: Int? = null,
            var domains: List<String>? = null,
            var expectIPs: List<String>? = null,
            val clientIp: String? = null,
            val skipFallback: Boolean? = null,
            val tag: String? = null,
        )
    }

    data class RoutingBean(
        var domainStrategy: String,
        var rules: ArrayList<RulesBean>
    ) {
        data class RulesBean(
            var type: String = "field",
            var ip: List<String>? = null,
            var domain: List<String>? = null,
            var process: List<String>? = null,
            var outboundTag: String? = null,
            var balancerTag: String? = null,
            var port: String? = null,
            val sourcePort: String? = null,
            val network: String? = null,
            val source: List<String>? = null,
            val user: List<String>? = null,
            var inboundTag: List<String>? = null,
            val protocol: List<String>? = null,
            val attrs: String? = null
        )
    }

    data class PolicyBean(
        var levels: Map<String, LevelBean>,
        var system: Any? = null
    ) {
        data class LevelBean(
            var handshake: Int? = null,
            var connIdle: Int? = null,
            var uplinkOnly: Int? = null,
            var downlinkOnly: Int? = null,
            val statsUserUplink: Boolean? = null,
            val statsUserDownlink: Boolean? = null,
            var bufferSize: Int? = null
        )
    }

    data class FakednsBean(
        var ipPool: String = "198.18.0.0/15",
        var poolSize: Int = 10000
    )

    fun getProxyOutbound(): OutboundBean? {
        outbounds.forEach { outbound ->
            EConfigType.entries.forEach { if (outbound.protocol.equals(it.name, true)) return outbound }
        }
        return null
    }

    fun getAllProxyOutbound(): List<OutboundBean> {
        return outbounds.filter { outbound -> EConfigType.entries.any { it.name.equals(outbound.protocol, ignoreCase = true) } }
    }
}
