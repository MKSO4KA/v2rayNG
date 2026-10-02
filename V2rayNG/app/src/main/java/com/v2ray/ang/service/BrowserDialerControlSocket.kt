package com.v2ray.ang.service

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

object BrowserDialerControlSocket {
    private val TOKEN_REGEX = Regex("""/websocket\?token=([^"'\s]+)""")

    fun resolveControlWsUrl(rawAddr: String, probeClient: OkHttpClient): String? {
        val uri = parseDialerUri(rawAddr) ?: return null
        val probeUrl = buildDialerProbeUrl(rawAddr) ?: return null
        val request = Request.Builder().url(probeUrl).get().build()
        val token = runCatching {
            probeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                extractControlToken(response.body.string())
            }
        }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return URI("ws", uri.userInfo, host, uri.port, "/websocket", "token=$token", null).toString()
    }

    private fun buildDialerProbeUrl(rawAddr: String): String? {
        val normalized = rawAddr.trim().ifEmpty { return null }
        val uri = parseDialerUri(normalized) ?: return null
        val host = uri.host ?: return null
        val probeScheme = when (uri.scheme?.lowercase()) {
            "https", "wss" -> "https"
            else -> "http"
        }
        return URI(probeScheme, uri.userInfo, host, uri.port, "/", null, null).toString()
    }

    private fun parseDialerUri(rawAddr: String): URI? {
        val normalized = rawAddr.trim().ifEmpty { return null }
        return runCatching { if (normalized.contains("://")) URI(normalized) else URI("http://$normalized") }.getOrNull()
    }

    private fun extractControlToken(html: String): String? = TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}
