package com.v2ray.ang.smartpool

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartPoolProberHttpTest {

    @Test
    fun testAdaptiveBatchSizeCalculation() {
        val batchSmall = SmartPoolProber.calculateAdaptiveBatchSize(4, 20000L)
        assertTrue(batchSmall in 1..8, "Batch size must be bounded")

        val batchZero = SmartPoolProber.calculateAdaptiveBatchSize(0, 20000L)
        assertTrue(batchZero == 0, "Zero candidates must yield zero batch size")
    }

    @Test
    fun testProbeTargetUrlsAreWellFormed() {
        val fallbackUrl = SmartPoolConstants.TEST_URL_FALLBACK
        val baselineUrl = SmartPoolConstants.BASELINE_TEST_URL
        assertTrue(fallbackUrl.startsWith("https://") || fallbackUrl.startsWith("http://"))
        assertTrue(baselineUrl.startsWith("https://") || baselineUrl.startsWith("http://"))
    }
}
