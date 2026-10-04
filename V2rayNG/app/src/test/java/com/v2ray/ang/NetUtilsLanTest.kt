package com.v2ray.ang

import com.v2ray.ang.util.NetUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetUtilsLanTest {

    @Test
    fun testGetLanIpAddressFormat() {
        val ip = NetUtils.getLanIpAddress()
        if (ip != null) {
            assertTrue(NetUtils.isIpv4Address(ip))
            assertTrue(ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))
        }
    }
}
