package com.v2ray.ang.smartpool

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigDomainResolver
import com.v2ray.ang.core.CoreOutboundBuilder
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.StreamSettingsBean
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.V2rayConfig.InboundBean
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.V2rayConfig.RoutingBean.RulesBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

object SmartPoolConfigBuilder {
    private const val DEFAULT_TEMPLATE = """
    {
      "log": { "loglevel": "error" },
      "dns": {
        "queryStrategy": "UseIP",
        "servers": ["tcp+local://1.1.1.1:53", "8.8.8.8", "1.1.1.1", "localhost"]
      },
      "inbounds": [{
        "tag": "in-template", "listen": "127.0.0.1", "port": 10808, "protocol": "socks",
        "settings": { "auth": "noauth", "udp": true },
        "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"] }
      }],
      "outbounds": [],
      "routing": { "domainStrategy": "IPIfNonMatch", "rules": [] }
    }
    """

    fun buildContext(context: Context, guid: String, profile: ProfileItem): CoreConfigContext {
        return CoreConfigContext(context = context, guid = guid, isCustom = true)
    }

    fun buildMultiInboundConfig(
        context: Context?,
        rawNodes: List<ProfileItem>,
        maxPorts: Int = SmartPoolConstants.DEFAULT_PORT_LIMIT
    ): V2rayConfig {
        val validNodes = SmartPoolNodeFilter.filterAndDeduplicate(rawNodes).take(maxPorts)
        val asset = context?.let { runCatching { Utils.readTextFromAssets(it, "v2ray_config.json") }.getOrNull() }
        val jsonText = if (!asset.isNullOrBlank()) asset else DEFAULT_TEMPLATE
        val v2rayConfig = JsonUtil.fromJson(jsonText, V2rayConfig::class.java)
            ?: JsonUtil.fromJson(DEFAULT_TEMPLATE, V2rayConfig::class.java)
            ?: error("Failed to load V2rayConfig template")

        val templateInbound = v2rayConfig.inbounds.firstOrNull()
        val templateInboundJson = if (templateInbound != null) JsonUtil.toJson(templateInbound) else null

        v2rayConfig.log.loglevel = "error"
        v2rayConfig.remarks = SmartPoolConstants.SMART_POOL_REMARKS
        v2rayConfig.inbounds.clear()
        v2rayConfig.outbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.routing.domainStrategy = "IPIfNonMatch"

        validNodes.forEachIndexed { i, node ->
            val port = SmartPoolConstants.BASE_POOL_PORT + i
            val inTag = "${SmartPoolConstants.TAG_IN_PREFIX}$port"
            val outTag = "${SmartPoolConstants.TAG_OUT_PREFIX}$port"

            val inBean = if (templateInboundJson != null) {
                JsonUtil.fromJson(templateInboundJson, InboundBean::class.java)
            } else null

            if (inBean != null) {
                inBean.tag = inTag
                inBean.listen = AppConfig.LOOPBACK
                inBean.port = port
                inBean.protocol = "socks"
                if (inBean.settings == null) {
                    inBean.settings = JsonUtil.fromJson("{\"auth\":\"noauth\",\"udp\":true}", InboundBean.InSettingsBean::class.java)
                } else {
                    inBean.settings?.auth = "noauth"
                    inBean.settings?.udp = true
                }
                if (inBean.sniffing == null) {
                    inBean.sniffing = JsonUtil.fromJson("{\"enabled\":true,\"destOverride\":[\"http\",\"tls\",\"quic\"]}", InboundBean.SniffingBean::class.java)
                } else {
                    inBean.sniffing?.enabled = true
                    inBean.sniffing?.destOverride?.clear()
                    inBean.sniffing?.destOverride?.addAll(listOf("http", "tls", "quic"))
                }
                v2rayConfig.inbounds.add(inBean)
            }

            val outbound = CoreOutboundBuilder.convert(node)
            if (outbound != null) {
                outbound.tag = outTag
                v2rayConfig.outbounds.add(outbound)
                v2rayConfig.routing.rules.add(RulesBean(type = "field", inboundTag = arrayListOf(inTag), outboundTag = outTag))
            }
        }

        v2rayConfig.outbounds.add(OutboundBean(protocol = "freedom", tag = "direct", settings = OutboundBean.OutSettingsBean(), streamSettings = StreamSettingsBean()))
        v2rayConfig.outbounds.add(OutboundBean(protocol = "blackhole", tag = "block", settings = OutboundBean.OutSettingsBean(), streamSettings = StreamSettingsBean()))
        v2rayConfig.routing.rules.add(RulesBean(outboundTag = AppConfig.TAG_DIRECT, ip = ArrayList(AppConfig.PRIVATE_IP_LIST)))
        CoreConfigDomainResolver.resolveOutboundDomainsToHosts(v2rayConfig)
        return v2rayConfig
    }
}
