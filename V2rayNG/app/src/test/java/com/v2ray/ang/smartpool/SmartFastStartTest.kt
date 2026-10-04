package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartFastStartTest {

    private fun createProfile(index: Int): ProfileItem {
        val p = ProfileItem.create(EConfigType.VLESS)
        p.server = "10.0.0.$index"
        p.serverPort = "443"
        p.remarks = "fast-node-$index"
        return p
    }

    @Test
    fun testSetEarlyLeaderPromotesFirstArrivedNodeInstantly() {
        val nodes = (1..5).map { createProfile(it) }
        val balancer = SmartPoolBalancer(nodes)

        val node3 = SmartNodeState(createProfile(3), localPort = 30003, latencyMs = 45L)
        balancer.setEarlyLeader(node3)

        val leader = balancer.getCurrentLeader()
        assertEquals(30003, leader?.localPort, "Первая ответившая нода должна сразу стать Лидером")
        assertEquals(0, leader?.penalty)
        assertEquals(0, leader?.failCount)
    }

    @Test
    fun testAddEarlyStandbyFillsPoolUpToCapacity() {
        val nodes = (1..12).map { createProfile(it) }
        val balancer = SmartPoolBalancer(nodes)

        val leaderNode = SmartNodeState(createProfile(1), localPort = 30001, latencyMs = 40L)
        balancer.setEarlyLeader(leaderNode)

        // Заполняем ответившие ноды 2..10
        for (i in 2..10) {
            val state = SmartNodeState(createProfile(i), localPort = 30000 + i, latencyMs = (40 + i).toLong())
            balancer.addEarlyStandby(state)
        }

        // Максимальная вместимость Standbys = 8
        assertEquals(8, balancer.getStandbyCount(), "Standbys не должен превышать лимит STANDBY_CAPACITY (8)")
        val hotNodes = balancer.getHotNodes()
        assertEquals(9, hotNodes.size, "Всего горячих узлов должно быть ровно 9 (1 Лидер + 8 Standbys)")
        assertTrue(hotNodes.any { it.localPort == 30001 })
    }
}
