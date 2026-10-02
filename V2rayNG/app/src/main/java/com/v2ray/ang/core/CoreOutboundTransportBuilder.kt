package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.StreamSettingsBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

object CoreOutboundTransportBuilder {
    fun createTcpHttpRequest(host: String?, path: String?): StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean {
        val requestString = """{"version":"1.1","method":"GET","headers":{"User-Agent":["Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"],"Accept-Encoding":["gzip, deflate"],"Connection":["keep-alive"],"Pragma":"no-cache"}}"""
        val request = JsonUtil.fromJson(requestString, StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean::class.java) ?: StreamSettingsBean.TcpSettingsBean.HeaderBean.RequestBean()
        val parsedHost = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        request.headers.Host = parsedHost.ifEmpty { null }
        request.path = path.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("/") }
        return request
    }

    fun populateTransportSettings(streamSettings: StreamSettingsBean, profileItem: ProfileItem): String? {
        val transport = profileItem.network.orEmpty()
        val headerType = profileItem.headerType
        val host = profileItem.host
        val path = profileItem.path
        val seed = profileItem.seed
        var sni: String? = null
        streamSettings.network = transport.ifEmpty { NetworkType.TCP.type }
        when (streamSettings.network) {
            NetworkType.TCP.type -> {
                val tcpSetting = StreamSettingsBean.TcpSettingsBean()
                if (headerType == AppConfig.HEADER_TYPE_HTTP) {
                    tcpSetting.header.type = AppConfig.HEADER_TYPE_HTTP
                    val requestObj = createTcpHttpRequest(host, path)
                    tcpSetting.header.request = requestObj
                    sni = requestObj.headers.Host?.getOrNull(0)
                } else {
                    tcpSetting.header.type = "none"
                    sni = host
                }
                streamSettings.tcpSettings = tcpSetting
            }
            NetworkType.KCP.type -> {
                val kcpSetting = StreamSettingsBean.KcpSettingsBean()
                profileItem.kcpMtu?.let { kcpSetting.mtu = it }
                profileItem.kcpTti?.let { kcpSetting.tti = it }
                streamSettings.kcpSettings = kcpSetting
                val udpMaskList = mutableListOf<StreamSettingsBean.FinalMaskBean.MaskBean>()
                if (!headerType.isNullOrEmpty() && headerType != "none") {
                    val kcpHeaderType = if (headerType == "wechat-video") "wechat" else headerType
                    udpMaskList.add(StreamSettingsBean.FinalMaskBean.MaskBean(type = "mkcp-legacy", settings = StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(header = kcpHeaderType, value = if (headerType == "dns" && !host.isNullOrEmpty()) host else null)))
                }
                udpMaskList.add(StreamSettingsBean.FinalMaskBean.MaskBean(type = "mkcp-legacy", settings = if (seed.isNullOrEmpty()) null else StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(value = seed)))
                udpMaskList.reverse()
                streamSettings.finalmask = StreamSettingsBean.FinalMaskBean(udp = udpMaskList.toList())
            }
            NetworkType.WS.type -> {
                val wssetting = StreamSettingsBean.WsSettingsBean()
                wssetting.host = host.orEmpty()
                sni = host
                wssetting.path = path ?: "/"
                streamSettings.wsSettings = wssetting
            }
            NetworkType.HTTP_UPGRADE.type -> {
                val httpupgradeSetting = StreamSettingsBean.HttpupgradeSettingsBean()
                httpupgradeSetting.host = host.orEmpty()
                sni = host
                httpupgradeSetting.path = path ?: "/"
                streamSettings.httpupgradeSettings = httpupgradeSetting
            }
            NetworkType.XHTTP.type -> {
                val xhttpSetting = StreamSettingsBean.XhttpSettingsBean()
                xhttpSetting.host = host.orEmpty()
                sni = host
                xhttpSetting.path = path ?: "/"
                xhttpSetting.mode = profileItem.xhttpMode
                xhttpSetting.extra = JsonUtil.parseString(profileItem.xhttpExtra)
                streamSettings.xhttpSettings = xhttpSetting
            }
            NetworkType.H2.type, NetworkType.HTTP.type -> {
                streamSettings.network = NetworkType.H2.type
                val h2Setting = StreamSettingsBean.HttpSettingsBean()
                h2Setting.host = host.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                sni = h2Setting.host.getOrNull(0)
                h2Setting.path = path ?: "/"
                streamSettings.httpSettings = h2Setting
            }
            NetworkType.GRPC.type -> {
                val grpcSetting = StreamSettingsBean.GrpcSettingsBean()
                grpcSetting.multiMode = profileItem.mode == "multi"
                grpcSetting.serviceName = profileItem.serviceName.orEmpty()
                grpcSetting.authority = profileItem.authority.orEmpty()
                grpcSetting.idle_timeout = 60
                grpcSetting.health_check_timeout = 20
                sni = profileItem.authority
                streamSettings.grpcSettings = grpcSetting
            }
            NetworkType.HYSTERIA.type -> {
                val hysteriaSetting = StreamSettingsBean.HysteriaSettingsBean(version = 2, auth = profileItem.password.orEmpty())
                val quicParams = StreamSettingsBean.FinalMaskBean.QuicParamsBean(
                    brutalUp = profileItem.bandwidthUp?.nullIfBlank(),
                    brutalDown = profileItem.bandwidthDown?.nullIfBlank(),
                )
                quicParams.congestion = if (quicParams.brutalUp != null || quicParams.brutalDown != null) "brutal" else null
                if (profileItem.portHopping.isNotNullEmpty()) {
                    val rawInterval = profileItem.portHoppingInterval?.trim().nullIfBlank()
                    val interval = rawInterval?.toIntOrNull()?.let { if (it < 5) "30" else rawInterval } ?: "30"
                    quicParams.udpHop = StreamSettingsBean.FinalMaskBean.QuicParamsBean.UdpHopBean(ports = profileItem.portHopping, interval = interval)
                }
                val finalmask = StreamSettingsBean.FinalMaskBean(quicParams = quicParams)
                if (profileItem.obfsPassword.isNotNullEmpty()) {
                    finalmask.udp = listOf(StreamSettingsBean.FinalMaskBean.MaskBean(type = "salamander", settings = StreamSettingsBean.FinalMaskBean.MaskBean.MaskSettingsBean(password = profileItem.obfsPassword.orEmpty())))
                }
                streamSettings.hysteriaSettings = hysteriaSetting
                streamSettings.finalmask = finalmask
            }
        }
        profileItem.finalMask?.let { mask ->
            val parsed = JsonUtil.parseString(mask)
            if (parsed != null) streamSettings.finalmask = parsed
            else LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
        }
        return sni
    }
}
