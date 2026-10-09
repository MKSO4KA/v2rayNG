package com.v2ray.ang.smartpool.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CrashReportUploadIntegrationTest {

    @Test
    fun testCrashReportLiveUploadWhenTokenPassed() {
        val token = System.getProperty("github.token")?.trim().orEmpty()
        println(">>> [CRASH TEST] Token provided: ${if (token.isNotBlank()) "YES (len: ${token.length})" else "NO"}")

        val payload = CrashReportPayload().apply {
            deviceManufacturer = "Xiaomi"
            deviceModel = "Poco F6"
            androidVersion = "14"
            androidSdkInt = 34
            appVersionName = "2.3.10"
            appVersionCode = 750
            deviceIdHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            errorSummary = "Integration Test: Automated Crash Report"
            encryptedAgePayload = AgeEncryption.encryptToArmor("Log test sample line 1\nLog test sample line 2".toByteArray(Charsets.UTF_8))
        }

        val title = payload.buildTitle()
        val body = payload.buildMarkdownBody()
        val labels = payload.buildLabels()
        assertTrue(title.isNotEmpty())
        assertTrue(body.contains("BEGIN AGE ENCRYPTED FILE"))
        assertTrue(labels.contains("crash-core"))

        if (token.isBlank()) {
            println("[SKIP LIVE UPLOAD] No TOKEN flag passed. To test live upload, run: make test_crash TOKEN=\"your_github_token\"")
            return
        }

        val repo = "MKSO4KA/v2rayNG"
        CrashReportUploader.ensureLabelsColors(repo, token, labels)

        val url = "https://api.github.com/repos/$repo/issues"
        val jsonBody = payload.toJson()

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            println("[GITHUB API RESPONSE] Code: ${response.code}")
            val respBody = response.body?.string().orEmpty()
            if (response.code != 201) {
                println("[GITHUB API ERROR BODY] $respBody")
            }
            assertEquals(201, response.code, "Expected 201 Created from GitHub API, but got ${response.code}: $respBody")
        }
    }
}
