package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreConfigGeoRemovalTest {

    @Test
    fun testPrivateIpListContainsStandardSubnets() {
        val list = AppConfig.PRIVATE_IP_LIST
        assertTrue(list.contains("10.0.0.0/8"), "Must contain 10.0.0.0/8")
        assertTrue(list.contains("172.16.0.0/12"), "Must contain 172.16.0.0/12")
        assertTrue(list.contains("192.168.0.0/16"), "Must contain 192.168.0.0/16")
        assertTrue(list.contains("127.0.0.0/8"), "Must contain 127.0.0.0/8")
        assertTrue(list.contains("fc00::/7"), "Must contain IPv6 ULA fc00::/7")
        assertTrue(list.contains("fe80::/10"), "Must contain IPv6 link-local fe80::/10")
    }

    @Test
    fun testPrivateIpListDoesNotContainGeoTags() {
        AppConfig.PRIVATE_IP_LIST.forEach { item ->
            assertFalse(item.startsWith("geoip:"), "PRIVATE_IP_LIST must not contain geoip prefixes: $item")
            assertFalse(item.startsWith("geosite:"), "PRIVATE_IP_LIST must not contain geosite prefixes: $item")
        }
    }
}
