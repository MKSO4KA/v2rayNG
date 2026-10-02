package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.extension.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DialerNativeService : IDialerService {
    private val CONTROL_SOCKET_IDLE = 0
    private val CONTROL_SOCKET_OPENING = 1
    private val CONTROL_LOOP_DELAY_MS = 1000L

    @Volatile private var serviceJob = SupervisorJob()
    @Volatile private var scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val controlSocketState = AtomicInteger(CONTROL_SOCKET_IDLE)
    private val controlSockets = ConcurrentHashMap.newKeySet<WebSocket>()
    @Volatile private var controlUrl: String? = null
    private var loopJob: Job? = null
    private var client: OkHttpClient? = null

    override fun start(context: Context, dialerAddr: String) {
        stop()
        serviceJob = SupervisorJob()
        scope = CoroutineScope(serviceJob + Dispatchers.IO)
        if (dialerAddr.isEmpty()) return
        val nativeClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        client = nativeClient
        running.set(true)
        loopJob = scope.launch {
            val resolvedControlUrl = BrowserDialerControlSocket.resolveControlWsUrl(dialerAddr, nativeClient) ?: run {
                running.set(false)
                return@launch
            }
            controlUrl = resolvedControlUrl
            openControlSocket()
            while (isActive && running.get()) {
                openControlSocket()
                delay(CONTROL_LOOP_DELAY_MS)
            }
        }
    }

    override fun stop() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        serviceJob.cancel()
        controlSockets.toTypedArray().forEach { runCatching { it.close(1000, "stopped") } }
        controlSockets.clear()
        controlSocketState.set(CONTROL_SOCKET_IDLE)
        controlUrl = null
        val oldClient = client
        client = null
        oldClient?.dispatcher?.cancelAll()
        oldClient?.connectionPool?.evictAll()
    }

    private fun openControlSocket(): Boolean {
        val localClient = client ?: return false
        if (!running.get()) return false
        val url = controlUrl ?: return false
        if (!controlSocketState.compareAndSet(CONTROL_SOCKET_IDLE, CONTROL_SOCKET_OPENING)) return false
        val request = Request.Builder().url(url).build()
        return runCatching {
            val socket = localClient.newWebSocket(request, ControlSocketListener(url))
            controlSockets.add(socket)
            true
        }.getOrElse {
            controlSocketState.set(CONTROL_SOCKET_IDLE)
            false
        }
    }

    private inner class ControlSocketListener(private val url: String) : WebSocketListener() {
        private val taskAccepted = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (taskAccepted.compareAndSet(false, true)) {
                controlSocketState.set(CONTROL_SOCKET_IDLE)
                scope.launch { if (running.get()) openControlSocket() }
                handleTask(webSocket, BrowserDialerTask.parse(text))
            }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!taskAccepted.get()) {
                runCatching { webSocket.send("fail"); webSocket.close(1002, "text json required") }
            }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = cleanup(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = cleanup(webSocket)

        private fun cleanup(webSocket: WebSocket) {
            if (!closed.compareAndSet(false, true)) return
            controlSockets.remove(webSocket)
            if (!taskAccepted.get()) {
                controlSocketState.set(CONTROL_SOCKET_IDLE)
                scope.launch { if (running.get()) openControlSocket() }
            }
        }

        private fun handleTask(webSocket: WebSocket, task: BrowserDialerTask?) {
            if (task == null) {
                runCatching { webSocket.send("fail"); webSocket.close(1007, "invalid task") }
                return
            }
            val localClient = client ?: return
            when {
                task.method == "WS" -> handleWs(webSocket, task, localClient)
                task.method == "GET" && task.streamResponse -> handleStream(webSocket, task, localClient)
                else -> handleUnary(webSocket, task, localClient)
            }
        }

        private fun handleWs(control: WebSocket, task: BrowserDialerTask, localClient: OkHttpClient) {
            val req = Request.Builder().url(task.url).build()
            localClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, resp: Response) { runCatching { control.send("ok") } }
                override fun onMessage(ws: WebSocket, text: String) { runCatching { control.send(text) } }
                override fun onMessage(ws: WebSocket, bytes: ByteString) { runCatching { control.send(bytes) } }
                override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) { runCatching { control.send("fail"); control.close(1011, "err") } }
            })
        }

        private fun handleStream(control: WebSocket, task: BrowserDialerTask, localClient: OkHttpClient) {
            runCatching { control.send("ok") }
            scope.launch {
                runCatching {
                    localClient.newCall(Request.Builder().url(task.url).get().build()).execute().use { resp ->
                        resp.body.byteStream().use { input ->
                            val buf = ByteArray(8192)
                            while (running.get()) {
                                val read = input.read(buf)
                                if (read == -1) break
                                control.send(buf.toByteString(0, read))
                            }
                        }
                    }
                }
                runCatching { control.close(1000, "done") }
            }
        }

        private fun handleUnary(control: WebSocket, task: BrowserDialerTask, localClient: OkHttpClient) {
            runCatching { control.send("ok") }
            scope.launch {
                runCatching {
                    localClient.newCall(Request.Builder().url(task.url).method(task.method, null).build()).execute().use { resp ->
                        control.send(if (resp.isSuccessful) "ok" else "fail")
                    }
                }
                runCatching { control.close(1000, "done") }
            }
        }
    }
}