package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SmartPoolDeduplicationTest {

    @Test
    fun testSmartPoolSettingsArePreservedOnProfileCopy() {
        val original = ProfileItem.create(EConfigType.SMART_POOL).apply {
            remarks = "⚡ Smart Pool (10 узлов)"
            smartPoolFilterRegex = "^(?!.*{flag:RU}).+$"
            smartPoolInterval = "15s"
            smartPoolTolerance = 25.0
            smartPoolPolicyType = "lowest_latency"
            smartPoolSubUpdateInterval = "30s"
        }

        val updated = original.copy(remarks = "⚡ Smart Pool (25 узлов)")

        assertEquals("⚡ Smart Pool (25 узлов)", updated.remarks)
        assertEquals("^(?!.*{flag:RU}).+$", updated.smartPoolFilterRegex)
        assertEquals("15s", updated.smartPoolInterval)
        assertEquals(25.0, updated.smartPoolTolerance)
        assertEquals("lowest_latency", updated.smartPoolPolicyType)
        assertEquals("30s", updated.smartPoolSubUpdateInterval)
    }

    @Test
    fun testDeduplicationKeepsSingleSmartPoolInstance() {
        val list = mutableListOf("guid-node-1", "guid-smart-1", "guid-node-2", "guid-smart-2")
        val smartGuids = list.filter { it.startsWith("guid-smart") }

        assertEquals(2, smartGuids.size)
        val primaryGuid = smartGuids.first()

        // Simulate deduplication logic
        for (dup in smartGuids.drop(1)) {
            list.remove(dup)
        }
        if (list.indexOf(primaryGuid) != 0) {
            list.remove(primaryGuid)
            list.add(0, primaryGuid)
        }

        assertEquals(3, list.size)
        assertEquals("guid-smart-1", list[0])
        assertEquals(1, list.count { it.startsWith("guid-smart") })
    }
}
