package com.v2ray.ang.smartpool

import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class SmartPoolProber(private val balancer: SmartPoolBalancer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { probeLoop() }
    }

    fun stop() {
        running = false
    }

    private suspend fun probeLoop() {
        while (scope.isActive && running) {
            delay(20000L)
            runCatching { probeHotGroup() }
            if (balancer.needsReplenishment()) {
                runCatching { recruitFromColdPool() }
            }
        }
    }

    fun calibrateOnce() {
        probeHotGroup()
        if (balancer.needsReplenishment()) {
            recruitFromColdPoolSync()
        }
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
                alive.add(node)
            } else {
                balancer.penalize(node)
            }
        }

        if (alive.isNotEmpty()) {
            balancer.updateStandbys(alive)
            LogUtil.i(SmartPoolConstants.TAG, "⚡ [Балансировщик] Проверен горячий пул: ${alive.size} узлов | Активный Лидер: ${balancer.getCurrentLeader()?.profile?.remarks} (порт: ${balancer.getCurrentLeader()?.localPort})")
        }
    }

    suspend fun recruitFromColdPool() {
        val needed = SmartPoolConstants.STANDBY_CAPACITY - balancer.getStandbyCount()
        if (needed <= 0) return
        val coldCandidates = balancer.getColdCandidates()
        if (coldCandidates.isEmpty()) return

        val recruited = mutableListOf<SmartNodeState>()
        val parentJob = kotlinx.coroutines.Job()
        val recruitScope = CoroutineScope(Dispatchers.IO + parentJob)

        for (cand in coldCandidates) {
            if (recruited.size >= needed) break
            recruitScope.launch {
                val rtt = probeLocalSocks(cand.localPort)
                if (rtt > 0) {
                    cand.latencyMs = rtt
                    cand.penalty = 0
                    cand.failCount = 0
                    synchronized(recruited) {
                        if (recruited.size < needed) {
                            recruited.add(cand)
                            if (recruited.size >= needed) {
                                parentJob.cancel()
                            }
                        }
                    }
                }
            }
        }

        runCatching {
            withTimeoutOrNull(4000L) {
                while (recruited.size < needed && parentJob.isActive) {
                    delay(50L)
                }
            }
        }
        parentJob.cancel()

        if (recruited.isNotEmpty()) {
            balancer.fillStandbys(recruited)
            LogUtil.i(SmartPoolConstants.TAG, "⚡ [Рекрутинг] Доукомплектован горячий пул: +${recruited.size} быстрых нод (всего в резерве: ${balancer.getStandbyCount()})")
        }
    }

    private fun recruitFromColdPoolSync() {
        val needed = SmartPoolConstants.STANDBY_CAPACITY - balancer.getStandbyCount()
        if (needed <= 0) return
        val coldCandidates = balancer.getColdCandidates()
        val recruited = mutableListOf<SmartNodeState>()
        for (cand in coldCandidates) {
            if (recruited.size >= needed) break
            val rtt = probeLocalSocks(cand.localPort)
            if (rtt > 0) {
                cand.latencyMs = rtt
                recruited.add(cand)
            }
        }
        if (recruited.isNotEmpty()) {
            balancer.fillStandbys(recruited)
        }
    }

    fun probeLocalSocks(localPort: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))
            val conn = URL(SmartPoolConstants.TEST_URL_FALLBACK).openConnection(proxy)
            conn.connectTimeout = SmartPoolConstants.PROBE_TIMEOUT_MS.toInt()
            conn.readTimeout = SmartPoolConstants.PROBE_TIMEOUT_MS.toInt()
            val stream = conn.getInputStream()
            stream.read(ByteArray(64))
            stream.close()
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }
}
