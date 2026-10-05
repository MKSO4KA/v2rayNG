package com.v2ray.ang.smartpool

import android.os.Debug
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SmartPoolStatusServer(
    private val balancer: SmartPoolBalancer,
    private val recruiter: SmartPoolRecruiter?,
    private val port: Int = SmartPoolConstants.STATUS_PORT
) {
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            pool.execute { listenLoop() }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            runCatching { serverSocket?.close() }
            pool.shutdownNow()
        }
    }

    private fun listenLoop() {
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            LogUtil.i(SmartPoolConstants.TAG, "SmartPoolStatusServer listening on 127.0.0.1:$port")
            while (isRunning.get()) {
                val client = serverSocket?.accept() ?: break
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                LogUtil.w(SmartPoolConstants.TAG, "StatusServer socket error", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 2000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val reqLine = reader.readLine() ?: return
            val out = client.getOutputStream()

            if (reqLine.startsWith("GET /status") || reqLine.startsWith("GET / ")) {
                val json = buildStatusJson()
                val body = json.toByteArray(Charsets.UTF_8)
                val headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            } else {
                val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(notFound.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    private fun buildStatusJson(): String {
        val leader = balancer.getCurrentLeader()
        val allNodes = balancer.listAll()
        val hotNodes = balancer.getHotNodes()
        val standbys = hotNodes.filter { it.localPort != leader?.localPort }
        val hotPorts = (listOfNotNull(leader) + standbys).map { it.localPort }.toSet()
        val stashList = recruiter?.warmStash?.filter { it.localPort !in hotPorts } ?: emptyList()
        val stashPorts = stashList.map { it.localPort }.toSet()
        val coldNodes = balancer.getColdCandidates().filter { it.localPort !in hotPorts && it.localPort !in stashPorts }


        val javaHeap = try { ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)).toInt() } catch (_: Throwable) { 0 }
        val nativeHeap = try { (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt() } catch (_: Throwable) { 0 }


        val data = mapOf(
            "leader" to leader?.let {
                mapOf(
                    "remarks" to it.profile.remarks,
                    "port" to it.localPort,
                    "latencyMs" to it.latencyMs,
                    "failCount" to it.failCount,
                    "protocol" to it.profile.configType.name.lowercase(),
                    "server" to it.profile.server,
                    "isAvailable" to it.isAvailable()
                )
            },
            "standbys" to standbys.map {
                mapOf(
                    "remarks" to it.profile.remarks,
                    "port" to it.localPort,
                    "latencyMs" to it.latencyMs,
                    "failCount" to it.failCount,
                    "protocol" to it.profile.configType.name.lowercase(),
                    "server" to it.profile.server,
                    "isAvailable" to it.isAvailable()
                )
            },
            "stash" to stashList.map {
                mapOf(
                    "remarks" to it.profile.remarks,
                    "port" to it.localPort,
                    "latencyMs" to it.latencyMs,
                    "protocol" to it.profile.configType.name.lowercase()
                )
            },
            "cold" to coldNodes.map {
                mapOf(
                    "remarks" to it.profile.remarks,
                    "port" to it.localPort,
                    "latencyMs" to it.latencyMs,
                    "failCount" to it.failCount,
                    "protocol" to it.profile.configType.name.lowercase(),
                    "server" to it.profile.server,
                    "isAvailable" to it.isAvailable(),
                    "cooldownRemainingSec" to ((it.cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L) / 1000)
                )
            },
            "recruiter" to mapOf(
                "worstStandbyRTT" to balancer.getWorstStandbyRTT(),
                "stashSize" to stashList.size
            ),
            "pool" to mapOf(
                "total" to allNodes.size,
                "available" to allNodes.count { it.isAvailable() },
                "inCooldown" to allNodes.count { !it.isAvailable() },
                "activeConnections" to balancer.activeConnections.get(),
                "baselineLatencyMs" to balancer.baselineLatencyMs
            ),
            "memory" to mapOf(
                "javaHeapMb" to javaHeap,
                "nativeHeapMb" to nativeHeap
            )
        )
        return JsonUtil.toJson(data)
    }
}
