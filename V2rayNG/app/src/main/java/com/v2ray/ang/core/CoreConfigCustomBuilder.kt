package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil

object CoreConfigCustomBuilder {
    fun buildV2rayCustomConfig(configContext: CoreConfigContext, initConfigFn: (CoreConfigContext) -> com.v2ray.ang.dto.V2rayConfig): ConfigResult {
        val config = MmkvManager.decodeServerConfig(configContext.guid)
        if (config?.configType == com.v2ray.ang.enums.EConfigType.SMART_POOL) {
            val targetSub = config.smartPoolTargetSubId ?: config.subscriptionId
            val limit = config.smartPoolPortLimit ?: com.v2ray.ang.smartpool.SmartPoolConstants.DEFAULT_PORT_LIMIT
            val candidates = com.v2ray.ang.smartpool.SmartPoolManager.getValidPoolCandidates(config.smartPoolFilterRegex, targetSub)
            LogUtil.i(AppConfig.TAG, "SmartPool: building multi-inbound config for '${config.remarks}', found ${candidates.size} nodes (limit: $limit)")
            val v2rayConfig = com.v2ray.ang.smartpool.SmartPoolConfigBuilder.buildMultiInboundConfig(configContext.context, candidates, limit)
            val poolJson = JsonUtil.toJsonPretty(v2rayConfig).orEmpty()
            return ConfigResult(status = true, guid = configContext.guid, content = poolJson)
        }

        val raw = MmkvManager.decodeServerRaw(configContext.guid)
            ?: return ConfigResult(status = false, guid = configContext.guid, errorMessage = "Failed to build config context, config is empty")

        val result = ConfigResult(true, configContext.guid, raw)
        val json = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return result

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_SPEED_ENABLED) == true) {
            if (!json.has("stats")) json.add("stats", JsonObject())
            if (!json.has("policy")) {
                val policyObj = JsonObject()
                val systemObj = JsonObject()
                systemObj.addProperty("statsOutboundUplink", true)
                systemObj.addProperty("statsOutboundDownlink", true)
                policyObj.add("system", systemObj)
                json.add("policy", policyObj)
            }
        } else {
            json.remove("stats")
            json.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject?.let { policy ->
                policy.remove("system")
                if (policy.entrySet().isEmpty()) json.remove("policy")
            }
        }

        if (!CoreConfigInboundBuilder.needTun()) {
            return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
        }

        val inboundsJson = json.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { json.add("inbounds", it) }
        val tunNotExists = inboundsJson.none {
            it.isJsonObject && it.asJsonObject.get("protocol")?.takeIf { p -> p.isJsonPrimitive && p.asJsonPrimitive.isString }?.asString == "tun"
        }

        if (tunNotExists) {
            val templateConfig = initConfigFn(configContext)
            templateConfig.inbounds.firstOrNull { it.tag == "tun" }?.let { inboundTun ->
                inboundTun.settings?.mtu = SettingsManager.getVpnMtu()
                inboundsJson.add(JsonUtil.parseString(JsonUtil.toJson(inboundTun)))
            }
        }
        return JsonUtil.toJsonPretty(json)?.let { ConfigResult(true, configContext.guid, it) } ?: result
    }
}
