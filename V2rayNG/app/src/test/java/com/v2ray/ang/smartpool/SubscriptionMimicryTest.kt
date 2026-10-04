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
        assertEquals("Happ/4.6.0/Android/17903223988441985697", happ.userAgent)
        assertEquals("RMX2063", happ.model)
        assertEquals("a35bb23fdaadd515", happ.hwid)
        assertEquals("Android", happ.os)
        assertEquals("11", happ.osVer)
        assertEquals("", happ.appVer)
        assertEquals("gzip", happ.encoding)
        assertEquals("ru", happ.locale)
        assertEquals("ru-RU,ru;q=0.9", happ.lang)
    }

    @Test
    fun testApplyMimicryProfileOnlyAddsNonBlankHeaders() {
        val happ = MimicryProfile.HAPP_DEFAULT
        val req = Request.Builder()
            .url("https://example.com/sub")
            .let { SmartSubFetcher.applyMimicryProfile(it, happ) }
            .build()

        assertEquals("Happ/4.6.0/Android/17903223988441985697", req.header("User-Agent"))
        assertEquals("RMX2063", req.header("X-Device-Model"))
        assertEquals("a35bb23fdaadd515", req.header("X-HWID"))
        assertEquals("Android", req.header("X-Device-OS"))
        assertEquals("11", req.header("X-Ver-OS"))
        assertNull(req.header("X-App-Version"))
        assertEquals("gzip", req.header("Accept-Encoding"))
        assertEquals("ru", req.header("X-Device-Locale"))
        assertEquals("ru-RU,ru;q=0.9", req.header("Accept-Language"))
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
