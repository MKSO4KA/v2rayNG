package com.v2ray.ang.smartpool.crash

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.util.JsonUtil
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CrashReportPayload {
    var deviceManufacturer: String = runCatching { Build.MANUFACTURER.orEmpty() }.getOrDefault("")
    var deviceModel: String = runCatching { Build.MODEL.orEmpty() }.getOrDefault("")
    var deviceAbi: String = runCatching { Build.SUPPORTED_ABIS?.firstOrNull().orEmpty() }.getOrDefault("")
    var androidVersion: String = runCatching { Build.VERSION.RELEASE.orEmpty() }.getOrDefault("")
    var androidSdkInt: Int = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0)
    var appVersionName: String = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("2.3.10")
    var appVersionCode: Int = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(750)
    var buildTimeUtc: String = ""
    var incidentTimeUtc: String = ""
    var deviceIdHash: String = ""
    var errorSummary: String = ""
    var errorStackTrace: String? = null
    var quarantinedNodeTag: String? = null
    var quarantinedNodeRemarks: String? = null
    var branch: String = "clean-core"
    var customLabels: MutableList<String> = mutableListOf()
    var encryptedAgePayload: String? = null

    fun populateContext(context: Context, error: Throwable, nodeTag: String? = null, nodeRemarks: String? = null) {
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        incidentTimeUtc = fmt.format(Date(now))
        buildTimeUtc = runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            fmt.format(Date(pkg.lastUpdateTime))
        }.getOrDefault(incidentTimeUtc)

        deviceIdHash = computeHwidHash(context)
        errorSummary = error.message?.take(200) ?: error.javaClass.simpleName
        errorStackTrace = error.stackTraceToString().take(4000)
        quarantinedNodeTag = nodeTag
        quarantinedNodeRemarks = nodeRemarks
    }

    fun buildTitle(): String {
        return "[Crash] $deviceManufacturer $deviceModel | Android $androidVersion (API $androidSdkInt) | v$appVersionName ($appVersionCode)"
    }

    fun buildLabels(): List<String> {
        val base = mutableListOf("crash-core", "$deviceManufacturer $deviceModel", "API $androidSdkInt", "v$appVersionName")
        base.addAll(customLabels)
        return base.distinct()
    }

    fun buildMarkdownBody(): String {
        return buildString {
            append("> [!CAUTION]\n")
            append("> **Xray Core Crash / Validation Failure**\n")
            append("> Summary: ").append(errorSummary).append("\n\n")
            append("### 📱 Device & Environment\n")
            append("- **Device:** ").append(deviceManufacturer).append(" ").append(deviceModel).append(" (ABI: ").append(deviceAbi).append(")\n")
            append("- **Android:** ").append(androidVersion).append(" (API ").append(androidSdkInt).append(")\n")
            append("- **App Version:** ").append(appVersionName).append(" (Code: ").append(appVersionCode).append(")\n")
            append("- **Branch:** ").append(branch).append("\n")
            append("- **Build Date:** ").append(buildTimeUtc).append("\n")
            append("- **Incident Date:** ").append(incidentTimeUtc).append("\n")
            append("- **Device-ID-Hash:** `").append(deviceIdHash).append("`\n")
            if (!quarantinedNodeTag.isNullOrBlank()) {
                append("- **Quarantined Node:** `").append(quarantinedNodeTag).append("` (").append(quarantinedNodeRemarks.orEmpty()).append(")\n")
            }
            append("\n---\n\n")
            append("### 🔒 Encrypted Diagnostics (`crashlog.age`)\n")
            append("<details open>\n<summary><b>Click to expand encrypted payload</b></summary>\n\n")
            append("```\n")
            append(encryptedAgePayload.orEmpty().trim())
            append("\n```\n</details>\n")
        }
    }

    fun toJson(): String {
        val map = mapOf(
            "title" to buildTitle(),
            "body" to buildMarkdownBody(),
            "labels" to buildLabels()
        )
        return JsonUtil.toJson(map)
    }

    companion object {
        fun computeHwidHash(context: Context): String {
            val persistentUuid = com.v2ray.ang.handler.MmkvManager.decodeSettingsString("pref_device_install_uuid").let {
                if (!it.isNullOrBlank()) it
                else java.util.UUID.randomUUID().toString().also {
                    newId -> com.v2ray.ang.handler.MmkvManager.encodeSettings("pref_device_install_uuid", newId)
                }
            }
            val androidId = runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            val raw = "$persistentUuid|${Build.MANUFACTURER}|${Build.MODEL}|${Build.HARDWARE}|$androidId"
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
