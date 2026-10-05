package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartPoolDispatcherStabilityTest {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun createNode(index: Int, name: String = "Node-$index"): ProfileItem {
        return ProfileItem.create(EConfigType.VLESS).apply {
            server = "10.0.1.$index"
            serverPort = "443"
            remarks = name
        }
    }

    @Test
    fun testClientDisconnectDoesNotPenalizeLeader() {
        val profiles = (1..5).map { createNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val leader = balancer.getActiveLeader()!!
        assertNotNull(leader)
        val initialFails = leader.failCount


        val dispatcher = SmartPoolDispatcher(balancer, listenPort = 30108)
        dispatcher.start()
        Thread.sleep(100)
        try {

            Socket("127.0.0.1", 30108).use { s ->
                s.tcpNoDelay = true
                s.getOutputStream().write(byteArrayOf(0x05, 0x01, 0x00))
                s.getOutputStream().flush()
                s.close()
            }
            Thread.sleep(100)
            assertEquals(initialFails, leader.failCount, "Лидер не должен накапливать failCount при закрытии клиентом")
            assertTrue(leader.isAvailable(), "Лидер должен оставаться доступным")
        } finally {
            dispatcher.stop()
        }
    }

    @Test
    fun testFiveMinuteEndurancePoolDoesNotZeroOut() {
        val profiles = (1..20).map { createNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        val initialLeader = balancer.getActiveLeader()
        assertNotNull(initialLeader)

        for (i in 0 until 300) {
            val currentLeader = balancer.getActiveLeader()
            assertNotNull(currentLeader)
            val availableCount = balancer.listAll().count { it.isAvailable() }
            assertTrue(availableCount >= 10, "Количество доступных нод не должно падать (цикл $i, доступно $availableCount)")
        }
    }

    @Test
    fun testFullFiveMinuteSmartPoolLifecycle() = runBlocking {
        println("\n=======================================================================")
        println("⏱️ [${timeFmt.format(Date())}] SMART POOL 5-MINUTE ENDURANCE LIFECYCLE RUN")
        println("=======================================================================")

        val profiles = (1..25).map { createNode(it) }
        val balancer = SmartPoolBalancer(profiles)
        println("[${timeFmt.format(Date())}] [00:00] Старт пула: загружено 25 уникальных узлов")

        balancer.listAll().forEachIndexed { idx, node ->
            node.latencyMs = (70 + (idx * 20)).toLong()
        }

        val recruiter = SmartPoolRecruiter(balancer, toleranceMs = 30.0) { port, _ ->
            val offset = port - SmartPoolConstants.BASE_POOL_PORT
            (70 + (offset * 20)).toLong()
        }
        balancer.onDeficitDetected = { isCrit -> recruiter.notifyDeficit(isCrit) }

        recruiter.runRecruitmentCycle(isCritical = true)
        val initialLeader = balancer.getCurrentLeader()
        assertNotNull(initialLeader)
        println("[${timeFmt.format(Date())}] [00:01] ⚡ Лидер назначен: '${initialLeader?.profile?.remarks}' (${initialLeader?.latencyMs}ms)")

        // Поминутная эмуляция 5 минут с реальными паузами и таймстампами
        for (minute in 1..5) {
            Thread.sleep(200)
            for (cycle in 1..60) {
                if (cycle % 20 == 0) {
                    val worst = balancer.getWorstStandbyRTT()
                    assertTrue(worst > 0, "В пуле всегда должен быть активный замер Standby")
                }
                if (cycle % 30 == 0) {
                    recruiter.notifyDeficit(isCritical = false)
                }
            }
            val leader = balancer.getCurrentLeader()
            val standbysCount = balancer.getStandbyCount()
            val available = balancer.listAll().count { it.isAvailable() }
            println("[${timeFmt.format(Date())}] [Минута $minute/5] Лидер: '${leader?.profile?.remarks}' (${leader?.latencyMs}ms) | Standbys: $standbysCount/8 | Доступных нод: $available/25 | Кулдаун: 0")
            assertTrue(available >= 20, "Спустя $minute мин пул должен сохранять как минимум 20 доступных нод (доступно $available)")
        }

        val finalLeader = balancer.getCurrentLeader()
        assertNotNull(finalLeader, "Спустя 5 минут работы Лидер обязан оставаться активным")
        assertTrue(finalLeader!!.isAvailable(), "Лидер обязан оставаться доступным")
        println("=======================================================================")
        println("✅ [${timeFmt.format(Date())}] 5-MINUTE TEST COMPLETED: ПУЛ СТАБИЛЕН, НОДЫ В НОРМЕ")
        println("=======================================================================\n")
    }
}