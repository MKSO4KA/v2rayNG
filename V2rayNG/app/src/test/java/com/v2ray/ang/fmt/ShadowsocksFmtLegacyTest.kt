package com.v2ray.ang.fmt

import android.util.Base64
import android.util.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.util.Base64 as JavaBase64

class ShadowsocksFmtLegacyTest {
    companion object {
        private const val SS_SCHEME = "ss://"
    }

    private lateinit var mockBase64: MockedStatic<Base64>
    private lateinit var mockLog: MockedStatic<Log>

    private fun createLegacyUrl(method: String, password: String, host: String, port: Int, remarks: String): String {
        val legacyContent = "$method:$password@$host:$port"
        val base64Encoded = JavaBase64.getEncoder().encodeToString(legacyContent.toByteArray())
        return "$SS_SCHEME${base64Encoded}#${remarks.replace(" ", "%20")}"
    }

    @BeforeEach
    fun setUp() {
        mockLog = mockStatic(Log::class.java, Mockito.RETURNS_DEFAULTS)
        mockBase64 = mockStatic(Base64::class.java)
        mockBase64.`when`<ByteArray> { Base64.decode(Mockito.anyString(), Mockito.anyInt()) }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as String
            val flags = invocation.arguments[1] as Int
            val decoder = if ((flags and Base64.URL_SAFE) != 0) JavaBase64.getUrlDecoder() else JavaBase64.getDecoder()
            decoder.decode(input)
        }
    }

    @AfterEach
    fun tearDown() {
        mockLog.close()
        mockBase64.close()
    }

    @Test
    fun test_parseLegacy_validUrl() {
        val ssUrl = createLegacyUrl("aes-256-gcm", "password123", "legacy.example.com", 8388, "Legacy Server")
        val result = ShadowsocksFmt.parseLegacy(ssUrl)
        assertNotNull(result)
        assertEquals("Legacy Server", result?.remarks)
        assertEquals("legacy.example.com", result?.server)
        assertEquals("8388", result?.serverPort)
        assertEquals("aes-256-gcm", result?.method)
        assertEquals("password123", result?.password)
    }

    @Test
    fun test_parseLegacy_withPartiallyEncodedUrl() {
        val methodPassword = "chacha20-ietf-poly1305:my-pass"
        val base64Part = JavaBase64.getEncoder().encodeToString(methodPassword.toByteArray())
        val ssUrl = "${SS_SCHEME}${base64Part}@partial.example.com:443#Partial%20Encoded"
        val result = ShadowsocksFmt.parseLegacy(ssUrl)
        assertNotNull(result)
        assertEquals("Partial Encoded", result?.remarks)
        assertEquals("partial.example.com", result?.server)
        assertEquals("chacha20-ietf-poly1305", result?.method)
    }

    @Test
    fun test_parseLegacy_returnsNullForInvalidFormat() {
        val base64Encoded = JavaBase64.getEncoder().encodeToString("not-a-valid-format".toByteArray())
        val ssUrl = "${SS_SCHEME}${base64Encoded}#Invalid"
        val result = ShadowsocksFmt.parseLegacy(ssUrl)
        assertNull(result)
    }

    @Test
    fun test_parseLegacy_handlesPasswordWithColon() {
        val legacyContent = "aes-256-gcm:pass:word:with:colons@example.com:8388"
        val base64Encoded = JavaBase64.getEncoder().encodeToString(legacyContent.toByteArray())
        val ssUrl = "${SS_SCHEME}${base64Encoded}#Colon%20Password"
        val result = ShadowsocksFmt.parseLegacy(ssUrl)
        assertNotNull(result)
        assertEquals("pass:word:with:colons", result?.password)
    }

    @Test
    fun test_parseLegacy_convertsMethodToLowercase() {
        val legacyContent = "AES-256-GCM:password@example.com:8388"
        val base64Encoded = JavaBase64.getEncoder().encodeToString(legacyContent.toByteArray())
        val ssUrl = "${SS_SCHEME}${base64Encoded}#Uppercase%20Method"
        val result = ShadowsocksFmt.parseLegacy(ssUrl)
        assertNotNull(result)
        assertEquals("aes-256-gcm", result?.method)
    }
}
