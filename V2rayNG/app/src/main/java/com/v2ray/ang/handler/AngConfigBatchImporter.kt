package com.v2ray.ang.handler

import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.V2rayNFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object AngConfigBatchImporter {
    data class ParsedProfile(val profile: ProfileItem, val rawConfig: String? = null)

    private val configFmtParsers: Map<String, (String) -> ProfileItem?> by lazy {
        mapOf(
            EConfigType.VMESS.protocolScheme to VmessFmt::parse,
            EConfigType.SHADOWSOCKS.protocolScheme to ShadowsocksFmt::parse,
            EConfigType.SOCKS.protocolScheme to SocksFmt::parse,
            AppConfig.SOCKS4 to SocksFmt::parse,
            AppConfig.SOCKS5 to SocksFmt::parse,
            EConfigType.TROJAN.protocolScheme to TrojanFmt::parse,
            EConfigType.VLESS.protocolScheme to VlessFmt::parse,
            EConfigType.WIREGUARD.protocolScheme to WireguardFmt::parse,
            EConfigType.HYSTERIA2.protocolScheme to Hysteria2Fmt::parse,
            AppConfig.HY2 to Hysteria2Fmt::parse,
        )
    }

    fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        if (servers == null) return 0
        val subItem = MmkvManager.decodeSubscription(subid)
        val configs = mutableListOf<ProfileItem>()
        val v2raynLines = mutableListOf<String>()
        servers.lines().distinct().reversed().forEach { line ->
            if (line.startsWith(AppConfig.V2RAYNFMTS, ignoreCase = true)) v2raynLines.add(line)
            else parseConfig(line, subid, subItem)?.let { configs.add(it) }
        }
        val allConfigs = V2rayNFmt.parse(v2raynLines, subid) + configs
        if (allConfigs.isNotEmpty()) commitProfiles(allConfigs.map(::ParsedProfile), subid, append)
        return allConfigs.size
    }

    fun commitProfiles(configs: List<ParsedProfile>, subid: String, append: Boolean) {
        val keyToProfile = linkedMapOf<String, ProfileItem>()
        val rawConfigs = mutableMapOf<String, String>()
        configs.forEach { parsed ->
            val key = Utils.getUuid()
            keyToProfile[key] = parsed.profile
            parsed.rawConfig?.let { raw -> rawConfigs[key] = raw }
        }
        MmkvManager.saveServerProfiles(keyToProfile, rawConfigs, subid, append)
    }

    fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) return 0
        if (server.contains("inbounds") && server.contains("outbounds") && server.contains("routing")) {
            val serverList: Array<Any> = JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()
            if (serverList.isNotEmpty()) {
                val configs = serverList.reversed().map { srv ->
                    val config = CustomFmt.parse(JsonUtil.toJson(srv))
                    config.subscriptionId = subid
                    config.description = AngConfigManager.generateDescription(config)
                    ParsedProfile(config, JsonUtil.toJsonPretty(srv) ?: "")
                }
                commitProfiles(configs, subid, append)
                return configs.size
            }
            val config = CustomFmt.parse(server)
            config.subscriptionId = subid
            config.description = AngConfigManager.generateDescription(config)
            commitProfiles(listOf(ParsedProfile(config, server)), subid, append)
            return 1
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            val config = WireguardFmt.parseWireguardConfFile(server)
            config.subscriptionId = subid
            config.description = AngConfigManager.generateDescription(config)
            commitProfiles(listOf(ParsedProfile(config, server)), subid, append)
            return 1
        }
        return 0
    }

    fun parseConfig(str: String?, subid: String, subItem: SubscriptionItem?): ProfileItem? {
        if (str.isNullOrEmpty()) return null
        val config = configFmtParsers.firstNotNullOfOrNull { (scheme, parser) ->
            if (str.startsWith(scheme)) parser(str) else null
        } ?: return null
        if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
            val matched = Regex(subItem?.filter.orEmpty()).containsMatchIn(config.remarks)
            if (!matched) return null
        }
        config.subscriptionId = subid
        config.description = AngConfigManager.generateDescription(config)
        return config
    }
}
