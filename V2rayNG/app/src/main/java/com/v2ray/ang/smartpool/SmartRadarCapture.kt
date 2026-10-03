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
    val model: String = "Android-Device",
    val hwid: String = "[[MASK]]<<RND:16>>",
    val os: String = "Android",
    val osVer: String = "14",
    val appVer: String = "1.8.5",
    val encoding: String = "gzip",
    val locale: String = "ru_RU",
    val lang: String = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
)

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
            model = first["x-device-model"] ?: first["model"] ?: "Android-Device",
            hwid = first["x-hwid"] ?: "[[MASK]]<<RND:16>>",
            os = first["x-device-os"] ?: "Android",
            osVer = first["x-ver-os"] ?: "14",
            appVer = first["x-app-version"] ?: "1.8.5",
            encoding = first["accept-encoding"] ?: "gzip",
            locale = first["x-device-locale"] ?: "ru_RU",
            lang = first["accept-language"] ?: "ru-RU,ru;q=0.9"
        )
    }
}
