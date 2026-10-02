package com.v2ray.ang.core

import android.content.Context
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

object CoreConfigManager {
    private var initConfigCache: String? = null
    private var initConfigCacheWithTun: String? = null

    fun getV2rayConfig(context: Context, guid: String): ConfigResult {
        return try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return CoreConfigCustomBuilder.buildV2rayCustomConfig(configContext, ::initV2rayConfig)
            }
            toConfigResult(configContext, buildUnifiedConfig(configContext))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config", e)
            ConfigResult(status = false, guid = guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
        return try {
            val configContext = CoreConfigContextBuilder.build(context, guid)
                ?: return ConfigResult(status = false, guid = guid, errorMessage = "Failed to build config context")
            if (configContext.isCustom) {
                return CoreConfigCustomBuilder.buildV2rayCustomConfig(configContext, ::initV2rayConfig)
            }
            val v2rayConfig = buildUnifiedConfig(configContext)
            postProcessForSpeedtest(v2rayConfig)
            toConfigResult(configContext, v2rayConfig)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
            ConfigResult(status = false, guid = guid, errorMessage = "Failed to get V2ray config: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildUnifiedConfig(configContext: CoreConfigContext): V2rayConfig {
        require(configContext.resolvedOutbounds.isNotEmpty()) { "resolvedOutbounds must not be empty for a non-CUSTOM context" }
        val primaryResolvedOutbound = configContext.resolvedOutbounds.first()
        val v2rayConfig = initV2rayConfig(configContext)
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.remarks = primaryResolvedOutbound.profile.remarks
        CoreConfigInboundBuilder.configureInbounds(v2rayConfig)
        if (v2rayConfig.outbounds.isNotEmpty()) v2rayConfig.outbounds.removeAt(0)
        val existingTags = v2rayConfig.outbounds.mapTo(mutableSetOf()) { it.tag }
        configContext.resolvedOutbounds.forEachIndexed { index, spec ->
            buildOutbounds(spec, index == 0, existingTags, v2rayConfig)
        }
        configureRouting(v2rayConfig)
        CoreConfigDnsBuilder.configureFakeDns(v2rayConfig)
        CoreConfigDnsBuilder.configureDns(v2rayConfig)
        CoreConfigDnsBuilder.configureLocalDns(v2rayConfig)
        applySpeedDisabled(v2rayConfig)
        CoreConfigDomainResolver.resolveOutboundDomainsToHosts(v2rayConfig)
        return v2rayConfig
    }

    private fun buildOutbounds(resolvedOutbound: CoreConfigContext.ResolvedOutbound, prepend: Boolean, existingTags: MutableSet<String>, v2rayConfig: V2rayConfig) {
        if (resolvedOutbound.tag in existingTags) return
        val profile = resolvedOutbound.resolvedProfiles.firstOrNull() ?: return
        val outbound = CoreOutboundBuilder.convert(profile) ?: return
        outbound.tag = resolvedOutbound.tag
        if (prepend) v2rayConfig.outbounds.add(0, outbound) else v2rayConfig.outbounds.add(outbound)
        existingTags.add(resolvedOutbound.tag)
    }

    private fun postProcessForSpeedtest(v2rayConfig: V2rayConfig) {
        v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
        v2rayConfig.inbounds.clear()
        v2rayConfig.routing.rules.clear()
        v2rayConfig.dns = null
        v2rayConfig.fakedns = null
        v2rayConfig.stats = null
    }

    private fun toConfigResult(configContext: CoreConfigContext, v2rayConfig: V2rayConfig): ConfigResult {
        return ConfigResult(status = true, guid = configContext.guid, content = JsonUtil.toJsonPretty(v2rayConfig) ?: "")
    }

    private fun initV2rayConfig(configContext: CoreConfigContext): V2rayConfig {
        val context = configContext.context
        val assets = if (CoreConfigInboundBuilder.needTun()) {
            initConfigCacheWithTun ?: Utils.readTextFromAssets(context, "v2ray_config_with_tun.json").also { initConfigCacheWithTun = it }
        } else {
            initConfigCache ?: Utils.readTextFromAssets(context, "v2ray_config.json").also { initConfigCache = it }
        }
        if (TextUtils.isEmpty(assets)) error("Missing config template asset")
        return JsonUtil.fromJson(assets, V2rayConfig::class.java) ?: error("Failed to parse config template")
    }

    private fun applySpeedDisabled(v2rayConfig: V2rayConfig) {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) != true) {
            v2rayConfig.stats = null
            v2rayConfig.policy?.system = null
        }
    }

    private fun configureRouting(v2rayConfig: V2rayConfig) {
        v2rayConfig.routing.domainStrategy = "AsIs"
        v2rayConfig.routing.rules.add(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT, ip = AppConfig.PRIVATE_IP_LIST))
    }

}

