package com.v2ray.ang.smartpool

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SmartCascadeFallbackTest {

    @Test
    fun testDirectMimicryProfileInitialization() {
        val profile = MimicryProfile()
        assertNotNull(profile.userAgent)
        assertNotNull(profile.model)
        assertNotNull(profile.hwid)
    }

    @Test
    fun testGzipDecompressionInSmartSubFetcher() {
        val original = "vmess://test-node-payload-data"
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        val compressed = bos.toByteArray()

        val decompressed = SmartSubFetcher.decompressBytes(compressed, "gzip")
        assertEquals(original, decompressed, "GZIP данные должны быть успешно распакованы")
    }

    @Test
    fun testPlainPayloadReturnsUnchanged() {
        val plainText = "vless://uuid@1.2.3.4:443?type=tcp#TestNode"
        val decompressed = SmartSubFetcher.decompressBytes(plainText.toByteArray(Charsets.UTF_8), null)
        assertEquals(plainText, decompressed, "Обычный текст без сжатия должен возвращаться без изменений")
    }
}
