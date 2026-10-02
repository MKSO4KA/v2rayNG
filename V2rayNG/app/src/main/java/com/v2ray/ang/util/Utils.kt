package com.v2ray.ang.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Utils {
    fun parseInt(str: String?, default: Int = 0): Int = str?.toIntOrNull() ?: default
    fun getClipboard(context: Context): String = EncodingUtils.getClipboard(context)
    fun setClipboard(context: Context, content: String) = EncodingUtils.setClipboard(context, content)
    fun decode(text: String?): String = EncodingUtils.decode(text)
    fun encode(text: String, removePadding: Boolean = false): String = EncodingUtils.encode(text, removePadding)
    fun getUuid(): String = EncodingUtils.getUuid()
    fun decodeURIComponent(url: String): String = EncodingUtils.decodeURIComponent(url)
    fun encodeURIComponent(url: String): String = EncodingUtils.encodeURIComponent(url)

    fun isIpAddress(value: String?): Boolean = NetUtils.isIpAddress(value)
    fun isPureIpAddress(value: String): Boolean = NetUtils.isPureIpAddress(value)
    fun isDomainName(input: String?): Boolean = NetUtils.isDomainName(input)
    fun isCoreDNSAddress(s: String): Boolean = NetUtils.isCoreDNSAddress(s)
    fun isValidUrl(value: String?): Boolean = NetUtils.isValidUrl(value)
    fun getIpv6Address(address: String?): String = NetUtils.getIpv6Address(address)
    fun fixIllegalUrl(str: String): String = NetUtils.fixIllegalUrl(str)
    fun findRandomFreePort(): Int = NetUtils.findRandomFreePort()
    fun isValidSubUrl(value: String?): Boolean = NetUtils.isValidSubUrl(value)
    fun isIpInCidr(ip: String, cidr: String): Boolean = NetUtils.isIpInCidr(ip, cidr)

    fun openUri(context: Context, uriString: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uriString.toUri()))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to open URI", e)
        }
    }

    fun readTextFromAssets(context: Context?, fileName: String): String {
        if (context == null) return ""
        return try {
            context.assets.open(fileName).use { inputStream ->
                inputStream.bufferedReader().use { reader -> reader.readText() }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read asset file: $fileName", e)
            ""
        }
    }

    fun userAssetPath(context: Context?): String {
        if (context == null) return ""
        return try {
            context.getDir(AppConfig.DIR_ASSETS, Context.MODE_PRIVATE).absolutePath
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get user asset path", e)
            ""
        }
    }

    fun getDeviceIdForXUDPBaseKey(): String {
        return try {
            val androidId = Settings.Secure.ANDROID_ID.toByteArray(Charsets.UTF_8)
            Base64.encodeToString(androidId.copyOf(32), Base64.NO_PADDING.or(Base64.URL_SAFE))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to generate device ID", e)
            ""
        }
    }

    fun receiverFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.RECEIVER_EXPORTED
    } else {
        ContextCompat.RECEIVER_NOT_EXPORTED
    }

    fun isXray(): Boolean = BuildConfig.APPLICATION_ID.startsWith("com.v2ray.ang")

    fun formatTimestamp(ts: Long?, pattern: String = "yyyy-MM-dd HH:mm", locale: Locale = Locale.getDefault()): String {
        if (ts == null || ts <= 0L) return ""
        return try {
            SimpleDateFormat(pattern, locale).format(Date(ts))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to format timestamp", e)
            ""
        }
    }
}

