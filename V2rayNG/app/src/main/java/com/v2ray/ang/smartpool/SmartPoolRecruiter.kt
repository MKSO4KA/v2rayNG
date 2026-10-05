package com.v2ray.ang.smartpool

import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class SmartPoolRecruiter(
    private val balancer: SmartPoolBalancer,
    private val toleranceMs: Double = 30.0,
    private val probeIntervalMs: Long = 20000L,
    private val probeFunc: (localPort: Int, timeoutMs: Long) -> Long = { _, _ -> -1L }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRecruiting = AtomicBoolean(false)
    private var running = false

    val warmStash = ConcurrentLinkedQueue<SmartNodeState>()
    private val warmPortSet = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    var onWarmPoolFilled: (() -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        scope.launch {
            val interval = (probeIntervalMs / 4).coerceIn(2000L, probeIntervalMs)
            while (isActive && running) {
                delay(interval)
                runColdRecruitPass()
            }
        }
    }

    fun stop() {
        running = false
        scope.coroutineContext.cancelChildren()
        clearWarmStash()
    }

    fun clearWarmStash() {
        warmStash.clear()
        warmPortSet.clear()
    }

    fun drainAllWarmNodes(): List<SmartNodeState> {
        val result = mutableListOf<SmartNodeState>()
        while (true) {
            val item = warmStash.poll() ?: break
            result.add(item)
        }
        warmPortSet.clear()
        return result.distinctBy { it.localPort }
    }

    fun notifyDeficit(isCritical: Boolean = false) {
        scope.launch { runRecruitmentCycle(isCritical) }
    }

    fun triggerRecruitment(isCritical: Boolean = false) {
        scope.launch { runRecruitmentCycle(isCritical) }
    }

    suspend fun runRecruitmentCycle(isCritical: Boolean) {
        var currentCutoff = SmartPoolConstants.BASE_CUTOFF_MS
        while (currentCutoff <= SmartPoolConstants.MAX_CUTOFF_MS) {
            val found = runColdRecruitPassWithCutoff(currentCutoff, isCritical)
            if (found && balancer.getCurrentLeader()?.isAvailable() == true && (balancer.getCurrentLeader()?.latencyMs ?: -1L) > 0) {
                break
            }
            if (currentCutoff >= SmartPoolConstants.MAX_CUTOFF_MS) break
            currentCutoff = (currentCutoff * 2).coerceAtMost(SmartPoolConstants.MAX_CUTOFF_MS)
        }
        val drained = drainAllWarmNodes()
        if (drained.isNotEmpty()) {
            balancer.rebalanceTopTier(drained, toleranceMs)
        }
    }


    suspend fun runColdRecruitPass() {
        val worstStandby = balancer.getWorstStandbyRTT()
        val effectiveCutoff = if (worstStandby > 0) {
            (worstStandby + toleranceMs.toLong()).coerceAtMost(SmartPoolConstants.MAX_CUTOFF_MS)
        } else {
            2500L
        }
        runColdRecruitPassWithCutoff(effectiveCutoff, isCritical = false)
    }

    private suspend fun runColdRecruitPassWithCutoff(cutoffMs: Long, isCritical: Boolean): Boolean {
        if (!isRecruiting.compareAndSet(false, true)) return false
        var respondedCount = 0
        try {
            val candidates = balancer.getColdCandidates()
            if (candidates.isEmpty()) return false

            val batchSize = SmartPoolConstants.STANDBY_CAPACITY.coerceAtMost(SmartPoolConstants.MAX_CONCURRENT_SOCKETS)
            val batch = candidates.take(batchSize)

            coroutineScope {
                for (node in batch) {
                    launch {
                        val rtt = probeFunc(node.localPort, cutoffMs)
                        if (rtt > 0) {
                            val worstStandby = balancer.getWorstStandbyRTT()
                            val isStandbyDeficit = balancer.getStandbyCount() < SmartPoolConstants.STANDBY_CAPACITY
                            val maxAllowedRTT = if (isStandbyDeficit) worstStandby else (worstStandby - toleranceMs.toLong())
                            if (worstStandby > 0 && rtt > maxAllowedRTT) return@launch

                            node.latencyMs = rtt
                            node.failCount = 0
                            node.lastSuccessTime = System.currentTimeMillis()
                            SmartPoolStorage.recordSuccess(node.hash, rtt)
                            respondedCount++

                            val currentLeader = balancer.getCurrentLeader()
                            if (currentLeader == null || currentLeader.latencyMs <= 0) {
                                balancer.setEarlyLeader(node)
                                LogUtil.i(SmartPoolConstants.TAG, "⚡ [FastStart] Назначен Early Leader: '${node.profile.remarks}' (${rtt}ms)")
                            } else {
                                val shouldAdd = (worstStandby <= 0 || rtt < maxAllowedRTT)
                                if (shouldAdd && warmStash.size < SmartPoolConstants.WARM_POOL_CAPACITY) {
                                    if (warmPortSet.add(node.localPort)) {
                                        warmStash.offer(node)
                                        LogUtil.i(SmartPoolConstants.TAG, "📦 [Recruiter] Добавлена в Тёплый пул: '${node.profile.remarks}' (${rtt}ms, всего в буфере: ${warmStash.size}/${SmartPoolConstants.WARM_POOL_CAPACITY})")
                                        if (warmStash.size >= SmartPoolConstants.WARM_POOL_CAPACITY) {
                                            onWarmPoolFilled?.invoke()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            isRecruiting.set(false)
        }
        return respondedCount > 0
    }
}
