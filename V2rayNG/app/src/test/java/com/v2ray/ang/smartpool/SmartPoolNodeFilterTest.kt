package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolNodeFilterTest {

    @Test
    fun testFilterDummyNodes() {
        val dummy1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "daysleft"
            serverPort = "443"
            remarks = "Осталось 5 дней"
        }
        assertFalse(SmartPoolNodeFilter.isValidProxyNode(dummy1))

        val dummy2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "traffic"
            serverPort = "443"
            remarks = "Трафик: 10 ГБ"
        }
        assertFalse(SmartPoolNodeFilter.isValidProxyNode(dummy2))

        val valid = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node1.example.com"
            serverPort = "443"
            remarks = "Германия Fast"
        }
        assertTrue(SmartPoolNodeFilter.isValidProxyNode(valid))
    }

    @Test
    fun testDeduplicationByEndpoint() {
        val n1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node1.example.com"
            serverPort = "443"
            password = "uuid-1"
            remarks = "Auto / Балансировщик"
        }
        val n2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node1.example.com"
            serverPort = "443"
            password = "uuid-1"
            remarks = "Германия 1"
        }
        val list = listOf(n1, n2)
        val unique = SmartPoolNodeFilter.filterAndDeduplicate(list)
        assertEquals(1, unique.size)
        assertEquals("Германия 1", unique[0].remarks)
    }
}
