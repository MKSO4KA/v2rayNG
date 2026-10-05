package com.v2ray.ang.smartpool

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class SmartPoolProber(
    private val balancer: SmartPoolBalancer,
    private val probeIntervalMs: Long = 20000L,
    private val toleranceMs: Double = 0.0,
    private val customTestUrls: List<String> = emptyList(),
    private val customBaselineUrl: String? = null,
    private val probeFunc: ((localPort: Int, timeoutMs: Long) -> Long)? = null,
    private val recruiterProvider: (() -> SmartPoolRecruiter?)? = null
) {
    private val triggerChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    fun triggerEarlyProbe() {
        triggerChannel.trySend(Unit)
    }

    fun doProbe(localPort: Int, timeoutMs: Long = SmartPoolConstants.PROBE_TIMEOUT_MS): Long {
        return probeFunc?.invoke(localPort, timeoutMs) ?: probeLocalSocks(localPort, timeoutMs)
    }


    companion object {
        // Окно 59 минут (по 30 сек запаса с обеих сторон часа для стабилизации)
        const val TARGET_COLD_SWEEP_MS = 59 * 60 * 1000L

        fun calculateAdaptiveBatchSize(coldCount: Int, intervalMs: Long, targetSweepMs: Long = TARGET_COLD_SWEEP_MS): Int {
            if (coldCount <= 0) return 0
            val safeInterval = intervalMs.coerceAtLeast(1000L)
            val totalCycles = (targetSweepMs / safeInterval).coerceAtLeast(1L)
            return ceil(coldCount.toDouble() / totalCycles).toInt().coerceIn(1, 8)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private val coldSweepIndex = AtomicInteger(0)

    fun start() {
        if (running) return
        running = true
        LogUtil.i(AppConfig.TAG, "SmartPool: prober started (interval: ${probeIntervalMs / 1000}s, tolerance: ${toleranceMs}ms)")
        scope.launch {
            probeDirectBaseline()
            fastArrivalRace()
            probeActiveRing()
            probeLoop()
        }
    }


    suspend fun fastArrivalRace() {
        val candidates = balancer.listAll().filter { it.isAvailable() }.take(18)
        if (candidates.isEmpty()) return
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)
        coroutineScope {
            candidates.forEach { target ->
                launch {
                    val rtt = doProbe(target.localPort, 1500L)
                    if (rtt > 0) {
                        target.latencyMs = rtt
                        target.failCount = 0
                        target.lastSuccessTime = System.currentTimeMillis()
                        SmartPoolStorage.recordSuccess(target.hash, rtt)
                        val current = finishedCount.incrementAndGet()
                        if (current == 1) {
                            balancer.setEarlyLeader(target)
                            LogUtil.i(SmartPoolConstants.TAG, "⚡ [FastStart] Первый ответивший Лидер: '${target.profile.remarks}' (${rtt}ms, порт: ${target.localPort})")
                        } else if (balancer.getStandbyCount() < SmartPoolConstants.STANDBY_CAPACITY) {
                            balancer.addEarlyStandby(target)
                            LogUtil.i(SmartPoolConstants.TAG, "⚡ [FastStart] Добавлен в Standby #$current: '${target.profile.remarks}' (${rtt}ms, порт: ${target.localPort})")
                        } else {
                            recruiterProvider?.invoke()?.warmStash?.offer(target)
                        }
                    }
                }
            }
        }
        val alive = candidates.filter { it.latencyMs > 0 }
        if (alive.isNotEmpty()) {
            balancer.rebalanceTopTier(alive, toleranceMs)
        }
    }



    fun stop() {
        running = false
    }

    private suspend fun probeLoop() {
        while (scope.isActive && running) {
            kotlinx.coroutines.withTimeoutOrNull(probeIntervalMs) {
                triggerChannel.receive()
            }
            runCatching { probeDirectBaseline() }
            runCatching { probeActiveRing() }
        }
    }


    fun probeDirectBaseline() {
        val targets = listOfNotNull(
            customBaselineUrl?.takeIf { it.isNotBlank() },
            SmartPoolConstants.BASELINE_TEST_URL,
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        ).distinct()
        for (baselineTarget in targets) {
            val start = System.currentTimeMillis()
            try {
                val conn = URL(baselineTarget).openConnection() as? java.net.HttpURLConnection
                if (conn != null) {
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) {
                        val baseline = System.currentTimeMillis() - start
                        balancer.baselineLatencyMs = baseline
                        LogUtil.i(AppConfig.TAG, "SmartPool: direct network baseline = ${baseline}ms (via $baselineTarget)")
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        balancer.baselineLatencyMs = 0L
    }

    fun calibrateOnce() {
        scope.launch {
            probeDirectBaseline()
            fastArrivalRace()
            probeActiveRing()
        }
    }



    suspend fun probeActiveRing() {
        val hotNodes = balancer.getHotNodes()
        val warmNodes = recruiterProvider?.invoke()?.drainAllWarmNodes().orEmpty()
        val candidateRing = (hotNodes + warmNodes).distinctBy { it.localPort }
        if (candidateRing.isEmpty()) return

        val alive = java.util.concurrent.CopyOnWriteArrayList<SmartNodeState>()
        coroutineScope {
            for (node in candidateRing) {
                launch {
                    val rtt = doProbe(node.localPort)
                    node.latencyMs = rtt
                    if (rtt > 0) {
                        node.failCount = 0
                        node.lastSuccessTime = System.currentTimeMillis()
                        SmartPoolStorage.recordSuccess(node.hash, rtt)
                        alive.add(node)
                    } else if (node.localPort == balancer.getCurrentLeader()?.localPort) {
                        balancer.penalize(node)
                    }
                }
            }
        }
        if (alive.isNotEmpty()) {
            balancer.rebalanceTopTier(alive.toList(), toleranceMs)
            val leader = balancer.getCurrentLeader()
            LogUtil.i(AppConfig.TAG, "SmartPool: Active Ring calibrated, ${alive.size} alive. Leader: '${leader?.profile?.remarks}' (${leader?.latencyMs}ms)")
        }
    }

    fun probeHotGroup() {
        scope.launch { probeActiveRing() }
    }



    private suspend fun sweepColdCandidatesFairly() {
        val coldCandidates = balancer.getColdCandidates()
        if (coldCandidates.isEmpty()) return

        val now = System.currentTimeMillis()
        val availableCold = coldCandidates.filter { now >= it.cooldownUntil }
        if (availableCold.isEmpty()) return

        val batchSize = calculateAdaptiveBatchSize(availableCold.size, probeIntervalMs)
        val targets = mutableListOf<SmartNodeState>()
        for (i in 0 until batchSize) {
            val idx = (coldSweepIndex.getAndIncrement() % availableCold.size).coerceAtLeast(0)
            targets.add(availableCold[idx])
        }

        coroutineScope {
            targets.distinctBy { it.localPort }.forEach { target ->
                launch {
                    val rtt = doProbe(target.localPort)
                    target.latencyMs = rtt
                    if (rtt > 0) {
                        target.failCount = 0

                        target.lastSuccessTime = System.currentTimeMillis()
                        SmartPoolStorage.recordSuccess(target.hash, rtt)
                        if (balancer.replaceOrUpgradeStandby(target, toleranceMs)) {
                            LogUtil.i(SmartPoolConstants.TAG, "🔄 [ColdSweep] Добавлен/обновлен Standby: '${target.profile.remarks}' (${target.latencyMs}ms)")
                        } else if (balancer.needsReplenishment()) {
                            balancer.fillStandbys(listOf(target))
                        }
                    }
                }
            }
        }
    }

    fun probeLocalSocks(localPort: Int, timeoutMs: Long = SmartPoolConstants.PROBE_TIMEOUT_MS): Long {
        val candidateUrls = if (customTestUrls.isNotEmpty()) customTestUrls else listOf(SmartPoolConstants.TEST_URL_FALLBACK)
        val (authUser, authPass) = SmartPoolManager.getPoolAuthCredentials()
        val safeTimeout = timeoutMs.coerceIn(50L, SmartPoolConstants.PROBE_TIMEOUT_MS).toInt()

        if (candidateUrls.size == 1) {
            return probeSingleUrl(candidateUrls[0], localPort, safeTimeout, authUser, authPass)
        }

        val executor = java.util.concurrent.Executors.newFixedThreadPool(candidateUrls.size.coerceAtMost(4))
        val completion = java.util.concurrent.ExecutorCompletionService<Long>(executor)
        try {
            candidateUrls.forEach { url ->
                completion.submit {
                    probeSingleUrl(url, localPort, safeTimeout, authUser, authPass)
                }
            }
            var received = 0
            while (received < candidateUrls.size) {
                val future = completion.poll(safeTimeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                received++
                val res = runCatching { future.get() }.getOrDefault(-1L)
                if (res > 0) {
                    return res
                }
            }
        } finally {
            executor.shutdownNow()
        }
        return -1L
    }

    private fun probeSingleUrl(probeUrl: String, localPort: Int, safeTimeout: Int, authUser: String, authPass: String): Long {
        val start = System.currentTimeMillis()
        return runCatching {
            val urlObj = URL(probeUrl)
            val isHttps = urlObj.protocol.equals("https", ignoreCase = true)
            val targetHost = urlObj.host
            val targetPort = if (urlObj.port > 0) urlObj.port else if (isHttps) 443 else 80

            java.net.Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", localPort), safeTimeout)
                s.soTimeout = safeTimeout
                s.tcpNoDelay = true
                val cin = java.io.DataInputStream(s.getInputStream())
                val cout = s.getOutputStream()

                cout.write(byteArrayOf(0x05, 0x01, 0x02))
                cout.flush()
                val gResp = ByteArray(2)
                cin.readFully(gResp)
                if (gResp[0] != 0x05.toByte() || gResp[1] != 0x02.toByte()) return@runCatching -1L

                val uBytes = authUser.toByteArray(Charsets.UTF_8)
                val pBytes = authPass.toByteArray(Charsets.UTF_8)
                val aBuf = ByteArray(3 + uBytes.size + pBytes.size)
                aBuf[0] = 0x01
                aBuf[1] = uBytes.size.toByte()
                System.arraycopy(uBytes, 0, aBuf, 2, uBytes.size)
                aBuf[2 + uBytes.size] = pBytes.size.toByte()
                System.arraycopy(pBytes, 0, aBuf, 3 + uBytes.size, pBytes.size)
                cout.write(aBuf)
                cout.flush()
                val aResp = ByteArray(2)
                cin.readFully(aResp)
                if (aResp[0] != 0x01.toByte() || aResp[1] != 0x00.toByte()) return@runCatching -1L

                val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
                val reqBuf = ByteArray(4 + 1 + hostBytes.size + 2)
                reqBuf[0] = 0x05; reqBuf[1] = 0x01; reqBuf[2] = 0x00; reqBuf[3] = 0x03
                reqBuf[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, reqBuf, 5, hostBytes.size)
                reqBuf[reqBuf.size - 2] = (targetPort shr 8).toByte()
                reqBuf[reqBuf.size - 1] = (targetPort and 0xFF).toByte()
                cout.write(reqBuf)
                cout.flush()

                val connResp = ByteArray(4)
                cin.readFully(connResp)
                if (connResp[1] != 0x00.toByte()) return@runCatching -1L
                val atyp = connResp[3].toInt() and 0xFF
                val skipLen = when (atyp) { 0x01 -> 4; 0x04 -> 16; 0x03 -> cin.readByte().toInt() and 0xFF; else -> 4 }
                cin.skipBytes(skipLen + 2)

                val transportSocket = if (isHttps) {
                    val sslFactory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                    val sslSock = sslFactory.createSocket(s, targetHost, targetPort, true) as javax.net.ssl.SSLSocket
                    sslSock.startHandshake()
                    sslSock
                } else s

                val writer = java.io.OutputStreamWriter(transportSocket.getOutputStream(), Charsets.US_ASCII)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(transportSocket.getInputStream(), Charsets.US_ASCII))
                val path = if (urlObj.file.isNullOrBlank()) "/" else urlObj.file
                writer.write("GET $path HTTP/1.1\r\nHost: $targetHost\r\nConnection: close\r\nUser-Agent: Mozilla/5.0\r\n\r\n")
                writer.flush()

                val statusLine = reader.readLine() ?: return@runCatching -1L
                val parts = statusLine.split(" ")
                val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: -1 else -1
                if (statusCode in 200..399) {
                    System.currentTimeMillis() - start
                } else {
                    -1L
                }
            }
        }.getOrDefault(-1L)
    }
}
