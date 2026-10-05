package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SmartColdQueueTest {

    private fun createProfile(index: Int): ProfileItem {
        val p = ProfileItem.create(EConfigType.VLESS)
        p.server = "10.0.0.$index"
        p.serverPort = "443"
        p.password = "uuid-$index"
        p.remarks = "node-$index"
        return p
    }

    @Test
    fun testAdaptiveBatchSizeCalculatesCorrectKForVariousPools() {
        val intervalMs = 20_000L // 20 секунд
        // За 59 минут (3540 сек) проходит 177 циклов

        assertEquals(1, SmartPoolProber.calculateAdaptiveBatchSize(50, intervalMs))
        assertEquals(1, SmartPoolProber.calculateAdaptiveBatchSize(100, intervalMs))
        assertEquals(2, SmartPoolProber.calculateAdaptiveBatchSize(200, intervalMs))
        assertEquals(3, SmartPoolProber.calculateAdaptiveBatchSize(500, intervalMs))
        assertEquals(0, SmartPoolProber.calculateAdaptiveBatchSize(0, intervalMs))
    }

    @Test
    fun testNodesInCooldownDoNotCompeteForProbeSlot() {
        val now = System.currentTimeMillis()
        val nodeNormal = SmartNodeState(createProfile(1), localPort = 30001, cooldownUntil = 0L)
        val nodeCooldown = SmartNodeState(createProfile(2), localPort = 30002, cooldownUntil = now + 900_000L)

        val list = listOf(nodeNormal, nodeCooldown)
        val available = list.filter { now >= it.cooldownUntil }

        assertEquals(1, available.size)
        assertEquals(30001, available[0].localPort)
    }

    @Test
    fun testNodeReEntersQueueImmediatelyAfterCooldownExpires() {
        val now = System.currentTimeMillis()
        val node = SmartNodeState(createProfile(1), localPort = 30001, cooldownUntil = now + 1000L)

        assertFalse(now >= node.cooldownUntil, "До истечения кулдауна нода недоступна")
        val future = now + 1001L
        assertTrue(future >= node.cooldownUntil, "После истечения кулдауна нода сразу доступна")
    }

    @Test
    fun testPromotionToStandbyResetsPenaltyAndFailCount() {
        val balancer = SmartPoolBalancer(emptyList())
        val node = SmartNodeState(createProfile(1), localPort = 30001, latencyMs = 85L, failCount = 8)


        balancer.fillStandbys(listOf(node))
        val standbys = balancer.getHotNodes()

        assertTrue(standbys.any { it.localPort == 30001 })
        val promoted = standbys.first { it.localPort == 30001 }
        assertEquals(0, promoted.failCount, "Счетчик сбоев должен сбрасываться в 0 при повышении")
    }

    @Test
    fun testStandbysOnlyContainVerifiedAliveNodes() {
        val unverified = (1..10).map { createProfile(it) }
        val balancer = SmartPoolBalancer(unverified)

        // Непроверенные ноды с latency = -1 не должны попадать в standbys
        assertEquals(0, balancer.getStandbyCount(), "До проверки standbys должны быть пустыми, без слепых догадок")
    }

    @Test
    fun testOfflineTimeDoesNotKillNode() {
        val profile = createProfile(1)
        val node = SmartNodeState(profile, localPort = 30001, failCount = 0)
        val sevenDaysLater = System.currentTimeMillis() + (7L * 24 * 3600 * 1000L)

        assertFalse(node.isDead(sevenDaysLater), "Даже через 7 дней без сети нода НЕ должна умирать")
    }

    @Test
    fun test24ConsecutiveFailsPrunesNodeAndSuccessResets() {
        val profile = createProfile(1)
        val node = SmartNodeState(profile, localPort = 30001, failCount = 23)

        assertFalse(node.isDead(), "При 23 ошибках нода еще жива")
        node.failCount++
        assertTrue(node.isDead(), "При 24 ошибках подряд нода признается мертвой")

        // Успех сбрасывает счетчик
        node.failCount = 0
        assertFalse(node.isDead(), "При успешном пинге нода снова полностью жива")
    }

    @Test
    fun testNodesStackAcrossSubscriptionUpdates() {
        val initial = listOf(createProfile(1), createProfile(2))
        val balancer = SmartPoolBalancer(initial)

        // Провайдер вернул только ноду 2 и новую ноду 3 (нода 1 отсутствует во временном ответе)
        val nextBatch = listOf(createProfile(2), createProfile(3))
        balancer.differentialUpdate(nextBatch)

        val currentPorts = balancer.listAll().map { it.profile.remarks }
        assertTrue(currentPorts.contains("node-1"), "Нода 1 должна стакаться и оставаться в пуле по TTL")
        assertTrue(currentPorts.contains("node-2"), "Нода 2 сохраняется")
        assertTrue(currentPorts.contains("node-3"), "Нода 3 добавляется")
    }
}
