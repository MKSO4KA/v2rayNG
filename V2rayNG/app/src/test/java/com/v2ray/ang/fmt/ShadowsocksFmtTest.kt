package com.v2ray.ang.fmt

import android.util.Base64
import android.util.Log
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.dto.entities.ProfileItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import java.net.URLDecoder
import java.util.Base64 as JavaBase64

class ShadowsocksFmtTest {
    companion object {
        private const val SS_SCHEME = "ss://"
    }

    private lateinit var mockBase64: MockedStatic<Base64>
    private lateinit var mockLog: MockedStatic<Log>

    private fun createSip002Url(method: String, password: String, host: String, port: Int, remarks: String): String {
        val methodPassword = "$method:$password"
        val base64UserInfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(methodPassword.toByteArray())
        return "$SS_SCHEME${base64UserInfo}@$host:$port#${remarks.replace(" ", "%20")}"
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
        mockBase64.`when`<String> { Base64.encodeToString(Mockito.any(ByteArray::class.java), Mockito.anyInt()) }.thenAnswer { invocation ->
            val input = invocation.arguments[0] as ByteArray
            val flags = invocation.arguments[1] as Int
            val isUrlSafe = (flags and Base64.URL_SAFE) != 0
            val noPadding = (flags and Base64.NO_PADDING) != 0
            var encoder = if (isUrlSafe) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
            if (noPadding) encoder = encoder.withoutPadding()
            encoder.encodeToString(input)
        }
    }

    @AfterEach
    fun tearDown() {
        mockLog.close()
        mockBase64.close()
    }

    @Test
    fun test_parseSip002_validUrlWithBase64EncodedUserinfo() {
        val ssUrl = createSip002Url("aes-256-gcm", "my-secret-password", "example.com", 8388, "Test Server")
        val result = ShadowsocksFmt.parseSip002(ssUrl)
        assertNotNull(result)
        assertEquals("Test Server", result?.remarks)
        assertEquals("example.com", result?.server)
        assertEquals("8388", result?.serverPort)
        assertEquals("aes-256-gcm", result?.method)
        assertEquals("my-secret-password", result?.password)
    }

    @Test
    fun test_parseSip002_validUrlWithPlainTextUserinfo() {
        val ssUrl = "ss://aes-256-gcm:mypassword@example.com:8388#Plain%20Server"
        val result = ShadowsocksFmt.parseSip002(ssUrl)
        assertNotNull(result)
        assertEquals("Plain Server", result?.remarks)
        assertEquals("aes-256-gcm", result?.method)
        assertEquals("mypassword", result?.password)
    }

    @Test
    fun test_parseSip002_withChacha20Encryption() {
        val ssUrl = createSip002Url("chacha20-ietf-poly1305", "secret123", "ss.example.com", 443, "ChaCha20")
        val result = ShadowsocksFmt.parseSip002(ssUrl)
        assertNotNull(result)
        assertEquals("chacha20-ietf-poly1305", result?.method)
    }

    @Test
    fun test_parseSip002_returnsNullForEmptyHost() {
        val base64UserInfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:password".toByteArray())
        val ssUrl = "${SS_SCHEME}${base64UserInfo}@:8388#No%20Host"
        assertNull(ShadowsocksFmt.parseSip002(ssUrl))
    }

    @Test
    fun test_parseSip002_returnsNullForInvalidPort() {
        val base64UserInfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:password".toByteArray())
        val ssUrl = "${SS_SCHEME}${base64UserInfo}@example.com:-1#Invalid%20Port"
        assertNull(ShadowsocksFmt.parseSip002(ssUrl))
    }

    @Test
    fun test_toUri_createsValidSip002Url() {
        val config = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
            remarks = "Test Server"
            server = "example.com"
            serverPort = "8388"
            method = "aes-256-gcm"
            password = "my-secret-password"
        }
        val uri = ShadowsocksFmt.toUri(config)
        assertFalse(uri.startsWith(SS_SCHEME), "toUri should not include scheme prefix")
        assertTrue(uri.contains("@example.com:8388"))
        assertTrue(uri.contains("#Test%20Server"))
        val base64Part = uri.substringBefore("@")
        val urlDecoded = URLDecoder.decode(base64Part, Charsets.UTF_8.name())
        val decoded = String(JavaBase64.getDecoder().decode(urlDecoded))
        assertEquals("aes-256-gcm:my-secret-password", decoded)
    }

    @Test
    fun test_toUri_handlesIpv6Address() {
        val config = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
            remarks = "IPv6 Server"
            server = "2001:db8::1"
            serverPort = "8388"
            method = "aes-256-gcm"
            password = "password"
        }
        val uri = ShadowsocksFmt.toUri(config)
        assertTrue(uri.contains("[2001:db8::1]:8388"))
    }

    @Test
    fun test_parseAndToUri_roundTripPreservesData() {
        val originalUrl = createSip002Url("chacha20-ietf-poly1305", "round-trip-password", "roundtrip.example.com", 443, "Round Trip Test")
        val parsed = ShadowsocksFmt.parse(originalUrl)
        assertNotNull(parsed)
        val regeneratedUri = ShadowsocksFmt.toUri(parsed!!)
        assertFalse(regeneratedUri.startsWith(SS_SCHEME))
        val reparsed = ShadowsocksFmt.parse("$SS_SCHEME$regeneratedUri")
        assertNotNull(reparsed)
        assertEquals(parsed.remarks, reparsed?.remarks)
        assertEquals(parsed.server, reparsed?.server)
        assertEquals(parsed.serverPort, reparsed?.serverPort)
        assertEquals(parsed.method, reparsed?.method)
        assertEquals(parsed.password, reparsed?.password)
    }

    @Test
    fun test_parse_handlesEmptyRemarksGracefully() {
        val base64UserInfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:password".toByteArray())
        val ssUrl = "${SS_SCHEME}${base64UserInfo}@example.com:8388#"
        val result = ShadowsocksFmt.parse(ssUrl)
        assertNotNull(result)
        assertEquals("none", result?.remarks)
    }

    @Test
    fun test_parseSip002_handlesDifferentEncryptionMethods() {
        listOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305").forEach { method ->
            val ssUrl = createSip002Url(method, "testpass", "example.com", 8388, method)
            val result = ShadowsocksFmt.parseSip002(ssUrl)
            assertNotNull(result, "Failed for method: $method")
            assertEquals(method, result?.method)
        }
    }
}

