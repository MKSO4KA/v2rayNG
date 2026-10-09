package com.v2ray.ang.smartpool.crash

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object CrashReportUploader {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun dispatchCrash(context: Context, payload: CrashReportPayload) = withContext(Dispatchers.IO) {
        val token = MmkvManager.decodeSettingsString(AppConfig.PREF_GITHUB_REPORT_TOKEN).orEmpty()
        val repo = MmkvManager.decodeSettingsString(AppConfig.PREF_GITHUB_REPORT_REPO, AppConfig.DEFAULT_GITHUB_REPORT_REPO).orEmpty()
        if (token.isBlank() || repo.isBlank()) {
            LogUtil.d(AppConfig.TAG, "[CrashReport] GitHub reporting token not configured, skipping issue creation")
            return@withContext
        }

        ensureLabelsColors(repo, token, payload.buildLabels())

        val spoolFile = writeSpool(context, payload)
        val jsonBody = payload.toJson()
        val success = tryCascadeUpload(repo, token, jsonBody)
        if (success) {
            spoolFile.delete()
            LogUtil.i(AppConfig.TAG, "[CrashReport] Issue created successfully, spool file removed")
        } else {
            LogUtil.w(AppConfig.TAG, "[CrashReport] Failed to upload crash report, queued in spool")
        }
    }

    fun ensureLabelsColors(repo: String, token: String, labels: List<String>) {
        val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).build()
        for (label in labels) {
            val color = pickLabelColor(label)
            val postMap = mapOf("name" to label, "color" to color)
            val postReq = Request.Builder()
                .url("https://api.github.com/repos/$repo/labels")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(JsonUtil.toJson(postMap).toRequestBody(JSON_MEDIA))
                .build()

            val statusCode = runCatching {
                client.newCall(postReq).execute().use { it.code }
            }.getOrDefault(0)

            if (statusCode == 422) {
                val encoded = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
                val patchMap = mapOf("color" to color)
                val patchReq = Request.Builder()
                    .url("https://api.github.com/repos/$repo/labels/$encoded")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .patch(JsonUtil.toJson(patchMap).toRequestBody(JSON_MEDIA))
                    .build()
                runCatching { client.newCall(patchReq).execute().close() }
            }
        }
    }

    private fun pickLabelColor(label: String): String = when {
        label == "crash-core" -> "d73a4a" // Red
        label.startsWith("API ") -> "008672" // Emerald green
        label.startsWith("v") && label.contains(".") -> "0366d6" // Blue
        else -> "7057ff" // Violet for device models
    }

    private fun writeSpool(context: Context, payload: CrashReportPayload): File {
        val dir = File(context.filesDir, "crashlogs").apply { mkdirs() }
        val file = File(dir, "crash_${System.currentTimeMillis()}.json")
        file.writeText(payload.toJson(), Charsets.UTF_8)
        return file
    }

    private fun tryCascadeUpload(repo: String, token: String, jsonBody: String): Boolean {
        val url = "https://api.github.com/repos/$repo/issues"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()

        // Tier 1: Direct
        val directClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).build()
        if (executeRequest(directClient, req)) return true

        // Tier 2: Local proxy
        val proxyClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808)))
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
        if (executeRequest(proxyClient, req)) return true

        // Tier 3: Micro-pool port fallback
        val poolClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 30000)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        return executeRequest(poolClient, req)
    }

    private fun executeRequest(client: OkHttpClient, req: Request): Boolean {
        return try {
            client.newCall(req).execute().use { resp ->
                resp.code == 201
            }
        } catch (_: Exception) {
            false
        }
    }
}
