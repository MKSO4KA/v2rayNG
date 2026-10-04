package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolMultiSubTest {

    @Test
    fun testMultiSubNodeAggregationExcludesSmartPoolGroup() {
        val sub1Nodes = listOf(
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "DE Node 1"
                server = "1.2.3.4"
                serverPort = "443"
                subscriptionId = "sub_id_1"
            },
            ProfileItem.create(EConfigType.VMESS).apply {
                remarks = "FR Node 1"
                server = "5.6.7.8"
                serverPort = "443"
                subscriptionId = "sub_id_1"
            }
        )

        val sub2Nodes = listOf(
            ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
                remarks = "US Node 1"
                server = "9.10.11.12"
                serverPort = "8388"
                subscriptionId = "sub_id_2"
            }
        )

        val poolProfile = ProfileItem.create(EConfigType.SMART_POOL).apply {
            remarks = "⚡ Smart Pool"
            server = "127.0.0.1"
            serverPort = "10808"
            subscriptionId = SmartPoolConstants.SMART_POOL_GROUP_ID
        }

        val allRawNodes = sub1Nodes + sub2Nodes + listOf(poolProfile)

        val validCandidates = allRawNodes.filter {
            it.configType != EConfigType.SMART_POOL && it.configType != EConfigType.CUSTOM
        }

        val deduplicated = SmartPoolNodeFilter.filterAndDeduplicate(validCandidates)

        assertEquals(3, deduplicated.size)
        assertFalse(deduplicated.any { it.configType == EConfigType.SMART_POOL })
        assertTrue(deduplicated.any { it.remarks == "DE Node 1" })
        assertTrue(deduplicated.any { it.remarks == "FR Node 1" })
        assertTrue(deduplicated.any { it.remarks == "US Node 1" })
    }

    @Test
    fun testDifferentialUpdateMaintainsAliveNodesFromMultipleSubscriptions() {
        val initialSub1 = listOf(
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Sub1 Node"
                server = "10.0.0.1"
                serverPort = "443"
            }
        )
        val initialSub2 = listOf(
            ProfileItem.create(EConfigType.VMESS).apply {
                remarks = "Sub2 Node"
                server = "10.0.0.2"
                serverPort = "443"
            }
        )

        val balancer = SmartPoolBalancer(initialSub1 + initialSub2, maxPortLimit = 16)
        val initialList = balancer.listAll()
        assertEquals(2, initialList.size)

        // When Sub 2 updates with a new node, Sub 1's alive node must be retained (stacked)
        val updatedSub2 = listOf(
            ProfileItem.create(EConfigType.VMESS).apply {
                remarks = "Sub2 Node Replacement"
                server = "10.0.0.3"
                serverPort = "443"
            }
        )

        val combinedUpdate = initialSub1 + updatedSub2
        val changed = balancer.differentialUpdate(combinedUpdate)
        assertTrue(changed)

        val updatedList = balancer.listAll()
        assertTrue(updatedList.any { it.profile.remarks == "Sub1 Node" })
        assertTrue(updatedList.any { it.profile.remarks == "Sub2 Node Replacement" })
    }

    @Test
    fun testMultipleSmartPoolProfilesCanCoexistIndependently() {
        val pool1 = ProfileItem.create(EConfigType.SMART_POOL).apply {
            remarks = "⚡ Smart Pool Gaming"
            subscriptionId = SmartPoolConstants.SMART_POOL_GROUP_ID
            smartPoolFilterRegex = ".*DE.*"
            smartPoolPortLimit = 64
            smartPoolInterval = "10s"
        }

        val pool2 = ProfileItem.create(EConfigType.SMART_POOL).apply {
            remarks = "⚡ Smart Pool Streaming"
            subscriptionId = SmartPoolConstants.SMART_POOL_GROUP_ID
            smartPoolFilterRegex = ".*US.*"
            smartPoolPortLimit = 128
            smartPoolInterval = "30s"
        }

        assertNotEquals(pool1.remarks, pool2.remarks)
        assertNotEquals(pool1.smartPoolFilterRegex, pool2.smartPoolFilterRegex)
        assertNotEquals(pool1.smartPoolPortLimit, pool2.smartPoolPortLimit)
        assertEquals(SmartPoolConstants.SMART_POOL_GROUP_ID, pool1.subscriptionId)
        assertEquals(SmartPoolConstants.SMART_POOL_GROUP_ID, pool2.subscriptionId)
    }
}
