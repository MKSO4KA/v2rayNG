package com.v2ray.ang.smartpool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartRadarCaptureTest {

    @Test
    fun testDefaultMimicryProfile() {
        val profile = MimicryProfile()
        assertEquals("v2rayNG/1.8.5", profile.userAgent)
        assertEquals("Android", profile.os)
        assertEquals("gzip", profile.encoding)
    }
}
