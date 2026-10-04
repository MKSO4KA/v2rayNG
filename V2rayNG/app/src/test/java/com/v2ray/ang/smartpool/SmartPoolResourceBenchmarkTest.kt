package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

class SmartPoolResourceBenchmarkTest {

    private fun generateSyntheticNodes(count: Int): List<ProfileItem> {
        return (1..count).map { i ->
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Node-$i-Server"
                server = "192.168.1.${(i % 250) + 1}"
                serverPort = (10000 + (i % 50000)).toString()
                password = "user-$i-uuid"
            }
        }
    }

    @Test
    fun benchmarkScalingAcrossLimits() {
        val limits = listOf(128, 256, 512, 1024)
        val runtime = Runtime.getRuntime()

        for (count in limits) {
            System.gc()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()
            val nodes = generateSyntheticNodes(count)
            val initTimeNs = measureNanoTime {
                val balancer = SmartPoolBalancer(nodes, maxPortLimit = count)
                assertTrue(balancer.listAll().isNotEmpty())
                assertTrue(balancer.listAll().size <= count)
            }
            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memDeltaBytes = (memAfter - memBefore).coerceAtLeast(0L)
            val avgBytesPerNode = if (count > 0) memDeltaBytes / count else 0L
            val initMs = initTimeNs / 1_000_000.0

            val configGenTimeNs = measureNanoTime {
                val cfg = SmartPoolConfigBuilder.buildMultiInboundConfig(null, nodes, maxPorts = count)
                assertTrue(cfg.inbounds.size <= count)
            }
            val configGenMs = configGenTimeNs / 1_000_000.0

            println("=== Benchmark Scale $count Nodes ===")
            println("RAM Alloc: ~${memDeltaBytes / 1024} KB (Avg ~$avgBytesPerNode bytes/node)")
            println("Balancer Init Time: $initMs ms")
            println("MultiInbound Config Gen Time: $configGenMs ms")

            assertTrue(configGenMs < 1000.0, "Config generation must be fast for scale $count")
            assertTrue(initMs < 500.0, "Balancer init must be fast for scale $count")
        }
    }

    @Test
    fun testPortRecyclingUnderLimit() {
        val limit = 128
        val initial = generateSyntheticNodes(limit)
        val balancer = SmartPoolBalancer(initial, maxPortLimit = limit)
        val portsBefore = balancer.listAll().map { it.localPort }.toSet()
        assertTrue(portsBefore.size <= limit)

        val halfReplaced = initial.take(64) + generateSyntheticNodes(100).drop(64).take(64)
        balancer.differentialUpdate(halfReplaced)
        val portsAfter = balancer.listAll().map { it.localPort }.toSet()

        assertTrue(balancer.listAll().size <= limit)
        assertTrue(portsAfter.all { it in SmartPoolConstants.BASE_POOL_PORT until (SmartPoolConstants.BASE_POOL_PORT + limit) })
    }
}
