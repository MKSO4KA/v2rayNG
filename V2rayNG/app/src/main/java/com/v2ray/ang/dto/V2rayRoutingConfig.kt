package com.v2ray.ang.dto

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

data class FakednsBean(
    var ipPool: String = "198.18.0.0/15",
    var poolSize: Int = 10000
)
