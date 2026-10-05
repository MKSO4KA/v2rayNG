package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolSecurityAuthTest {

    @Test
    fun testInternalInboundsEnforcePasswordAuth() {
        val (user, pass) = SmartPoolManager.getPoolAuthCredentials()
        assertTrue(user.isNotBlank())
        assertTrue(pass.isNotBlank())

        val dummyNodes = listOf(
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Node-1"
                server = "1.2.3.4"
                serverPort = "443"
                password = "uuid-1"
            },
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Node-2"
                server = "5.6.7.8"
                serverPort = "443"
                password = "uuid-2"
            }
        )

        val config = SmartPoolConfigBuilder.buildMultiInboundConfig(null, dummyNodes, maxPorts = 2)
        assertEquals(2, config.inbounds.size)

        for (inbound in config.inbounds) {
            assertEquals("password", inbound.settings?.auth, "Internal ports 30001+ must require password auth")
            assertNotNull(inbound.settings?.accounts)
            val account = inbound.settings?.accounts?.firstOrNull()
            assertNotNull(account)
            assertEquals(user, account?.user)
            assertEquals(pass, account?.pass)
            assertFalse(inbound.settings?.auth == "noauth", "Internal ports must never allow noauth")
        }
    }

    @Test
    fun testDnsQueryStrategyUsesIPv4Only() {
        val dummyNodes = listOf(
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Test-DNS"
                server = "1.1.1.1"
                serverPort = "443"
            }
        )
        val config = SmartPoolConfigBuilder.buildMultiInboundConfig(null, dummyNodes, maxPorts = 1)
        assertEquals("UseIPv4", config.dns?.queryStrategy, "DNS must use UseIPv4 to avoid IPv6 timeouts on HyperOS")
    }
}
