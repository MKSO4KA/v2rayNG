package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartPoolToleranceTest {

    private fun createNode(remarks: String, port: Int): SmartNodeState {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            this.remarks = remarks
            this.server = "1.1.1.1"
            this.serverPort = "443"
        }
        return SmartNodeState(profile = profile, localPort = port)
    }

    @Test
    fun testTolerancePreventsJitterWhenWithinMargin() {
        val p1 = ProfileItem.create(EConfigType.VLESS).apply { remarks = "Current Leader"; server = "1.1.1.1"; serverPort = "443" }
        val p2 = ProfileItem.create(EConfigType.VLESS).apply { remarks = "Competitor"; server = "2.2.2.2"; serverPort = "443" }
        val balancer = SmartPoolBalancer(listOf(p1, p2))

        val all = balancer.listAll()
        val currentLeaderNode = all[0]
        val competitorNode = all[1]

        currentLeaderNode.latencyMs = 100
        competitorNode.latencyMs = 85

        balancer.updateStandbys(listOf(currentLeaderNode, competitorNode), toleranceMs = 30.0)
        assertEquals("Current Leader", balancer.getActiveLeader()?.profile?.remarks)

        competitorNode.latencyMs = 50
        balancer.updateStandbys(listOf(currentLeaderNode, competitorNode), toleranceMs = 30.0)
        assertEquals("Competitor", balancer.getActiveLeader()?.profile?.remarks)
    }
}
