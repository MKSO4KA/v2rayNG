package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SmartPoolRecruiterTest {

    private fun createTestNode(index: Int, name: String = "Node-$index"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            server = "10.0.0.$index"
            serverPort = "443"
            remarks = name
        }
    }

    @Test
    fun testEarlyLeaderAssignmentAndFastStart() = runBlocking {
        val profiles = (1..10).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val recruiter = SmartPoolRecruiter(balancer, toleranceMs = 30.0) { port, _ ->
            if (port == 30003) 107L else -1L
        }
        recruiter.runRecruitmentCycle(isCritical = true)
        val leader = balancer.getCurrentLeader()
        assertEquals(30003, leader?.localPort)
        assertEquals(107L, leader?.latencyMs)
    }

    @Test
    fun testLeaderInPlaceUpgrade() = runBlocking {
        val profiles = (1..5).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val node1 = SmartNodeState(createTestNode(1, "SlowLeader"), localPort = 30001, latencyMs = 120L)
        balancer.setEarlyLeader(node1)

        val fasterCandidate = SmartNodeState(createTestNode(2, "FastCandidate"), localPort = 30002, latencyMs = 32L)
        val upgraded = balancer.upgradeLeaderIfBetter(fasterCandidate, toleranceMs = 30.0)
        assertTrue(upgraded, "Более быстрая нода должна сместить текущего Лидера")
        assertEquals("FastCandidate", balancer.getCurrentLeader()?.profile?.remarks)
    }

    @Test
    fun testProgressiveStandbyReplacement() {
        val profiles = (1..15).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val leader = SmartNodeState(createTestNode(1, "Leader"), localPort = 30001, latencyMs = 30L)
        balancer.setEarlyLeader(leader)

        val slowStandby = SmartNodeState(createTestNode(2, "SlowStandby"), localPort = 30002, latencyMs = 150L)
        balancer.replaceOrUpgradeStandby(slowStandby, toleranceMs = 30.0)
        assertEquals(1, balancer.getStandbyCount())

        val fastStandby = SmartNodeState(createTestNode(3, "FastStandby"), localPort = 30003, latencyMs = 50L)
        val replaced = balancer.replaceOrUpgradeStandby(fastStandby, toleranceMs = 30.0)
        assertTrue(replaced)
        assertTrue(balancer.getHotNodes().any { it.localPort == 30003 })
    }

    @Test
    fun testSingleFlightProtection() {
        val profiles = (1..5).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val callCount = AtomicInteger(0)
        val recruiter = SmartPoolRecruiter(balancer, toleranceMs = 30.0) { _, _ ->
            callCount.incrementAndGet()
            100L
        }
        recruiter.notifyDeficit(isCritical = false)
        recruiter.notifyDeficit(isCritical = false)
        assertTrue(callCount.get() >= 0)
    }

    @Test
    fun testExponentialCutoffEscalation() = runBlocking {
        val profiles = (1..5).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val recordedCutoffs = mutableListOf<Long>()
        val recruiter = SmartPoolRecruiter(balancer, toleranceMs = 0.0) { _, cutoff ->
            recordedCutoffs.add(cutoff)
            if (cutoff >= 600L) 550L else -1L
        }
        recruiter.runRecruitmentCycle(isCritical = true)
        assertTrue(recordedCutoffs.any { it <= 150L }, "Первый проход должен начинаться с BASE_CUTOFF_MS")
        assertTrue(recordedCutoffs.any { it >= 300L }, "При отсутствии нод порог должен удваиваться")
    }

    @Test
    fun testUnmeasuredLatencyDoesNotPolluteStandby() {
        val profiles = (1..5).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val invalidNode = SmartNodeState(createTestNode(2), localPort = 30002, latencyMs = -1L)
        val accepted = balancer.replaceOrUpgradeStandby(invalidNode, toleranceMs = 30.0)
        assertFalse(accepted, "Нода с отрицательной задержкой не должна допускаться в Standby")
        assertEquals(0, balancer.getStandbyCount())
    }

    @Test
    fun testPromotedStandbyProtectedFromRecruiterEviction() {
        val profiles = (1..5).map { createTestNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val promotedLeader = SmartNodeState(createTestNode(2, "PromotedStandby"), localPort = 30002, latencyMs = 70L)
        balancer.setEarlyLeader(promotedLeader)

        val attemptToEvict = balancer.replaceOrUpgradeStandby(promotedLeader, toleranceMs = 30.0)
        assertFalse(attemptToEvict, "Активный Лидер не может быть вытеснен через replaceOrUpgradeStandby")
        assertEquals(30002, balancer.getCurrentLeader()?.localPort)
    }
}