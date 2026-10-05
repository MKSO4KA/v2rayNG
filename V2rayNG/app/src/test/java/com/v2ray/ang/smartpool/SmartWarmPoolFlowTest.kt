package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartWarmPoolFlowTest {

    private fun createTestNode(index: Int, name: String = "Node-$index"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            server = "10.0.0.$index"
            serverPort = "443"
            remarks = name
        }
    }

    @Test
    fun testDeterministicWarmPoolStagingAndDrainLifecycle() = runBlocking {
        val mockLatencies = mutableMapOf<Int, Long>()
        // Задаем Лидеру (30001) 50ms, Standby 1..8 (30002..30009) задержки 60ms..130ms
        mockLatencies[30001] = 50L
        for (i in 2..9) {
            mockLatencies[30000 + i] = (50L + (i * 10L))
        }
        // Холодные ноды 10..12 изначально не измерены (-1L)
        mockLatencies[30010] = -1L
        mockLatencies[30011] = -1L
        mockLatencies[30012] = -1L


        val testProbeFunc: (Int, Long) -> Long = { port, cutoff ->
            val rtt = mockLatencies[port] ?: -1L
            if (rtt in 1..cutoff) rtt else -1L
        }

        val profiles = (1..12).map { createTestNode(it) }
        val profile = ProfileItem.create(EConfigType.SMART_POOL).apply {
            remarks = "Deterministic Test Pool"
            smartPoolInterval = "20s"
            smartPoolTolerance = 30.0
        }

        val balancer = SmartPoolManager.startSession(
            candidates = profiles,
            profile = profile,
            probeFunc = testProbeFunc,
            enableDispatcher = false
        )

        val recruiter = SmartPoolManager.recruiter
        val prober = SmartPoolManager.prober
        assertNotNull(recruiter)
        assertNotNull(prober)

        try {
            // Шаг 1: Проверка начального состояния Лидера и 8 Standby
            prober!!.fastArrivalRace()

            val initialLeader = balancer.getCurrentLeader()
            assertNotNull(initialLeader)
            assertEquals(30001, initialLeader!!.localPort)
            assertEquals(50L, initialLeader.latencyMs)

            val initialStandbys = balancer.getHotNodes().filter { it.localPort != initialLeader.localPort }
            assertEquals(8, initialStandbys.size)
            val worstStandbyRTT = balancer.getWorstStandbyRTT()
            assertEquals(140L, worstStandbyRTT, "Худший Standby (Node-9) должен иметь RTT 140ms")

            // Шаг 2: Доборщик сканирует холодный пул (Node-10, Node-11, Node-12)
            // Node-10 (70ms) < (140 - 30 = 110) -> должна попасть в Тёплый пул
            // Node-11 (125ms) > 110 -> должна отсеяться
            mockLatencies[30010] = 70L
            mockLatencies[30011] = 125L
            recruiter!!.runColdRecruitPass()


            val stash = recruiter.warmStash.toList()
            assertTrue(stash.any { it.localPort == 30010 }, "Node-10 (70ms) обязана попасть в Тёплый пул")
            assertFalse(stash.any { it.localPort == 30011 }, "Node-11 (125ms) не должна попасть из-за толерантности")

            // Шаг 3: Пробер проводит калибровку кольца probeActiveRing
            // Тёплый пул должен полностью слиться в Standbys, выбив самый медленный Standby (140ms)
            prober.probeActiveRing()

            assertTrue(recruiter.warmStash.isEmpty(), "После калибровки Тёплый пул обязан быть полностью очищен (0/23)")
            val updatedStandbys = balancer.getHotNodes().filter { it.localPort != initialLeader.localPort }
            assertEquals(8, updatedStandbys.size)
            assertTrue(updatedStandbys.any { it.localPort == 30010 }, "Node-10 (70ms) должна занять слот Standby")
            assertFalse(updatedStandbys.any { it.localPort == 30009 }, "Старый худший Standby (Node-9 140ms) вытеснен в холодный пул")
            assertEquals(130L, balancer.getWorstStandbyRTT(), "Новый худший Standby стал 130ms")

            // Шаг 4: Появление супер-ноды 10ms (должна сместить Лидера 50ms)
            mockLatencies[30012] = 10L
            recruiter.runColdRecruitPass()
            assertTrue(recruiter.warmStash.any { it.localPort == 30012 }, "Node-12 (10ms) должна попасть в Тёплый пул")
            prober.probeActiveRing()


            val newLeader = balancer.getCurrentLeader()
            assertNotNull(newLeader)
            assertEquals(30012, newLeader!!.localPort, "Node-12 (10ms) должна стать новым Активным Лидером")
            assertEquals(10L, newLeader.latencyMs)
            assertTrue(recruiter.warmStash.isEmpty(), "Тёплый пул снова гарантированно пуст")

        } finally {
            SmartPoolManager.stop()
        }
    }
}
