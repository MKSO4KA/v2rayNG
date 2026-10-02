package com.v2ray.ang.dto

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
