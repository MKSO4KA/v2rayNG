package com.v2ray.ang.handler

import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object MimicryHandler {

    interface MimicryCallback {
        fun onStarted(url: String)
        fun onIntercepted(current: Int, total: Int)
        fun onSuccess(result: Map<String, String>)
        fun onError(message: String)
        fun onTimeout()
    }

    private var serverSocket: ServerSocket? = null
    private var radarJob: Job? = null

    fun startRadar(scope: CoroutineScope, callback: MimicryCallback) {
        stopRadar()
        radarJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                val port = serverSocket?.localPort ?: return@launch
                val url = "http://127.0.0.1:$port/"
                
                withContext(Dispatchers.Main) {
                    callback.onStarted(url)
                }

                val captures = mutableListOf<Map<String, String>>()
                val lastCaptureTime = AtomicLong(0L)
                
                while (captures.size < 3 && isActive) {
                    serverSocket?.soTimeout = 30000
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            val firstLine = reader.readLine()
                            
                            if (firstLine != null && firstLine.uppercase(Locale.US).startsWith("GET")) {
                                val now = System.currentTimeMillis()
                                if (now - lastCaptureTime.get() > 1000L) {
                                    lastCaptureTime.set(now)
                                    val headers = mutableMapOf<String, String>()
                                    var line = reader.readLine()
                                    while (!line.isNullOrBlank()) {
                                        val split = line.split(":", limit = 2)
                                        if (split.size == 2) {
                                            headers[split[0].trim().lowercase(Locale.US)] = split[1].trim()
                                        }
                                        line = reader.readLine()
                                    }
                                    captures.add(headers)
                                    withContext(Dispatchers.Main) {
                                        callback.onIntercepted(captures.size, 3)
                                    }
                                }
                            }
                            
                            val fakeVless = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@127.0.0.1:443?encryption=none&security=none&type=tcp&headerType=none#MimicryDecoy"
                            val base64Vless = Utils.encode(fakeVless)
                            val bodyBytes = "$base64Vless\n".toByteArray()
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                            out.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray())
                            out.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
                            out.write("Connection: close\r\n\r\n".toByteArray())
                            out.write(bodyBytes)
                            out.flush()
                        }
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                }

                withContext(Dispatchers.Main) {
                    if (captures.size == 3) {
                        callback.onSuccess(analyzeCaptures(captures))
                    } else if (isActive) {
                        callback.onTimeout()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                stopRadar()
            }
        }
    }

    fun stopRadar() {
        radarJob?.cancel()
        radarJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun analyzeCaptures(captures: List<Map<String, String>>): Map<String, String> {
        val keysToMap = mapOf(
            "user-agent" to "UserAgent",
            "x-device-model" to "Model",
            "model" to "Model",
            "x-hwid" to "HWID",
            "hwid" to "HWID",
            "x-device-os" to "OS",
            "os" to "OS",
            "x-ver-os" to "OSVer",
            "os-version" to "OSVer",
            "x-app-version" to "AppVer",
            "app-version" to "AppVer",
            "accept-encoding" to "Encoding",
            "x-device-locale" to "Locale",
            "locale" to "Locale",
            "accept-language" to "Lang"
        )
        val result = mutableMapOf<String, String>()
        keysToMap.forEach { (headerKey, modelKey) ->
            if (!result.containsKey(modelKey)) {
                val vals = captures.mapNotNull { it[headerKey] }
                if (vals.size == 3) {
                    val v1 = vals[0]
                    val v2 = vals[1]
                    val v3 = vals[2]
                    if (v1 == v2 && v2 == v3) {
                        result[modelKey] = v1
                    } else {
                        result[modelKey] = generateMask(v1, v2, v3)
                    }
                }
            }
        }
        return result
    }

    private fun generateMask(s1: String, s2: String, s3: String): String {
        val minLen = minOf(s1.length, s2.length, s3.length)
        if (minLen == 0) return ""
        val sb = StringBuilder()
        sb.append("[[MASK]]")
        val differingLengths = (s1.length != s2.length || s2.length != s3.length)
        for (i in 0 until minLen) {
            val c1 = s1[i]
            val c2 = s2[i]
            val c3 = s3[i]
            if (c1 == c2 && c2 == c3) {
                sb.append(c1)
            } else if (c1.isDigit() && c2.isDigit() && c3.isDigit()) {
                sb.append("<<D>>")
            } else if (c1.isLowerCase() && c2.isLowerCase() && c3.isLowerCase()) {
                sb.append("<<L>>")
            } else if (c1.isUpperCase() && c2.isUpperCase() && c3.isUpperCase()) {
                sb.append("<<U>>")
            } else {
                sb.append("<<A>>")
            }
        }
        if (differingLengths) {
            val maxLen = maxOf(s1.length, s2.length, s3.length)
            sb.append("<<RND:${maxLen - minLen}>>")
        }
        return sb.toString()
    }
}
