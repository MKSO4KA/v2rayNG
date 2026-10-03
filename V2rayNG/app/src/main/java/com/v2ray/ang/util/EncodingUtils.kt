package com.v2ray.ang.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.v2ray.ang.AppConfig
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

object EncodingUtils {
    fun getClipboard(context: Context): String {
        return try {
            val cmb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cmb.primaryClip?.getItemAt(0)?.text.toString()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to get clipboard content", e)
            ""
        }
    }

    fun setClipboard(context: Context, content: String) {
        try {
            val cmb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cmb.setPrimaryClip(ClipData.newPlainText(null, content))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to set clipboard content", e)
        }
    }

    fun decode(text: String?): String {
        return tryDecodeBase64(text) ?: text?.trimEnd('=')?.let { tryDecodeBase64(it) }.orEmpty()
    }

    private fun tryDecodeBase64(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val clean = text.trim().replace("\r", "").replace("\n", "")
        return runCatching {
            java.util.Base64.getDecoder().decode(clean).toString(Charsets.UTF_8)
        }.recoverCatching {
            java.util.Base64.getUrlDecoder().decode(clean).toString(Charsets.UTF_8)
        }.recoverCatching {
            Base64.decode(clean, Base64.NO_WRAP).toString(Charsets.UTF_8)
        }.recoverCatching {
            Base64.decode(clean, Base64.NO_WRAP.or(Base64.URL_SAFE)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun encode(text: String, removePadding: Boolean = false): String {
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            var encoded = runCatching { java.util.Base64.getEncoder().encodeToString(bytes) }
                .getOrElse { Base64.encodeToString(bytes, Base64.NO_WRAP) }
            if (removePadding) encoded = encoded.trimEnd('=')
            encoded
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to encode text to base64", e)
            ""
        }
    }

    fun getUuid(): String {
        return try {
            UUID.randomUUID().toString().replace("-", "")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to generate UUID", e)
            ""
        }
    }

    fun decodeURIComponent(url: String): String {
        return try {
            URLDecoder.decode(url.replace("+", "%2B"), Charsets.UTF_8.toString())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to decode encodeURIComponent", e)
            url
        }
    }

    fun encodeURIComponent(url: String): String {
        return try {
            URLEncoder.encode(url, Charsets.UTF_8.toString()).replace("+", "%20")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to encode encodeURIComponent", e)
            url
        }
    }
}
