package com.v2ray.ang.smartpool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolSubAutoUpdaterTest {

    @Test
    fun testParseIntervalToMillis() {
        assertEquals(20000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("20s"))
        assertEquals(20000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("20сек"))
        assertEquals(60000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("1m"))
        assertEquals(120000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("2мин"))
        assertEquals(3600000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("1h"))
        assertEquals(30000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("30"))
        assertEquals(15000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("15000ms"))
    }

    @Test
    fun testIntervalSafeFloor() {
        assertEquals(5000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("1s"))
        assertEquals(5000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("200ms"))
    }

    @Test
    fun testIntervalFallbackOnInvalid() {
        assertEquals(20000L, SmartPoolSubAutoUpdater.parseIntervalToMillis("invalid"))
        assertEquals(20000L, SmartPoolSubAutoUpdater.parseIntervalToMillis(""))
        assertEquals(20000L, SmartPoolSubAutoUpdater.parseIntervalToMillis(null))
    }

    @Test
    fun testComputeHashConsistency() {
        val text1 = "vless://uuid1@example.com:443#node1"
        val text2 = "vless://uuid1@example.com:443#node1"
        val text3 = "vless://uuid2@example.com:443#node2"

        val hash1 = SmartPoolSubAutoUpdater.computeHash(text1)
        val hash2 = SmartPoolSubAutoUpdater.computeHash(text2)
        val hash3 = SmartPoolSubAutoUpdater.computeHash(text3)

        assertEquals(hash1, hash2)
        assertNotEquals(hash1, hash3)
        assertEquals(64, hash1.length)
    }
}
