package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartUniversalNodeParsingTest {

    @Test
    fun testHappAndSingboxOutboundUnpacking() {
        val jsonPayload = """
        {
          "outbounds": [
            {
              "type": "vless",
              "tag": "🇩🇪 Германия",
              "server": "de.example.com",
              "server_port": 443,
              "uuid": "11111111-2222-3333-4444-555555555555",
              "flow": "xtls-rprx-vision",
              "tls": {
                "enabled": true,
                "server_name": "de.example.com",
                "reality": {
                  "enabled": true,
                  "public_key": "abcdef123456",
                  "short_id": "1234"
                }
              }
            },
            {
              "type": "hysteria2",
              "tag": "🇳🇱 Нидерланды",
              "server": "nl.example.com",
              "server_port": 8443,
              "password": "secret123",
              "tls": {
                "enabled": true,
                "server_name": "nl.example.com"
              }
            },
            {
              "type": "selector",
              "tag": "⚡ Авто-выбор",
              "outbounds": ["🇩🇪 Германия", "🇳🇱 Нидерланды"]
            },
            {
              "protocol": "freedom",
              "tag": "direct"
            }
          ]
        }
        """.trimIndent()

        val nodes = SmartSubFetcher.parseNodesFromPayload(jsonPayload)

        assertEquals(2, nodes.size, "Только реальные прокси-ноды должны быть извлечены (без selector и direct)")
        assertEquals(EConfigType.VLESS, nodes[0].configType)
        assertEquals("de.example.com", nodes[0].server)
        assertEquals("443", nodes[0].serverPort)
        assertEquals("reality", nodes[0].security)
        assertEquals("abcdef123456", nodes[0].publicKey)

        assertEquals(EConfigType.HYSTERIA2, nodes[1].configType)
        assertEquals("nl.example.com", nodes[1].server)
        assertEquals("8443", nodes[1].serverPort)
    }

    @Test
    fun testDeduplicationReplacesAutoGroupNodeRemarksWithSpecificName() {
        val autoNode = ProfileItem.create(EConfigType.VLESS).apply {
            server = "1.2.3.4"
            serverPort = "443"
            password = "uuid-abc"
            publicKey = "key-123"
            remarks = "⚡ Wi-Fi | Авто-балансировщик"
        }

        val namedNode = ProfileItem.create(EConfigType.VLESS).apply {
            server = "1.2.3.4"
            serverPort = "443"
            password = "uuid-abc"
            publicKey = "key-123"
            remarks = "🇸🇪 Швеция #1 Стокгольм"
        }

        val deduplicated = SmartPoolNodeFilter.filterAndDeduplicate(listOf(autoNode, namedNode))

        assertEquals(1, deduplicated.size, "Одинаковый физический эндпоинт должен быть дедуплицирован")
        assertEquals("🇸🇪 Швеция #1 Стокгольм", deduplicated[0].remarks, "Имя из авто-группы должно замениться на конкретную страну")
    }

    @Test
    fun testNodeHasherDetectsPhysicalIdentityRegardlessOfRemarks() {
        val n1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "srv.host.com"
            serverPort = "443"
            password = "pass"
            remarks = "First Name"
        }

        val n2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "srv.host.com"
            serverPort = "443"
            password = "pass"
            remarks = "Completely Different Name"
        }

        assertEquals(SmartNodeHasher.computeNodeHash(n1), SmartNodeHasher.computeNodeHash(n2))
    }
}
