package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubscriptionMimicryTest {

    @Test
    fun testHappDefaultPresetValues() {
        val happ = MimicryProfile.HAPP_DEFAULT
        assertEquals("v2raytun/android", happ.userAgent)
        assertEquals("POCO 24069PC21G", happ.model)
        assertEquals("D663268B1803E487", happ.hwid)
        assertEquals("Android", happ.os)
        assertEquals("Android 16", happ.osVer)
        assertEquals("5.25.82", happ.appVer)
        assertEquals("gzip", happ.encoding)
        assertEquals("", happ.locale)
        assertEquals("", happ.lang)
    }

    @Test
    fun testApplyMimicryProfileOnlyAddsNonBlankHeaders() {
        val happ = MimicryProfile.HAPP_DEFAULT
        val req = Request.Builder()
            .url("https://example.com/sub")
            .let { SmartSubFetcher.applyMimicryProfile(it, happ) }
            .build()

        assertEquals("v2raytun/android", req.header("User-Agent"))
        assertEquals("POCO 24069PC21G", req.header("X-Device-Model"))
        assertEquals("D663268B1803E487", req.header("X-HWID"))
        assertEquals("Android", req.header("X-Device-OS"))
        assertEquals("Android 16", req.header("X-Ver-OS"))
        assertEquals("5.25.82", req.header("X-App-Version"))
        assertEquals("gzip", req.header("Accept-Encoding"))
        assertNull(req.header("X-Device-Locale"))
        assertNull(req.header("Accept-Language"))
    }

    @Test
    fun testSubscriptionItemMimicryProfileConversion() {
        val sub = SubscriptionItem(remarks = "Target")
        sub.applyMimicryProfile(MimicryProfile.HAPP_DEFAULT)
        val profile = sub.toMimicryProfile()
        assertEquals(MimicryProfile.HAPP_DEFAULT.model, profile.model)
        assertEquals(MimicryProfile.HAPP_DEFAULT.hwid, profile.hwid)
    }
}
