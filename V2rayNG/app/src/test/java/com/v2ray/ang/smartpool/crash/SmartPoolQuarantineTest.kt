package com.v2ray.ang.smartpool.crash

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SmartPoolQuarantineTest {

    @BeforeEach
    fun setup() {
        SmartPoolQuarantineManager.clear()
        SmartPoolLogRingBuffer.clear()
    }

    @Test
    fun testQuarantineRegistrationAndFilter() {
        val node = ProfileItem(configType = EConfigType.VMESS).apply {
            server = "bad-node.test"
            serverPort = "443"
            password = "uuid-1234"
            remarks = "Bad Node"
        }

        assertFalse(SmartPoolQuarantineManager.isQuarantined(node))
        SmartPoolQuarantineManager.quarantineNode("out-30015", node, "allowInsecure deprecated")
        assertTrue(SmartPoolQuarantineManager.isQuarantined(node))
        assertTrue(SmartPoolQuarantineManager.isTagQuarantined("out-30015"))
    }

    @Test
    fun testRingBufferBoundedLimit() {
        for (i in 0 until 500) {
            SmartPoolLogRingBuffer.log("Log entry #$i with some sample content")
        }
        val logs = SmartPoolLogRingBuffer.getAllBufferedLogs()
        assertTrue(logs.isNotEmpty())
        val totalChars = logs.sumOf { it.length }
        assertTrue(totalChars <= SmartPoolLogRingBuffer.MAX_LOG_CHARS)
    }
}
