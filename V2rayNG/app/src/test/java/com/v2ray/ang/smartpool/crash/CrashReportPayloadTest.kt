package com.v2ray.ang.smartpool.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrashReportPayloadTest {

    @Test
    fun testTitleAndLabelsFormatting() {
        val payload = CrashReportPayload().apply {
            deviceManufacturer = "Xiaomi"
            deviceModel = "Poco F6"
            androidVersion = "14"
            androidSdkInt = 34
            appVersionName = "2.3.10"
            appVersionCode = 750
        }

        assertEquals("[Crash] Xiaomi Poco F6 | Android 14 (API 34) | v2.3.10 (750)", payload.buildTitle())

        val labels = payload.buildLabels()
        assertTrue(labels.contains("crash-core"))
        assertTrue(labels.contains("Xiaomi Poco F6"))
        assertTrue(labels.contains("API 34"))
        assertTrue(labels.contains("v2.3.10"))
    }

    @Test
    fun testMarkdownBodyContainsMetadata() {
        val payload = CrashReportPayload().apply {
            deviceManufacturer = "Realme"
            deviceModel = "6 Pro"
            deviceIdHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
            errorSummary = "Outbound TLS verification failed"
            encryptedAgePayload = "-----BEGIN AGE ENCRYPTED FILE-----\ntest\n-----END AGE ENCRYPTED FILE-----"
        }

        val md = payload.buildMarkdownBody()
        assertTrue(md.contains("Device-ID-Hash:"))
        assertTrue(md.contains("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"))
        assertTrue(md.contains("BEGIN AGE ENCRYPTED FILE"))
    }
}
