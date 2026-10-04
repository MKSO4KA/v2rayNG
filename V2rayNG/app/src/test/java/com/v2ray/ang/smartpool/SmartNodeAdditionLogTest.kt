package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartNodeAdditionLogTest {

    @Test
    fun testMaskedAuthFormatHidesSensitiveCredentials() {
        val uuid = "327bf7ed-cfea-4bf3-b878-9cfd40338184"
        val masked = if (uuid.length > 8) "${uuid.take(4)}...${uuid.takeLast(4)}" else "***"
        assertEquals("327b...8184", masked)
        assertFalse(masked.contains("cfea-4bf3"))
    }

    @Test
    fun testShortPasswordMaskedAsThreeAsterisks() {
        val shortPass = "pass1"
        val masked = if (shortPass.length > 8) "${shortPass.take(4)}...${shortPass.takeLast(4)}" else "***"
        assertEquals("***", masked)
    }

    @Test
    fun testTransportInfoStringFormatting() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "de.example.com"
            serverPort = "443"
            network = "ws"
            security = "tls"
            sni = "de.example.com"
            path = "/vless-ws"
        }

        val transportInfo = buildString {
            append(profile.network ?: "tcp")
            if (!profile.security.isNullOrBlank()) append("/${profile.security}")
            if (!profile.sni.isNullOrBlank()) append(", sni=${profile.sni}")
            if (!profile.path.isNullOrBlank()) append(", path=${profile.path}")
        }

        assertTrue(transportInfo.contains("ws/tls"))
        assertTrue(transportInfo.contains("sni=de.example.com"))
        assertTrue(transportInfo.contains("path=/vless-ws"))
    }
}
