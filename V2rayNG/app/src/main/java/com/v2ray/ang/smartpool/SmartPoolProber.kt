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
    private val toleranceMs: Double = 0.0
) {
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
        fastArrivalRace()
        scope.launch { probeLoop() }
    }

    fun fastArrivalRace() {
        val candidates = balancer.getColdCandidates()
        if (candidates.isEmpty()) return
        val raceBatch = candidates.take(16)
        val finishedCount = java.util.concurrent.atomic.AtomicInteger(0)
        scope.launch {
            raceBatch.forEach { target ->
                launch {
                    val rtt = probeLocalSocks(target.localPort)
                    if (rtt > 0) {
                        target.latencyMs = rtt
                        target.penalty = 0
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
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
    }

    private suspend fun probeLoop() {
        while (scope.isActive && running) {
            delay(probeIntervalMs)
            runCatching { probeDirectBaseline() }
            runCatching { probeHotGroup() }
            runCatching { sweepColdCandidatesFairly() }
        }
    }

    fun probeDirectBaseline() {
        val start = System.currentTimeMillis()
        try {
            val conn = URL(SmartPoolConstants.BASELINE_TEST_URL).openConnection()
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.getInputStream().use { it.read(ByteArray(32)) }
            val baseline = System.currentTimeMillis() - start
            balancer.baselineLatencyMs = baseline
            LogUtil.i(AppConfig.TAG, "SmartPool: direct network baseline = ${baseline}ms")
        } catch (_: Exception) {
            balancer.baselineLatencyMs = 0L
        }
    }

    fun calibrateOnce() {
        probeDirectBaseline()
        fastArrivalRace()
        probeHotGroup()
    }

    fun probeHotGroup() {
        val hotNodes = balancer.getHotNodes()
        if (hotNodes.isEmpty()) return
        val alive = mutableListOf<SmartNodeState>()
        for (node in hotNodes) {
            val rtt = probeLocalSocks(node.localPort)
            node.latencyMs = rtt
            if (rtt > 0) {
                node.penalty = 0
                node.failCount = 0
                node.lastSuccessTime = System.currentTimeMillis()
                SmartPoolStorage.recordSuccess(node.hash, rtt)
                alive.add(node)
            } else {
                balancer.penalize(node)
            }
        }
        if (alive.isNotEmpty()) {
            balancer.updateStandbys(alive, toleranceMs)
            val leader = balancer.getCurrentLeader()
            LogUtil.i(AppConfig.TAG, "SmartPool: hot probe verified ${alive.size}/${hotNodes.size} alive nodes, leader: '${leader?.profile?.remarks}' (${leader?.latencyMs}ms)")
        }
    }

    private suspend fun sweepColdCandidatesFairly() {
        val coldCandidates = balancer.getColdCandidates()
        if (coldCandidates.isEmpty()) return

        val now = System.currentTimeMillis()
        // Исключаем ноды в активном кулдауне из борьбы за слот
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
                    val rtt = probeLocalSocks(target.localPort)
                    target.latencyMs = rtt
                    if (rtt > 0) {
                        target.penalty = 0
                        target.failCount = 0
                        target.lastSuccessTime = System.currentTimeMillis()
                        SmartPoolStorage.recordSuccess(target.hash, rtt)
                        if (balancer.needsReplenishment()) {
                            balancer.fillStandbys(listOf(target))
                        }
                    } else {
                        balancer.penalize(target)
                    }
                }
            }
        }
    }

    fun probeLocalSocks(localPort: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            val conn = URL(SmartPoolConstants.TEST_URL_FALLBACK).openConnection(proxy)
            conn.connectTimeout = SmartPoolConstants.PROBE_TIMEOUT_MS.toInt()
            conn.readTimeout = SmartPoolConstants.PROBE_TIMEOUT_MS.toInt()
            conn.getInputStream().use { it.read(ByteArray(64)) }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }
}
