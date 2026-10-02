package com.v2ray.ang.dto

import com.google.gson.annotations.SerializedName
import com.v2ray.ang.AppConfig

data class StreamSettingsBean(
    var network: String? = AppConfig.DEFAULT_NETWORK,
    var security: String? = null,
    var tcpSettings: TcpSettingsBean? = null,
    var kcpSettings: KcpSettingsBean? = null,
    var wsSettings: WsSettingsBean? = null,
    var httpupgradeSettings: HttpupgradeSettingsBean? = null,
    var xhttpSettings: XhttpSettingsBean? = null,
    var httpSettings: HttpSettingsBean? = null,
    var tlsSettings: TlsSettingsBean? = null,
    var quicSettings: QuicSettingBean? = null,
    var realitySettings: TlsSettingsBean? = null,
    var grpcSettings: GrpcSettingsBean? = null,
    var hysteriaSettings: HysteriaSettingsBean? = null,
    var finalmask: Any? = null,
    val dsSettings: Any? = null,
    var sockopt: SockoptBean? = null
) {
    data class TcpSettingsBean(
        var header: HeaderBean = HeaderBean(),
        val acceptProxyProtocol: Boolean? = null
    ) {
        data class HeaderBean(
            var type: String = "none",
            var request: RequestBean? = null,
            var response: Any? = null
        ) {
            data class RequestBean(
                var path: List<String> = ArrayList(),
                var headers: HeadersBean = HeadersBean(),
                val version: String? = null,
                val method: String? = null
            ) {
                data class HeadersBean(
                    var Host: List<String>? = ArrayList(),
                    @SerializedName("User-Agent") val userAgent: List<String>? = null,
                    @SerializedName("Accept-Encoding") val acceptEncoding: List<String>? = null,
                    val Connection: List<String>? = null,
                    val Pragma: Any? = null
                )
            }
        }
    }

    data class KcpSettingsBean(
        var mtu: Int = 1350,
        var tti: Int = 50,
        var uplinkCapacity: Int = 12,
        var downlinkCapacity: Int = 100,
        var congestion: Boolean = false,
        var readBufferSize: Int = 1,
        var writeBufferSize: Int = 1
    )

    data class WsSettingsBean(
        var path: String? = null,
        var host: String? = null,
        var headers: Map<String, String>? = null,
        val maxEarlyData: Int? = null,
        val useBrowserForwarding: Boolean? = null,
        val acceptProxyProtocol: Boolean? = null
    )

    data class HttpupgradeSettingsBean(
        var path: String? = null,
        var host: String? = null,
        val acceptProxyProtocol: Boolean? = null
    )

    data class XhttpSettingsBean(
        var path: String? = null,
        var host: String? = null,
        var mode: String? = null,
        var extra: Any? = null,
    )

    data class HttpSettingsBean(
        var host: List<String> = ArrayList(),
        var path: String? = null
    )

    data class SockoptBean(
        var TcpNoDelay: Boolean? = null,
        var tcpKeepAliveIdle: Int? = null,
        var tcpFastOpen: Boolean? = null,
        var tproxy: String? = null,
        var mark: Int? = null,
        var dialerProxy: String? = null,
        var domainStrategy: String? = null,
        var happyEyeballs: HappyEyeballsBean? = null,
    )

    data class HappyEyeballsBean(
        var prioritizeIPv6: Boolean? = null,
        var maxConcurrentTry: Int? = 4,
        var tryDelayMs: Int? = 250,
        var interleave: Int? = null,
    )

    data class TlsSettingsBean(
        var allowInsecure: Boolean = false,
        var serverName: String? = null,
        val alpn: List<String>? = null,
        val minVersion: String? = null,
        val maxVersion: String? = null,
        val preferServerCipherSuites: Boolean? = null,
        val cipherSuites: String? = null,
        val fingerprint: String? = null,
        val certificates: List<Any>? = null,
        val disableSystemRoot: Boolean? = null,
        val enableSessionResumption: Boolean? = null,
        var echConfigList: String? = null,
        var verifyPeerCertByName: String? = null,
        var pinnedPeerCertSha256: String? = null,
        var publicKey: String? = null,
        var shortId: String? = null,
        var spiderX: String? = null,
        var mldsa65Verify: String? = null
    )

    data class QuicSettingBean(
        var security: String = "none",
        var key: String = "",
        var header: HeaderBean = HeaderBean()
    ) {
        data class HeaderBean(var type: String = "none")
    }

    data class GrpcSettingsBean(
        var serviceName: String = "",
        var authority: String? = null,
        var multiMode: Boolean? = null,
        var idle_timeout: Int? = null,
        var health_check_timeout: Int? = null
    )

    data class HysteriaSettingsBean(
        var version: Int,
        var auth: String? = null
    )

    data class FinalMaskBean(
        var tcp: List<MaskBean>? = null,
        var udp: List<MaskBean>? = null,
        var quicParams: QuicParamsBean? = null
    ) {
        data class MaskBean(
            var type: String,
            var settings: MaskSettingsBean? = null
        ) {
            data class MaskSettingsBean(
                val password: String? = null,
                val header: String? = null,
                val value: String? = null,
                val packets: String? = null,
                val length: String? = null,
                val delay: String? = null,
                val maxSplit: String? = null,
                val reset: Int? = null,
                val noise: List<NoiseMaskBean>? = null
            ) {
                data class NoiseMaskBean(
                    val rand: String? = null,
                    val delay: String? = null,
                )
            }
        }

        data class QuicParamsBean(
            var congestion: String? = null,
            var brutalUp: String? = null,
            var brutalDown: String? = null,
            var udpHop: UdpHopBean? = null,
        ) {
            data class UdpHopBean(
                var ports: String? = null,
                var interval: String? = null
            )
        }
    }
}
