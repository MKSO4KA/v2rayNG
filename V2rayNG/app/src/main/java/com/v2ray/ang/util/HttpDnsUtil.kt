package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

object HttpDnsUtil {
    fun toIdnUrl(str: String): String {
        val url = URL(str)
        val host = url.host
        val asciiHost = IDN.toASCII(url.host, IDN.ALLOW_UNASSIGNED)
        return if (host != asciiHost) str.replace(host, asciiHost) else str
    }

    fun toIdnDomain(domain: String): String {
        if (Utils.isPureIpAddress(domain)) return domain
        if (domain.all { it.code < 128 }) return domain
        return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED)
    }

    fun resolveHostToIP(host: String, ipv6Preferred: Boolean = false): List<String>? {
        return try {
            if (Utils.isPureIpAddress(host)) return null
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty()) return null
            val sortedAddresses = if (ipv6Preferred) {
                addresses.sortedWith(compareByDescending { it is Inet6Address })
            } else {
                addresses.sortedWith(compareBy { it is Inet6Address })
            }
            val ipList = sortedAddresses.mapNotNull { it.hostAddress }
            LogUtil.i(AppConfig.TAG, "Resolved IPs for $host: ${ipList.joinToString()}")
            ipList
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve host to IP", e)
            null
        }
    }
}
