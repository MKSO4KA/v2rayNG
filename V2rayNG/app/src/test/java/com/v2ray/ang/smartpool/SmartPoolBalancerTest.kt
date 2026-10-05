package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SmartPoolBalancerTest {

    private fun createTestNode(server: String, remarks: String, port: String = "443"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            this.server = server
            this.serverPort = port
            this.remarks = remarks
        }
    }

    @Test
    fun testLeaderSelectionAndPenalty() {
        val n1 = createTestNode("1.1.1.1", "Node 1")
        val n2 = createTestNode("2.2.2.2", "Node 2")
        val balancer = SmartPoolBalancer(listOf(n1, n2))
        val leader = balancer.getActiveLeader()
        assertNotNull(leader)
        assertEquals(30001, leader!!.localPort)

        balancer.penalize(leader)
        assertEquals(1, leader.failCount)
    }


    @Test
    fun testLeaderListenerInvokedImmediately() {
        val n1 = createTestNode("1.1.1.1", "Initial Fast Node")
        val balancer = SmartPoolBalancer(listOf(n1))
        var notifiedRemark = ""
        balancer.onLeaderChanged = { node ->
            notifiedRemark = node.profile.remarks
        }
        assertEquals("Initial Fast Node", notifiedRemark)
    }

    @Test
    fun testFailoverAndRotation() {
        val n1 = createTestNode("1.1.1.1", "Node 1")
        val n2 = createTestNode("2.2.2.2", "Node 2")
        val balancer = SmartPoolBalancer(listOf(n1, n2))
        var currentLeaderName = ""
        balancer.onLeaderChanged = { currentLeaderName = it.profile.remarks }

        assertEquals("Node 1", currentLeaderName)
        val leader = balancer.getActiveLeader()!!
        balancer.penalize(leader)

        assertEquals("Node 2", currentLeaderName)
        assertEquals("Node 2", balancer.getActiveLeader()?.profile?.remarks)
    }

    @Test
    fun testCooldownAfterRepeatedFailures() {
        val n1 = createTestNode("1.1.1.1", "Failing Node")
        val n2 = createTestNode("2.2.2.2", "Stable Node")
        val balancer = SmartPoolBalancer(listOf(n1, n2))

        val failingNode = balancer.listAll().first { it.profile.remarks == "Failing Node" }
        balancer.penalize(failingNode)
        balancer.penalize(failingNode)
        balancer.penalize(failingNode)

        assertEquals(3, failingNode.failCount)
        assertFalse(failingNode.isAvailable(), "Node with 3 failures must be in cooldown")
        assertEquals("Stable Node", balancer.getActiveLeader()?.profile?.remarks)
    }

    @Test
    fun testRttSortingUpdatesLeader() {
        val n1 = createTestNode("1.1.1.1", "Slow Node")
        val n2 = createTestNode("2.2.2.2", "Fast Node")
        val balancer = SmartPoolBalancer(listOf(n1, n2))
        var leaderRemarks = ""
        balancer.onLeaderChanged = { leaderRemarks = it.profile.remarks }

        val all = balancer.listAll()
        all[0].latencyMs = 300
        all[1].latencyMs = 45

        balancer.updateStandbys(all)
        assertEquals("Fast Node", leaderRemarks)
        assertEquals("Fast Node", balancer.getActiveLeader()?.profile?.remarks)
    }
}
