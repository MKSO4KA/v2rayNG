package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Build runtime context from the selected profile.
 *
 * All outbound type analysis is completed here for both the selected profile
 * and routing targets. Custom profiles are returned immediately without
 * entering the normal analysis flow.
 */
object CoreConfigContextBuilder {

    /**
     * Load one profile and produce a fully analyzed context.
     *
     * Null is returned only when the selected profile cannot be loaded.
     */
    fun build(context: Context, guid: String): CoreConfigContext? {
        val config = MmkvManager.decodeServerConfig(guid) ?: return null

        // CUSTOM: return immediately — CoreConfigManager handles this path on its own.
        if (config.configType == EConfigType.CUSTOM) {
            return CoreConfigContext(context = context, guid = guid, isCustom = true)
        }

        // Step 1: Resolve the main outbound (always tag = TAG_PROXY).
        val primaryResolvedOutbound = resolveOutbound(AppConfig.TAG_PROXY, config) ?: run {
            LogUtil.e(AppConfig.TAG, "Failed to resolve main outbound for '${config.remarks}'")
            return null
        }

        return CoreConfigContext(
            context = context,
            guid = guid,
            resolvedOutbounds = listOf(primaryResolvedOutbound),
        )
    }

    /**
     * Resolve one outbound target into a normalized outbound entry.
     *
     * Custom profiles are ignored at this stage and produce no entry.
     */
    private fun resolveOutbound(tag: String, profile: ProfileItem): CoreConfigContext.ResolvedOutbound? {
        if (profile.configType == EConfigType.CUSTOM) {
            return null
        }

        val resolvedProfiles = listOf(profile)
        val resolvedType = CoreResolvedType.NORMAL

        return CoreConfigContext.ResolvedOutbound(
            tag = tag,
            profile = profile,
            resolvedProfiles = resolvedProfiles,
            resolvedType = resolvedType,
        )
    }

}
