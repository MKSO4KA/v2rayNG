package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.NetUtils
import java.util.Locale

object SmartPoolNodeFilter {

    private val RESERVED_IP_CIDRS = emptyList<String>()

    fun isReservedOrLocalAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val h = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (h == "0.0.0.0" || h == "localhost" || h == "::" || h == "::1" || h == "0") return true
        if (h.startsWith("127.") || h.startsWith("0.")) return true
        if (h.startsWith("fe80:") || h.startsWith("fc00:") || h.startsWith("fd")) return true
        // Do not block private IPv4 ranges (10/8, 172.16/12, 192.168/16)
        return false
    }

    fun isValidProxyNode(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        val addr = profile.server?.trim()?.lowercase(Locale.ROOT) ?: return false
        val port = profile.serverPort?.toIntOrNull() ?: return false
        if (addr.isEmpty() || port <= 0 || port > 65535) return false

        if (isReservedOrLocalAddress(addr)) return false

        val blockedHosts = setOf("daysleft", "traffic", "expiry", "notice", "direct", "block")
        if (addr in blockedHosts) return false

        val remarks = profile.remarks.lowercase(Locale.ROOT)
        val blockedPhrases = listOf("осталось", "дней", "трафик", "подписка до", "days left")
        if (blockedPhrases.any { remarks.contains(it) } && !addr.contains(".")) {
            return false
        }

        return addr.contains(".") || addr.contains(":")
    }

    fun extractPhysicalEndpointKey(profile: ProfileItem): String {
        val proto = profile.configType.name.lowercase(Locale.ROOT)
        val host = profile.server?.trim()?.lowercase(Locale.ROOT) ?: ""
        val port = profile.serverPort?.trim() ?: "0"
        val user = profile.password ?: profile.username ?: ""
        val key = profile.publicKey ?: profile.sni ?: profile.host ?: ""
        return "$proto://$host:$port#u=$user#k=$key"
    }

    fun filterAndDeduplicate(nodes: List<ProfileItem>, filterRegex: String? = null): List<ProfileItem> {
        val validNodes = nodes.filter { isValidProxyNode(it) }
        val candidates = if (!filterRegex.isNullOrBlank()) {
            SmartRegexMatcher.filter(filterRegex, validNodes)
        } else {
            validNodes
        }
        val seenEndpoints = mutableMapOf<String, ProfileItem>()
        val unique = mutableListOf<ProfileItem>()

        for (node in candidates) {
            val endpKey = extractPhysicalEndpointKey(node)
            val existing = seenEndpoints[endpKey]
            if (existing != null) {
                if (isAutoGroupNode(existing.remarks) && !isAutoGroupNode(node.remarks)) {
                    existing.remarks = node.remarks
                }
                continue
            }
            seenEndpoints[endpKey] = node
            unique.add(node)
        }
        return unique
    }

    fun isAutoGroupNode(remarks: String): Boolean {
        val lower = remarks.lowercase(Locale.ROOT)
        return lower.contains("auto") ||
                lower.contains("авто") ||
                lower.contains("быстрая") ||
                lower.contains("быстрый") ||
                lower.contains("fastest") ||
                lower.contains("баланс") ||
                lower.contains("групп") ||
                lower.contains("selector") ||
                lower.contains("load balance") ||
                lower.contains("wi-fi") ||
                lower.contains("lte/4g")
    }
}
