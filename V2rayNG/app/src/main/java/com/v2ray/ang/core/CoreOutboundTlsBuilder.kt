package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.StreamSettingsBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object CoreOutboundTlsBuilder {
    fun populateTlsSettings(streamSettings: StreamSettingsBean, profileItem: ProfileItem, sniExt: String?) {
        val streamSecurity = profileItem.security.orEmpty()
        if (profileItem.insecure == true && profileItem.pinnedCA256.isNullOrEmpty()) {
            com.v2ray.ang.smartpool.crash.SmartPoolLogRingBuffer.log(
                "[HotFix] Node '${profileItem.remarks}': disabled deprecated allowInsecure to prevent Xray rejection; standard CA verification enabled"
            )
        }
        val allowInsecure = false
        val sni = if (profileItem.sni.isNullOrEmpty()) {
            when {
                sniExt.isNotNullEmpty() && Utils.isDomainName(sniExt) -> sniExt
                profileItem.server.isNotNullEmpty() && Utils.isDomainName(profileItem.server) -> profileItem.server
                else -> sniExt
            }
        } else {
            profileItem.sni
        }
        streamSettings.security = streamSecurity.nullIfBlank() ?: return
        val tlsSetting = StreamSettingsBean.TlsSettingsBean(
            allowInsecure = allowInsecure,
            serverName = sni.nullIfBlank(),
            fingerprint = profileItem.fingerPrint.nullIfBlank(),
            alpn = profileItem.alpn?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() },
            echConfigList = profileItem.echConfigList.nullIfBlank(),
            verifyPeerCertByName = profileItem.verifyPeerCertByName.nullIfBlank(),
            pinnedPeerCertSha256 = profileItem.pinnedCA256.nullIfBlank(),
            publicKey = profileItem.publicKey.nullIfBlank(),
            shortId = profileItem.shortId.nullIfBlank(),
            spiderX = profileItem.spiderX.nullIfBlank(),
            mldsa65Verify = profileItem.mldsa65Verify.nullIfBlank(),
        )
        if (streamSettings.security == AppConfig.TLS) {
            streamSettings.tlsSettings = tlsSetting
            streamSettings.realitySettings = null
        } else if (streamSettings.security == AppConfig.REALITY) {
            streamSettings.tlsSettings = null
            streamSettings.realitySettings = tlsSetting
        }
    }

    fun getServerAddress(profileItem: ProfileItem): String {
        if (Utils.isPureIpAddress(profileItem.server.orEmpty())) return profileItem.server.orEmpty()
        val domain = HttpUtil.toIdnDomain(profileItem.server.orEmpty())
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD) != "2") return domain
        val resolvedIps = HttpUtil.resolveHostToIP(domain, MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6))
        return if (resolvedIps.isNullOrEmpty()) domain else resolvedIps.first()
    }

    fun updateOutboundFinalMask(streamSettings: StreamSettingsBean, profileItem: ProfileItem) {
        profileItem.finalMask?.let { mask ->
            val parsed = JsonUtil.parseString(mask)
            if (parsed != null) streamSettings.finalmask = parsed
            else LogUtil.w("V2rayConfigManager", "Invalid finalMask JSON, keeping previously generated finalmask")
        }
    }
}
