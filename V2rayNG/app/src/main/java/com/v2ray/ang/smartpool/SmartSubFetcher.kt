package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.util.Utils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SmartSubFetcher {

    fun fetchRawContentWithCascade(subUrl: String, secret: String? = null, profile: MimicryProfile? = null): String {
        val prof = profile ?: MimicryProfile()
        // Уровень 1: Прямой запрос с мимикрией
        var content = runCatching { fetchDirect(subUrl, prof) }.getOrNull()

        // Уровень 2: Через активное ядро (VPN или ProxyOnly), если оно уже запущено
        if (content.isNullOrBlank()) {
            content = fetchViaActiveCoreFallback(subUrl, prof)
        }

        // Уровень 3: Скрытый эфемерный SmartPool без тумблера VPN, если ядро выключено
        if (content.isNullOrBlank()) {
            content = kotlinx.coroutines.runBlocking {
                SmartEphemeralPoolRunner.fetchViaEphemeralPool(subUrl, prof)
            }
        }
        if (content.isNullOrBlank()) return ""

        if (!secret.isNullOrBlank()) {
            val decrypted = runCatching { decryptAesGcm(content, secret) }.getOrNull()
            if (!decrypted.isNullOrBlank()) content = decrypted
        }
        return content
    }

    fun fetchWithTierCascade(subUrl: String, secret: String? = null, profile: MimicryProfile? = null): List<ProfileItem> {
        val content = fetchRawContentWithCascade(subUrl, secret, profile)
        return parseNodesFromPayload(content)
    }

    fun parseNodesFromPayload(content: String): List<ProfileItem> {
        if (content.isBlank()) return emptyList()

        val decoded = Utils.decode(content)
        val candidatePayloads = listOfNotNull(
            content.takeIf { it.isNotBlank() },
            decoded.takeIf { it.isNotBlank() }
        ).distinct()

        for (payload in candidatePayloads) {
            val nodes = parseNodesFromText(payload)
            if (nodes.isNotEmpty()) {
                return SmartPoolNodeFilter.filterAndDeduplicate(nodes)
            }
        }
        return emptyList()
    }

    private fun parseNodesFromText(rawText: String): List<ProfileItem> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return emptyList()

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val jsonNodes = parseJsonOutbounds(trimmed)
            if (jsonNodes.isNotEmpty()) {
                return jsonNodes
            }
        }

        val result = mutableListOf<ProfileItem>()
        val lines = rawText.lines()
        for (line in lines) {
            val t = line.trim()
            if (t.isBlank() || t.startsWith("#")) continue
            when {
                t.startsWith("vless://", ignoreCase = true) -> com.v2ray.ang.fmt.VlessFmt.parse(t)?.let { result.add(it) }
                t.startsWith("vmess://", ignoreCase = true) -> com.v2ray.ang.fmt.VmessFmt.parse(t)?.let { result.add(it) }
                t.startsWith("trojan://", ignoreCase = true) -> com.v2ray.ang.fmt.TrojanFmt.parse(t)?.let { result.add(it) }
                t.startsWith("ss://", ignoreCase = true) -> com.v2ray.ang.fmt.ShadowsocksFmt.parse(t)?.let { result.add(it) }
                t.startsWith("hysteria2://", ignoreCase = true) || t.startsWith("hy2://", ignoreCase = true) -> com.v2ray.ang.fmt.Hysteria2Fmt.parse(t)?.let { result.add(it) }
                t.startsWith("wireguard://", ignoreCase = true) -> com.v2ray.ang.fmt.WireguardFmt.parse(t)?.let { result.add(it) }
                t.startsWith("socks://", ignoreCase = true) -> com.v2ray.ang.fmt.SocksFmt.parse(t)?.let { result.add(it) }
            }
        }
        return result
    }

    private fun parseJsonOutbounds(jsonStr: String): List<ProfileItem> {
        val result = mutableListOf<ProfileItem>()
        runCatching {
            if (jsonStr.startsWith("[")) {
                val list = com.v2ray.ang.util.JsonUtil.fromJson(jsonStr, Array<Any>::class.java)
                if (list != null) {
                    for ((index, item) in list.withIndex()) {
                        val subJson = com.v2ray.ang.util.JsonUtil.toJson(item) ?: continue
                        result.addAll(extractOutboundsFromJson(subJson, "node-${index + 1}"))
                    }
                }
            } else if (jsonStr.startsWith("{")) {
                result.addAll(extractOutboundsFromJson(jsonStr, "happ_node"))
            }
        }
        return result
    }

    private fun isGenericTag(tag: String?): Boolean {
        if (tag.isNullOrBlank()) return true
        val lower = tag.lowercase(java.util.Locale.ROOT)
        return lower == "proxy" || lower == "direct" || lower == "block" || lower == "dns" ||
                lower.startsWith("proxy-") || lower.startsWith("proxy_") ||
                (lower.startsWith("proxy") && lower.drop(5).all { it.isDigit() }) ||
                lower.startsWith("node-") || lower.startsWith("node_")
    }

    private fun extractOutboundsFromJson(jsonObjStr: String, fallbackRemarks: String): List<ProfileItem> {
        val list = mutableListOf<ProfileItem>()
        val root = com.v2ray.ang.util.JsonUtil.fromJson(jsonObjStr, Map::class.java) ?: return emptyList()
        val rootRemarks = ((root["remarks"] ?: root["name"] ?: root["ps"]) as? String)?.takeIf { it.isNotBlank() }
        val defaultRemarks = rootRemarks ?: fallbackRemarks

        val outbounds = root["outbounds"] as? List<*> ?: return emptyList()

        // Проход 1: Собираем карту красивых названий из селекторов и групп
        val tagToFriendlyName = mutableMapOf<String, String>()
        for (item in outbounds) {
            val ob = item as? Map<*, *> ?: continue
            val proto = ((ob["protocol"] as? String) ?: (ob["type"] as? String))?.lowercase(java.util.Locale.ROOT)
            if (proto == "selector" || proto == "urltest" || proto == "loadbalance" || proto == "fallback") {
                val selectorTag = (ob["tag"] as? String)?.trim() ?: continue
                val targets = ob["outbounds"] as? List<*> ?: continue
                for (target in targets) {
                    val targetTag = target as? String ?: continue
                    if (!SmartPoolNodeFilter.isAutoGroupNode(selectorTag) && !tagToFriendlyName.containsKey(targetTag)) {
                        tagToFriendlyName[targetTag] = selectorTag
                    }
                }
            }
        }

        // Проход 2: Создаем профили для реальных физических прокси
        for (item in outbounds) {
            val ob = item as? Map<*, *> ?: continue
            val proto = ((ob["protocol"] as? String) ?: (ob["type"] as? String))?.lowercase(java.util.Locale.ROOT) ?: continue
            if (proto == "freedom" || proto == "blackhole" || proto == "direct" || proto == "block" || proto == "dns" || proto.isBlank()) continue
            if (proto == "selector" || proto == "urltest" || proto == "loadbalance" || proto == "fallback") continue

            val tag = ob["tag"] as? String
            val directName = ((ob["remarks"] ?: ob["name"] ?: ob["ps"]) as? String)?.takeIf { it.isNotBlank() }
            val selectorName = tagToFriendlyName[tag]?.takeIf { it.isNotBlank() }
            val outboundRemarks = directName
                ?: selectorName
                ?: rootRemarks
                ?: tag?.takeIf { !isGenericTag(it) }
                ?: defaultRemarks

            val profile = createProfileFromOutboundMap(ob, proto, outboundRemarks)
            if (profile != null) {
                list.add(profile)
            }
        }
        return list
    }

    private fun createProfileFromOutboundMap(ob: Map<*, *>, proto: String, remarks: String): ProfileItem? {
        val eType = when (proto) {
            "vless" -> com.v2ray.ang.enums.EConfigType.VLESS
            "vmess" -> com.v2ray.ang.enums.EConfigType.VMESS
            "trojan" -> com.v2ray.ang.enums.EConfigType.TROJAN
            "shadowsocks" -> com.v2ray.ang.enums.EConfigType.SHADOWSOCKS
            "hysteria", "hysteria2", "hy2" -> com.v2ray.ang.enums.EConfigType.HYSTERIA2
            "wireguard" -> com.v2ray.ang.enums.EConfigType.WIREGUARD
            "socks" -> com.v2ray.ang.enums.EConfigType.SOCKS
            else -> return null
        }
        val config = ProfileItem.create(eType)
        config.remarks = remarks

        // Sing-box direct properties
        config.server = (ob["server"] as? String)?.takeIf { it.isNotBlank() }
        config.serverPort = ((ob["server_port"] as? Number)?.toInt()?.toString()) ?: (ob["server_port"] as? String)
        config.password = (ob["uuid"] as? String) ?: (ob["password"] as? String) ?: (ob["auth"] as? String)
        config.flow = ob["flow"] as? String
        config.method = ob["method"] as? String

        // Sing-box TLS / Reality structure
        val tlsObj = ob["tls"] as? Map<*, *>
        if (tlsObj != null) {
            val tlsEnabled = (tlsObj["enabled"] as? Boolean) ?: true
            if (tlsEnabled) {
                config.security = com.v2ray.ang.AppConfig.TLS
                config.sni = (tlsObj["server_name"] as? String) ?: (tlsObj["serverName"] as? String)
                val utlsObj = tlsObj["utls"] as? Map<*, *>
                config.fingerPrint = (utlsObj?.get("fingerprint") as? String) ?: (tlsObj["fingerprint"] as? String)
                val realityObj = tlsObj["reality"] as? Map<*, *>
                if (realityObj != null && (realityObj["enabled"] as? Boolean) != false) {
                    config.security = com.v2ray.ang.AppConfig.REALITY
                    config.publicKey = realityObj["public_key"] as? String ?: realityObj["publicKey"] as? String
                    config.shortId = realityObj["short_id"] as? String ?: realityObj["shortId"] as? String
                }
            }
        }

        // Sing-box Transport structure
        val transportObj = ob["transport"] as? Map<*, *>
        if (transportObj != null) {
            config.network = (transportObj["type"] as? String) ?: "tcp"
            config.path = transportObj["path"] as? String
            val headers = transportObj["headers"] as? Map<*, *>
            config.host = (headers?.get("Host") as? String) ?: (headers?.get("host") as? String)
            config.serviceName = (transportObj["service_name"] as? String) ?: (transportObj["serviceName"] as? String)
        }

        // Xray / V2ray settings
        val settings = ob["settings"] as? Map<*, *>
        if (settings != null) {
            val vnext = settings["vnext"] as? List<*>
            if (!vnext.isNullOrEmpty()) {
                val first = vnext[0] as? Map<*, *>
                if (config.server.isNullOrBlank()) config.server = first?.get("address") as? String
                if (config.serverPort.isNullOrBlank()) config.serverPort = (first?.get("port") as? Number)?.toInt()?.toString()
                val users = first?.get("users") as? List<*>
                if (!users.isNullOrEmpty()) {
                    val u = users[0] as? Map<*, *>
                    if (config.password.isNullOrBlank()) config.password = u?.get("id") as? String
                    if (config.method.isNullOrBlank()) config.method = (u?.get("encryption") as? String) ?: "none"
                    if (config.flow.isNullOrBlank()) config.flow = u?.get("flow") as? String
                }
            }

            val servers = settings["servers"] as? List<*>
            if (!servers.isNullOrEmpty()) {
                val first = servers[0] as? Map<*, *>
                if (config.server.isNullOrBlank()) config.server = first?.get("address") as? String
                if (config.serverPort.isNullOrBlank()) config.serverPort = (first?.get("port") as? Number)?.toInt()?.toString()
                if (config.password.isNullOrBlank()) config.password = first?.get("password") as? String
                if (config.method.isNullOrBlank()) config.method = first?.get("method") as? String
                if (config.flow.isNullOrBlank()) config.flow = first?.get("flow") as? String
            }

            if (config.server.isNullOrBlank()) {
                config.server = settings["address"] as? String
                config.serverPort = (settings["port"] as? Number)?.toInt()?.toString()
                config.password = (settings["auth"] as? String) ?: (settings["password"] as? String)
            }
        }

        // Xray / V2ray streamSettings
        val stream = ob["streamSettings"] as? Map<*, *>
        if (stream != null) {
            config.network = (stream["network"] as? String) ?: config.network ?: "tcp"
            config.security = (stream["security"] as? String) ?: config.security

            val reality = stream["realitySettings"] as? Map<*, *>
            if (reality != null) {
                config.security = com.v2ray.ang.AppConfig.REALITY
                config.publicKey = reality["publicKey"] as? String ?: reality["public_key"] as? String
                config.shortId = reality["shortId"] as? String ?: reality["short_id"] as? String
                config.spiderX = reality["spiderX"] as? String
                config.fingerPrint = reality["fingerprint"] as? String
                config.sni = reality["serverName"] as? String ?: reality["server_name"] as? String
            }

            val tls = stream["tlsSettings"] as? Map<*, *>
            if (tls != null) {
                config.sni = tls["serverName"] as? String ?: tls["server_name"] as? String
                config.fingerPrint = tls["fingerprint"] as? String
                val alpn = tls["alpn"] as? List<*>
                if (!alpn.isNullOrEmpty()) {
                    config.alpn = alpn.joinToString(",")
                }
            }

            val ws = stream["wsSettings"] as? Map<*, *>
            if (ws != null) {
                config.path = ws["path"] as? String
                val headers = ws["headers"] as? Map<*, *>
                config.host = (headers?.get("Host") as? String) ?: (headers?.get("host") as? String)
            }

            val grpc = stream["grpcSettings"] as? Map<*, *>
            if (grpc != null) {
                config.serviceName = (grpc["serviceName"] as? String) ?: (grpc["service_name"] as? String)
                config.authority = grpc["authority"] as? String
                config.mode = grpc["mode"] as? String
            }

            val xhttp = stream["xhttpSettings"] as? Map<*, *>
            if (xhttp != null) {
                config.path = xhttp["path"] as? String
                config.host = xhttp["host"] as? String
                config.xhttpMode = xhttp["mode"] as? String
            }

            val hysteria = stream["hysteriaSettings"] as? Map<*, *>
            if (hysteria != null) {
                if (config.password.isNullOrBlank()) {
                    config.password = hysteria["auth"] as? String
                }
            }
        }

        if (config.server.isNullOrBlank() || config.serverPort.isNullOrBlank()) {
            return null
        }
        return config
    }

    private fun fetchDirect(subUrl: String, prof: MimicryProfile): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val req = Request.Builder()
            .url(subUrl)
            .let { applyMimicryProfile(it, prof) }
            .build()
        return client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return@use ""
            decompressIfNeeded(bytes, resp.header("Content-Encoding"))
        }
    }

    private fun fetchViaActiveCoreFallback(subUrl: String, prof: MimicryProfile): String? {
        if (!com.v2ray.ang.core.CoreServiceManager.isRunning()) return null
        val httpPort = com.v2ray.ang.handler.SettingsManager.getHttpPort()
        val targetPort = if (httpPort > 0) httpPort else SmartPoolConstants.BASE_POOL_PORT
        val proxyType = if (httpPort > 0) Proxy.Type.HTTP else Proxy.Type.SOCKS

        return runCatching {
            val proxy = Proxy(proxyType, InetSocketAddress("127.0.0.1", targetPort))
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            val req = Request.Builder()
                .url(subUrl)
                .let { applyMimicryProfile(it, prof) }
                .build()
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: return@use ""
                decompressBytes(bytes, resp.header("Content-Encoding"))
            }
        }.getOrNull()
    }

    fun decompressBytes(bytes: ByteArray, contentEncoding: String?): String {
        return decompressIfNeeded(bytes, contentEncoding)
    }

    private fun decompressIfNeeded(bytes: ByteArray, contentEncoding: String?): String {
        if ((bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) || contentEncoding?.contains("gzip") == true) {
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault(String(bytes, Charsets.UTF_8))
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun decryptAesGcm(encryptedHex: String, secret: String): String {
        val trimmed = encryptedHex.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val data = trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
        val keySpec = SecretKeySpec(sha, "AES")
        val gcmSpec = GCMParameterSpec(128, sha, 0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    fun applyMimicryProfile(builder: Request.Builder, prof: MimicryProfile): Request.Builder {
        if (prof.userAgent.isNotBlank()) builder.header("User-Agent", prof.userAgent)
        if (prof.lang.isNotBlank()) builder.header("Accept-Language", prof.lang)
        if (prof.encoding.isNotBlank()) builder.header("Accept-Encoding", prof.encoding)
        if (prof.model.isNotBlank()) builder.header("X-Device-Model", prof.model)
        if (prof.hwid.isNotBlank()) builder.header("X-HWID", prof.hwid)
        if (prof.os.isNotBlank()) builder.header("X-Device-OS", prof.os)
        if (prof.osVer.isNotBlank()) builder.header("X-Ver-OS", prof.osVer)
        if (prof.appVer.isNotBlank()) builder.header("X-App-Version", prof.appVer)
        if (prof.locale.isNotBlank()) builder.header("X-Device-Locale", prof.locale)
        return builder
    }
}
