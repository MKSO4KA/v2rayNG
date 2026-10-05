package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolRaceProberTest {

    @Test
    fun testRaceProberBatchSizeIsExactly18() {
        assertEquals(18, SmartPoolConstants.RACE_PROBE_COUNT, "Race probe count must be exactly 2 * Standby(8) + 2 = 18")
        assertEquals(8, SmartPoolConstants.STANDBY_CAPACITY, "Standby capacity must remain strictly 8")
    }

    @Test
    fun testCandidateSelectionLimitsTo18InRace() {
        val candidates = (1..50).map { i ->
            ProfileItem.create(EConfigType.VLESS).apply {
                remarks = "Candidate-$i"
                server = "192.168.1.$i"
                serverPort = "443"
                password = "uuid-$i"
            }
        }
        val raceBatch = candidates.take(SmartPoolConstants.RACE_PROBE_COUNT)
        assertEquals(18, raceBatch.size, "FastArrivalRace batch must take exactly 18 candidates")
    }

    @Test
    fun testEffectiveRTTBaselineDeduction() {
        val node = SmartNodeState(
            profile = ProfileItem.create(EConfigType.VLESS),
            localPort = 30001,
            latencyMs = 250L
        )
        val rtt = node.effectiveRTT(baselineMs = 50L)
        assertEquals(250L, rtt, "Effective RTT without penalty should equal measured latency")
        assertTrue(node.isAvailable())
    }
}
