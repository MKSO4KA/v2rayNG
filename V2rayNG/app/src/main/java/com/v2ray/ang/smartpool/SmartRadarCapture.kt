package com.v2ray.ang.smartpool

import com.v2ray.ang.util.LogUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class MimicryProfile(
    val userAgent: String = "v2rayNG/1.8.5",
    val model: String = "",
    val hwid: String = "",
    val os: String = "Android",
    val osVer: String = "",
    val appVer: String = "",
    val encoding: String = "gzip",
    val locale: String = "",
    val lang: String = ""
) {
    companion object {
        val HAPP_DEFAULT = MimicryProfile(
            userAgent = "v2raytun/android",
            model = "POCO 24069PC21G",
            hwid = "D663268B1803E487",
            os = "Android",
            osVer = "Android 16",
            appVer = "5.25.82",
            encoding = "gzip",
            locale = "",
            lang = ""
        )
    }
}

class SmartRadarCapture(private val port: Int = SmartPoolConstants.RADAR_PORT) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val captures = mutableListOf<Map<String, String>>()
    private val pool = Executors.newSingleThreadExecutor()

    fun start(onProgress: (Int) -> Unit = {}, onComplete: (MimicryProfile) -> Unit) {
        if (!isRunning.compareAndSet(false, true)) return
        captures.clear()
        pool.execute {
            try {
                serverSocket = ServerSocket(port)
                LogUtil.i(SmartPoolConstants.TAG, "Radar server started on port $port")
                while (isRunning.get() && captures.size < 3) {
                    val socket = serverSocket?.accept() ?: break
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val headers = mutableMapOf<String, String>()
                    var line: String?
                    while (!reader.readLine().also { line = it }.isNullOrBlank()) {
                        val parts = line!!.split(":", limit = 2)
                        if (parts.size == 2) {
                            headers[parts[0].trim().lowercase(Locale.ROOT)] = parts[1].trim()
                        }
                    }
                    captures.add(headers)
                    onProgress(captures.size)
                    val body = "dmxlc3M6Ly9iODMxMzgxZC02MzI0LTRkNTMtYWQ0Zi04Y2RhNDhiMzA4MTFAMTI3LjAuMC4xOjQ0Mz9lbmNyeXB0aW9uPW5vbmUmc2VjdXJpdHk9bm9uZSZ0eXBlPXRjcCZoZWFkZXJUeXBlPW5vbmUjTWltaWNyeURlY295Cg=="
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(resp.toByteArray())
                    socket.close()
                }
                val profile = buildProfile()
                onComplete(profile)
            } catch (_: Exception) {
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
        }
    }

    private fun buildProfile(): MimicryProfile {
        if (captures.isEmpty()) return MimicryProfile()
        val first = captures[0]
        return MimicryProfile(
            userAgent = first["user-agent"] ?: "v2rayNG/1.8.5",
            model = first["x-device-model"] ?: first["device-model"] ?: first["x-model"] ?: first["model"] ?: "",
            hwid = first["x-hwid"] ?: first["hwid"] ?: first["x-device-id"] ?: first["device-id"] ?: first["x-hardware-id"] ?: "",
            os = first["x-device-os"] ?: first["x-os"] ?: first["os"] ?: "",
            osVer = first["x-ver-os"] ?: first["x-os-version"] ?: first["os-version"] ?: first["x-os-ver"] ?: "",
            appVer = first["x-app-version"] ?: first["x-app-ver"] ?: first["app-version"] ?: "",
            encoding = first["accept-encoding"] ?: "gzip",
            locale = first["x-device-locale"] ?: first["x-locale"] ?: first["locale"] ?: "",
            lang = first["accept-language"] ?: ""
        )
    }
}
