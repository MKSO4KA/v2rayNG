package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.HY2
import com.v2ray.ang.R
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import java.net.URI

object AngConfigManager {

    private val SECURE_RANDOM = java.security.SecureRandom()
    private const val MASK_PREFIX = "[[MASK]]"
    private const val DIGITS = "0123456789"
    private const val LOWERS = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val ALPHAS = DIGITS + LOWERS + UPPERS

    fun share2Clipboard(context: Context, guid: String): Int {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return -1
            }
            Utils.setClipboard(context, conf)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            return -1
        }
        return 0
    }

    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) {
                    continue
                }
                sb.append(url)
                sb.appendLine()
            }
            if (sb.count() > 0) {
                Utils.setClipboard(context, sb.toString())
            }
            return sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            return -1
        }
    }

    fun share2QRCode(guid: String): Bitmap? {
        try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) {
                return null
            }
            return QRCodeDecoder.createQRCode(conf)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            return null
        }
    }

    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        try {
            if (guid == null) return -1
            val result = V2rayConfigManager.getV2rayConfig(context, guid)
            if (result.status) {
                Utils.setClipboard(context, result.content)
            } else {
                return -1
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            return -1
        }
        return 0
    }

    private fun shareConfig(guid: String): String {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""
            return config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.CUSTOM -> ""
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.HTTP -> ""
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                EConfigType.POLICYGROUP -> ""
                else -> {}
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            return ""
        }
    }

    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        var count = parseBatchConfig(Utils.decode(server), subid, append)
        if (count <= 0) {
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            count = parseCustomConfigServer(server, subid, append)
        }
        var countSub = parseBatchSubscription(server)
        if (countSub <= 0) {
            countSub = parseBatchSubscription(Utils.decode(server))
        }
        if (countSub > 0) {
            updateConfigViaSubAll()
        }
        return count to countSub
    }

    private fun parseBatchSubscription(servers: String?): Int {
        try {
            if (servers == null) {
                return 0
            }
            var count = 0
            servers.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { str ->
                    if (Utils.isValidSubUrl(str)) {
                        count += importUrlAsSubscription(str)
                    }
                }
            return count
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch subscription", e)
        }
        return 0
    }

    private fun parseBatchConfig(servers: String?, subid: String, append: Boolean): Int {
        try {
            if (servers == null) {
                return 0
            }
            val removedSelected = if (subid.isNotBlank() && !append) {
                MmkvManager.getSelectServer()
                    .takeIf { it?.isNotBlank() == true }
                    ?.let { MmkvManager.decodeServerConfig(it) }
                    ?.takeIf { it.subscriptionId == subid }
            } else {
                null
            }
            val subItem = MmkvManager.decodeSubscription(subid)
            val configs = mutableListOf<ProfileItem>()
            servers.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .reversed()
                .forEach {
                    val config = parseConfig(it, subid, subItem)
                    if (config != null) {
                        configs.add(config)
                    }
                }
            if (configs.isNotEmpty()) {
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val keyToProfile = batchSaveConfigs(configs, subid)
                val matchKey = findMatchedProfileKey(keyToProfile, removedSelected)
                matchKey?.let { MmkvManager.setSelectServer(it) }
            }
            return configs.size
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse batch config", e)
        }
        return 0
    }

    private fun batchSaveConfigs(configs: List<ProfileItem>, subid: String): Map<String, ProfileItem> {
        val keyToProfile = mutableMapOf<String, ProfileItem>()
        val serverList = MmkvManager.decodeServerList(subid)
        var needSetSelected = MmkvManager.getSelectServer().isNullOrBlank()
        configs.forEach { config ->
            val key = Utils.getUuid()
            MmkvManager.encodeProfileDirect(key, JsonUtil.toJson(config))
            if (!serverList.contains(key)) {
                serverList.add(0, key)
                if (needSetSelected) {
                    MmkvManager.setSelectServer(key)
                    needSetSelected = false
                }
            }
            keyToProfile[key] = config
        }
        MmkvManager.encodeServerList(serverList, subid)
        return keyToProfile
    }

    private fun findMatchedProfileKey(keyToProfile: Map<String, ProfileItem>, target: ProfileItem?): String? {
        if (keyToProfile.isEmpty() || target == null) return null
        if (target.remarks.isNotBlank()) {
            keyToProfile.entries.firstOrNull { (_, saved) ->
                isSameText(saved.remarks, target.remarks)
            }?.key?.let { return it }
        }
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort) &&
                    isSameText(saved.password, target.password)
        }?.key?.let { return it }
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server) &&
                    isSameText(saved.serverPort, target.serverPort)
        }?.key?.let { return it }
        keyToProfile.entries.firstOrNull { (_, saved) ->
            isSameText(saved.server, target.server)
        }?.key?.let { return it }
        return null
    }

    private fun isSameText(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        return left.trim().equals(right.trim(), ignoreCase = true)
    }

    private fun parseCustomConfigServer(server: String?, subid: String, append: Boolean): Int {
        if (server == null) {
            return 0
        }
        if (server.contains("inbounds")
            && server.contains("outbounds")
            && server.contains("routing")
        ) {
            try {
                val serverList: Array<Any> =
                    JsonUtil.fromJson(server, Array<Any>::class.java) ?: arrayOf()
                if (serverList.isNotEmpty()) {
                    if (!append) {
                        MmkvManager.removeServerViaSubid(subid)
                    }
                    var count = 0
                    for (srv in serverList.reversed()) {
                        val config = CustomFmt.parse(JsonUtil.toJson(srv)) ?: continue
                        config.subscriptionId = subid
                        config.description = generateDescription(config)
                        val key = MmkvManager.encodeServerConfig("", config)
                        MmkvManager.encodeServerRaw(key, JsonUtil.toJsonPretty(srv) ?: "")
                        count += 1
                    }
                    return count
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server JSON array", e)
            }
            try {
                val config = CustomFmt.parse(server) ?: return 0
                config.subscriptionId = subid
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse custom config server as single config", e)
            }
            return 0
        } else if (server.startsWith("[Interface]") && server.contains("[Peer]")) {
            try {
                val config = WireguardFmt.parseWireguardConfFile(server) ?: return R.string.toast_incorrect_protocol
                config.description = generateDescription(config)
                if (!append) {
                    MmkvManager.removeServerViaSubid(subid)
                }
                val key = MmkvManager.encodeServerConfig("", config)
                MmkvManager.encodeServerRaw(key, server)
                return 1
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to parse WireGuard config file", e)
            }
            return 0
        } else {
            return 0
        }
    }

    private fun parseConfig(
        str: String?,
        subid: String,
        subItem: SubscriptionItem?
    ): ProfileItem? {
        try {
            if (str == null || TextUtils.isEmpty(str)) {
                return null
            }
            val debugStr = if (str.length > 60) str.substring(0, 60) + "..." else str
            LogUtil.d(AppConfig.TAG, "Attempting to parse config string: $debugStr")

            val config = if (str.startsWith(EConfigType.VMESS.protocolScheme)) {
                VmessFmt.parse(str)
            } else if (str.startsWith(EConfigType.SHADOWSOCKS.protocolScheme)) {
                ShadowsocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.SOCKS.protocolScheme)) {
                SocksFmt.parse(str)
            } else if (str.startsWith(EConfigType.TROJAN.protocolScheme)) {
                TrojanFmt.parse(str)
            } else if (str.startsWith(EConfigType.VLESS.protocolScheme)) {
                VlessFmt.parse(str)
            } else if (str.startsWith(EConfigType.WIREGUARD.protocolScheme)) {
                WireguardFmt.parse(str)
            } else if (str.startsWith(EConfigType.HYSTERIA2.protocolScheme) || str.startsWith(HY2)) {
                Hysteria2Fmt.parse(str)
            } else {
                LogUtil.d(AppConfig.TAG, "Unknown scheme or unsupported config format: $debugStr")
                null
            }
            if (config == null) {
                return null
            }
            if (subItem?.filter.isNotNullEmpty() && config.remarks.isNotNullEmpty()) {
                val matched = Regex(pattern = subItem?.filter.orEmpty())
                    .containsMatchIn(input = config.remarks)
                if (!matched) {
                    LogUtil.d(AppConfig.TAG, "Config excluded by filter: ${config.remarks}")
                    return null
                }
            }
            config.subscriptionId = subid
            config.description = generateDescription(config)
            return config
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse config", e)
            return null
        }
    }

    private fun applyMimicryMask(input: String?): String? {
        if (input == null || !input.startsWith(MASK_PREFIX)) return input
        var output = input.substring(MASK_PREFIX.length)

        try {
            while (output.contains("<<D>>")) output = output.replaceFirst("<<D>>", DIGITS[SECURE_RANDOM.nextInt(DIGITS.length)].toString())
            while (output.contains("<<L>>")) output = output.replaceFirst("<<L>>", LOWERS[SECURE_RANDOM.nextInt(LOWERS.length)].toString())
            while (output.contains("<<U>>")) output = output.replaceFirst("<<U>>", UPPERS[SECURE_RANDOM.nextInt(UPPERS.length)].toString())
            while (output.contains("<<A>>")) output = output.replaceFirst("<<A>>", ALPHAS[SECURE_RANDOM.nextInt(ALPHAS.length)].toString())

            val rndRegex = Regex("<<RND:(\\d+)>>")
            output = rndRegex.replace(output) { matchResult ->
                val len = matchResult.groupValues[1].toIntOrNull() ?: 10
                (1..len).map { ALPHAS[SECURE_RANDOM.nextInt(ALPHAS.length)] }.joinToString("")
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to process Mimicry Mask for input: $input", e)
            return input
        }

        LogUtil.d(AppConfig.TAG, "Mimicry Mask Applied: Original='$input' -> Result='$output'")
        return output
    }

    private fun sanitizeHeaderValue(value: String): String {
        // Strip non-ASCII characters as OkHttp strictly rejects them causing IllegalStateException
        return value.replace(Regex("[^\\x20-\\x7E]"), "")
    }

    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        return try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            subscriptions.fold(SubscriptionUpdateResult()) { acc, subscription ->
                acc + updateConfigViaSub(subscription)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via all subscriptions", e)
            SubscriptionUpdateResult()
        }
    }

    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult {
        try {
            if (!it.subscription.enabled) {
                return SubscriptionUpdateResult(skipCount = 1)
            }
            if (TextUtils.isEmpty(it.guid)
                || TextUtils.isEmpty(it.subscription.remarks)
                || TextUtils.isEmpty(it.subscription.url)
            ) {
                return SubscriptionUpdateResult(skipCount = 1)
            }
            val url = HttpUtil.toIdnUrl(it.subscription.url)
            if (!Utils.isValidUrl(url)) {
                return SubscriptionUpdateResult(failureCount = 1)
            }
            if (!it.subscription.allowInsecureUrl) {
                if (!Utils.isValidSubUrl(url)) {
                    return SubscriptionUpdateResult(failureCount = 1)
                }
            }
            
            LogUtil.i(AppConfig.TAG, "Starting subscription update for: $url")
            
            val headers = mutableMapOf<String, String>()
            
            it.subscription.userAgent?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["User-Agent"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.model?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-Device-Model"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.hwid?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-HWID"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.os?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-Device-OS"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.osVer?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-Ver-OS"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.appVer?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-App-Version"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.encoding?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["Accept-Encoding"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.locale?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["X-Device-Locale"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }
            it.subscription.lang?.takeIf { v -> v.isNotBlank() }?.let { v -> headers["Accept-Language"] = sanitizeHeaderValue(applyMimicryMask(v) ?: v) }

            LogUtil.i(AppConfig.TAG, "Subscription Update Headers sent: $headers")

            // Smart proxy fallback to avoid 15s connection timeouts if core isn't running
            val useProxy = V2RayServiceManager.isRunning()
            val proxyUsername = if (useProxy) SettingsManager.getSocksUsername() else null
            val proxyPassword = if (useProxy) SettingsManager.getSocksPassword() else null
            val httpPort = if (useProxy) SettingsManager.getHttpPort() else 0

            var configText = try {
                HttpUtil.getUrlContentWithCustomHeaders(url, headers, 15000, httpPort, proxyUsername, proxyPassword)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Update subscription: network error during request", e)
                ""
            }
            
            // If proxy failed or wasn't running, retry instantly without proxy
            if (configText.isEmpty()) {
                if (useProxy) LogUtil.i(AppConfig.TAG, "Proxy update failed, retrying without proxy...")
                configText = try {
                    HttpUtil.getUrlContentWithCustomHeaders(url, headers)
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update subscription: Failed to get URL content directly", e)
                    ""
                }
            }
            
            if (configText.isEmpty()) {
                LogUtil.e(AppConfig.TAG, "Update subscription: Received empty configuration body.")
                return SubscriptionUpdateResult(failureCount = 1)
            }

            LogUtil.i(AppConfig.TAG, "Received subscription config text length: ${configText.length}")
            
            val count = parseConfigViaSub(configText, it.guid, false)
            if (count > 0) {
                it.subscription.lastUpdated = System.currentTimeMillis()
                MmkvManager.encodeSubscription(it.guid, it.subscription)
                AutoOutboundBuilder.ensurePolicyGroups(it.guid)
                GistRuleProvider.syncBlocklist(it.guid)
                LogUtil.i(AppConfig.TAG, "Subscription updated: ${it.subscription.remarks}, $count configs")
                return SubscriptionUpdateResult(
                    configCount = count,
                    successCount = 1
                )
            } else {
                LogUtil.e(AppConfig.TAG, "Update subscription: Failed to parse configuration from response string.")
                return SubscriptionUpdateResult(failureCount = 1)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to update config via subscription", e)
            return SubscriptionUpdateResult(failureCount = 1)
        }
    }

    private fun parseConfigViaSub(server: String?, subid: String, append: Boolean): Int {
        LogUtil.d(AppConfig.TAG, "Parsing config via sub. Input length: ${server?.length}")
        val decodedServer = Utils.decode(server)
        LogUtil.d(AppConfig.TAG, "Decoded config via sub length: ${decodedServer.length}")

        var count = parseBatchConfig(decodedServer, subid, append)
        if (count <= 0) {
            LogUtil.d(AppConfig.TAG, "Failed to parse decoded batch config, trying raw...")
            count = parseBatchConfig(server, subid, append)
        }
        if (count <= 0) {
            LogUtil.d(AppConfig.TAG, "Failed to parse raw batch config, trying custom config...")
            count = parseCustomConfigServer(server, subid, append)
        }
        LogUtil.i(AppConfig.TAG, "parseConfigViaSub resulting config count: $count")
        return count
    }

    private fun importUrlAsSubscription(url: String): Int {
        val subscriptions = MmkvManager.decodeSubscriptions()
        subscriptions.forEach {
            if (it.subscription.url == url) {
                return 0
            }
        }
        val uri = URI(Utils.fixIllegalUrl(url))
        val subItem = SubscriptionItem()
        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
        val guid = MmkvManager.encodeSubscription("", subItem)
        AutoOutboundBuilder.ensurePolicyGroups(guid)
        GistRuleProvider.syncBlocklist(guid)
        return 1
    }

    fun generateDescription(profile: ProfileItem): String {
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""
        val addrPart = server?.let {
            if (it.contains(":"))
                it.split(":").take(2).joinToString(":", postfix = ":***")
            else
                it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""
        return "$addrPart : ${port ?: ""}"
    }
}

