package com.v2ray.ang.util

import android.util.Patterns
import android.webkit.URLUtil
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import java.io.IOException
import java.net.ServerSocket
import java.net.URI

object NetUtils {
    private val IPV4_REGEX = Regex("^([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])$")
    private val IPV6_REGEX = Regex("^((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*::((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*|((?:[0-9A-Fa-f]{1,4}))((?::[0-9A-Fa-f]{1,4})){7}$")

    fun isIpAddress(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return try {
            var addr = value.trim()
            if (addr.isEmpty()) return false
            if (addr.contains("/")) {
                val arr = addr.split("/")
                if (arr.size == 2 && arr[1].toIntOrNull() != null && arr[1].toInt() > -1) addr = arr[0]
            }
            if (addr.startsWith("::ffff:") && '.' in addr) addr = addr.drop(7)
            else if (addr.startsWith("[::ffff:") && '.' in addr) addr = addr.drop(8).replace("]", "")
            val octets = addr.split('.')
            if (octets.size == 4) {
                if (octets[3].contains(":")) addr = addr.substring(0, addr.indexOf(":"))
                return isIpv4Address(addr)
            }
            isIpv6Address(addr)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to validate IP address", e)
            false
        }
    }

    fun isPureIpAddress(value: String): Boolean = isIpv4Address(value) || isIpv6Address(value)

    fun isDomainName(input: String?): Boolean {
        if (input.isNullOrEmpty()) return false
        return !isPureIpAddress(input) && isValidUrl(input)
    }

    fun isIpv4Address(value: String): Boolean = IPV4_REGEX.matches(value)

    fun isIpv6Address(value: String): Boolean {
        var addr = value
        if (addr.startsWith("[")) {
            val closingBracket = addr.lastIndexOf(']')
            if (closingBracket <= 1) return false
            addr = addr.substring(1, closingBracket)
        }
        return IPV6_REGEX.matches(addr)
    }

    fun isCoreDNSAddress(s: String): Boolean = s.startsWith("https") || s.startsWith("tcp") || s.startsWith("quic") || s == "localhost"

    fun isValidUrl(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return try {
            Patterns.WEB_URL.matcher(value).matches() || Patterns.DOMAIN_NAME.matcher(value).matches() || URLUtil.isValidUrl(value)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to validate URL", e)
            false
        }
    }

    fun getIpv6Address(address: String?): String {
        if (address.isNullOrEmpty()) return ""
        return if (isIpv6Address(address) && !address.contains('[') && !address.contains(']')) "[$address]" else address
    }

    fun fixIllegalUrl(str: String): String = str.replace(" ", "%20").replace("|", "%7C")

    fun findRandomFreePort(): Int = ServerSocket(0).use { it.localPort }

    fun isValidSubUrl(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        return try {
            if (URLUtil.isHttpsUrl(value)) return true
            if (URLUtil.isHttpUrl(value)) {
                if (value.contains(LOOPBACK)) return true
                val uri = URI(fixIllegalUrl(value))
                if (isIpAddress(uri.host)) {
                    AppConfig.PRIVATE_IP_LIST.forEach { if (isIpInCidr(uri.host, it)) return true }
                }
            }
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to validate subscription URL", e)
            false
        }
    }

    fun isIpInCidr(ip: String, cidr: String): Boolean {
        val parts = cidr.split('/')
        if (parts.size != 2 || !isIpv4Address(ip) || !isIpv4Address(parts[0])) return false
        val prefixLength = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val mask = if (prefixLength == 0) 0L else (-1L shl (32 - prefixLength))
        return (ipv4ToLong(ip) and mask) == (ipv4ToLong(parts[0]) and mask)
    }

    private fun ipv4ToLong(ip: String): Long = ip.split('.').fold(0L) { result, octet -> (result shl 8) or octet.toLong() }
}
