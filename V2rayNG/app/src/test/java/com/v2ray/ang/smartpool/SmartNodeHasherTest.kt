package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class SmartNodeHasherTest {

    @Test
    fun testNodeHashIsDeterministicAndIgnoresRemarks() {
        val n1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.com"
            serverPort = "443"
            password = "uuid-123"
            remarks = "🇸🇪 Sweden (AI)"
        }

        val n2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.com"
            serverPort = "443"
            password = "uuid-123"
            remarks = "🇸🇪 Sweden Fast Renovated"
        }

        val hash1 = SmartNodeHasher.computeNodeHash(n1)
        val hash2 = SmartNodeHasher.computeNodeHash(n2)

        assertEquals(hash1, hash2, "Remarks change must not alter physical node hash")
        assertEquals(64, hash1.length)
    }

    @Test
    fun testNodeHashChangesWhenAddressOrAuthChanges() {
        val n1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node1.example.com"
            serverPort = "443"
            password = "uuid-123"
        }
        val n2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "node2.example.com"
            serverPort = "443"
            password = "uuid-123"
        }

        assertNotEquals(SmartNodeHasher.computeNodeHash(n1), SmartNodeHasher.computeNodeHash(n2))
    }

    @Test
    fun testDifferentialUpdatePreservesLeaderAndLatency() {
        val p1 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "1.1.1.1"
            serverPort = "443"
            remarks = "Node 1"
        }
        val p2 = ProfileItem.create(EConfigType.VLESS).apply {
            server = "2.2.2.2"
            serverPort = "443"
            remarks = "Node 2"
        }

        val balancer = SmartPoolBalancer(listOf(p1, p2))
        val leaderBefore = balancer.getActiveLeader()
        leaderBefore?.latencyMs = 42

        // Second update with identical nodes (only remarks modified by provider)
        val p1Renamed = ProfileItem.create(EConfigType.VLESS).apply {
            server = "1.1.1.1"
            serverPort = "443"
            remarks = "Node 1 Renamed"
        }
        val p2Renamed = ProfileItem.create(EConfigType.VLESS).apply {
            server = "2.2.2.2"
            serverPort = "443"
            remarks = "Node 2 Renamed"
        }

        val changed = balancer.differentialUpdate(listOf(p1Renamed, p2Renamed))
        assertFalse(changed, "Must report no structural change when hashes match")

        val leaderAfter = balancer.getActiveLeader()
        assertEquals(leaderBefore?.hash, leaderAfter?.hash, "Leader must be retained")
        assertEquals(42, leaderAfter?.latencyMs, "Latency must be preserved")
    }
}
