package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class SmartNodeState(
    var profile: ProfileItem,
    var localPort: Int,
    var latencyMs: Long = -1,
    var penalty: Int = 0,
    var failCount: Int = 0,
    var cooldownUntil: Long = 0,
    var isBanned: Boolean = false,
    val hash: String = SmartNodeHasher.computeNodeHash(profile),
    var firstSeenTime: Long = System.currentTimeMillis(),
    var lastSuccessTime: Long = System.currentTimeMillis()
) {
    fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        return !isBanned && now >= cooldownUntil
    }

    fun isDead(now: Long = System.currentTimeMillis()): Boolean {
        return (now - lastSuccessTime) > SmartPoolConstants.NODE_DEADLINE_MS || failCount >= SmartPoolConstants.MAX_CONSECUTIVE_FAILS
    }

    fun effectiveRTT(baselineMs: Long = 0L): Long {
        if (latencyMs <= 0) return Long.MAX_VALUE
        val relative = (latencyMs - baselineMs).coerceAtLeast(0L)
        return relative + (penalty * 15L)
    }

    fun healthScore(): Double {
        val latScore = if (latencyMs > 0) 1000.0 / (latencyMs + 5.0) else 0.0
        return latScore - (failCount * 12.0) - (penalty * 3.0)
    }
}

class SmartPoolBalancer(
    initialNodes: List<ProfileItem>,
    private val maxPortLimit: Int = SmartPoolConstants.DEFAULT_PORT_LIMIT
) {
    private val lock = Any()
    private val nodes = mutableListOf<SmartNodeState>()
    private var activeLeader: SmartNodeState? = null
    private var standbys = mutableListOf<SmartNodeState>()
    private val rrIndex = AtomicLong(0)
    val activeConnections = AtomicInteger(0)
    var baselineLatencyMs = 0L
    var onLeaderChanged: ((SmartNodeState) -> Unit)? = null
        set(value) {
            field = value
            activeLeader?.let { value?.invoke(it) }
        }

    init {
        val history = SmartPoolStorage.loadHistory()
        val unique = SmartPoolNodeFilter.filterAndDeduplicate(initialNodes).take(maxPortLimit)
        unique.forEachIndexed { i, p ->
            val h = SmartNodeHasher.computeNodeHash(p)
            val hist = history[h]
            val state = SmartNodeState(
                profile = p,
                localPort = SmartPoolConstants.BASE_POOL_PORT + i,
                hash = h,
                firstSeenTime = hist?.firstSeenTime ?: System.currentTimeMillis(),
                lastSuccessTime = hist?.lastSuccessTime ?: System.currentTimeMillis(),
                latencyMs = hist?.lastLatencyMs ?: -1L,
                penalty = hist?.penalty ?: 0,
                failCount = hist?.failCount ?: 0
            )
            nodes.add(state)
        }
        if (nodes.isNotEmpty()) {
            activeLeader = nodes[0]
            standbys = nodes.drop(1).take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        }
    }

    fun getCurrentLeader(): SmartNodeState? = synchronized(lock) { activeLeader }

    fun getActiveLeader(): SmartNodeState? {
        var changedLeader: SmartNodeState? = null
        val leader = synchronized(lock) {
            if (activeLeader != null && activeLeader!!.isAvailable()) return@synchronized activeLeader
            val standby = standbys.firstOrNull { it.isAvailable() }
            if (standby != null) {
                activeLeader = standby
                standbys.remove(standby)
                changedLeader = standby
                return@synchronized standby
            }
            val fallback = nodes.firstOrNull { it.isAvailable() }
            if (fallback != null && fallback != activeLeader) {
                activeLeader = fallback
                changedLeader = fallback
            }
            fallback
        }
        changedLeader?.let { onLeaderChanged?.invoke(it) }
        return leader
    }

    fun penalize(node: SmartNodeState) {
        synchronized(lock) {
            node.penalty += 2
            node.failCount++
            SmartPoolStorage.recordFailure(node.hash)
            if (node.failCount >= 3) {
                node.cooldownUntil = System.currentTimeMillis() + (SmartPoolConstants.COOLDOWN_MINUTES * 60 * 1000L)
            }
        }
        if (activeLeader?.localPort == node.localPort) rotateLeader()
    }

    fun rotateLeader() {
        var newLeader: SmartNodeState? = null
        synchronized(lock) {
            val candidate = standbys.firstOrNull { it.isAvailable() }
            if (candidate != null) {
                val old = activeLeader
                activeLeader = candidate
                standbys.remove(candidate)
                if (old != null && !old.isBanned) standbys.add(old)
                newLeader = candidate
            } else {
                val fallback = nodes.firstOrNull { it.isAvailable() && it.localPort != activeLeader?.localPort }
                if (fallback != null) {
                    val old = activeLeader
                    activeLeader = fallback
                    if (old != null && !old.isBanned) standbys.add(old)
                    newLeader = fallback
                }
            }
        }
        newLeader?.let { onLeaderChanged?.invoke(it) }
    }

    fun updateStandbys(verified: List<SmartNodeState>, toleranceMs: Double = 0.0) {
        var newLeader: SmartNodeState? = null
        synchronized(lock) {
            if (verified.isEmpty()) return
            val sorted = verified.sortedBy { it.effectiveRTT(baselineLatencyMs) }
            val best = sorted[0]
            val current = activeLeader
            val shouldSwitch = if (current != null && current.isAvailable() && current.profile.remarks != best.profile.remarks) {
                best.effectiveRTT(baselineLatencyMs) < (current.effectiveRTT(baselineLatencyMs) - toleranceMs.toLong())
            } else {
                best.profile.remarks != current?.profile?.remarks
            }
            if (shouldSwitch) {
                newLeader = best
                activeLeader = best
            }
            standbys = sorted.filter { it.localPort != activeLeader?.localPort }.take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        }
        newLeader?.let { onLeaderChanged?.invoke(it) }
    }

    fun differentialUpdate(newCandidates: List<ProfileItem>): Boolean = synchronized(lock) {
        val unique = SmartPoolNodeFilter.filterAndDeduplicate(newCandidates)
        val allowedUnique = unique.take(maxPortLimit)
        val newHashes = allowedUnique.map { SmartNodeHasher.computeNodeHash(it) }
        val currentHashes = nodes.map { it.hash }
        if (newHashes == currentHashes) {
            allowedUnique.forEachIndexed { i, p -> if (i < nodes.size) nodes[i].profile = p }
            return false
        }

        val existingByHash = nodes.associateBy { it.hash }.toMutableMap()
        val usedPorts = nodes.map { it.localPort }.toMutableSet()
        val allValidPorts = (SmartPoolConstants.BASE_POOL_PORT until (SmartPoolConstants.BASE_POOL_PORT + maxPortLimit)).toSet()
        val freePorts = (allValidPorts - usedPorts).toMutableList()

        val updatedNodes = mutableListOf<SmartNodeState>()
        for (profile in allowedUnique) {
            val h = SmartNodeHasher.computeNodeHash(profile)
            val existing = existingByHash[h]
            if (existing != null) {
                existing.profile = profile
                updatedNodes.add(existing)
            } else {
                val port = if (freePorts.isNotEmpty()) freePorts.removeAt(0) else {
                    val evicted = nodes.minByOrNull { it.healthScore() }
                    evicted?.localPort ?: (SmartPoolConstants.BASE_POOL_PORT + updatedNodes.size)
                }
                updatedNodes.add(SmartNodeState(profile = profile, localPort = port, hash = h))
            }
        }
        nodes.clear()
        nodes.addAll(updatedNodes)
        val survivingLeader = nodes.firstOrNull { it.hash == activeLeader?.hash }
        activeLeader = if (survivingLeader != null && survivingLeader.isAvailable()) survivingLeader else nodes.firstOrNull { it.isAvailable() }
        standbys = nodes.filter { it.hash != activeLeader?.hash && it.isAvailable() }.sortedBy { it.effectiveRTT(baselineLatencyMs) }.take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        true
    }

    fun listAll(): List<SmartNodeState> = synchronized(lock) { nodes.toList() }
    fun getStandbyCount(): Int = synchronized(lock) { standbys.count { it.isAvailable() } }
    fun needsReplenishment(): Boolean = synchronized(lock) { standbys.count { it.isAvailable() } < (SmartPoolConstants.STANDBY_CAPACITY / 2) }
    fun getHotNodes(): List<SmartNodeState> = synchronized(lock) { (listOfNotNull(activeLeader) + standbys).filter { !it.isBanned } }
    fun getColdCandidates(): List<SmartNodeState> = synchronized(lock) {
        val hotPorts = (listOfNotNull(activeLeader) + standbys).map { it.localPort }.toSet()
        nodes.filter { it.localPort !in hotPorts && it.isAvailable() }
    }
    fun fillStandbys(recruited: List<SmartNodeState>) = synchronized(lock) {
        standbys.removeAll { !it.isAvailable() }
        val existingPorts = (listOfNotNull(activeLeader) + standbys).map { it.localPort }.toSet()
        for (cand in recruited) {
            if (standbys.size >= SmartPoolConstants.STANDBY_CAPACITY) break
            if (cand.localPort !in existingPorts && cand.isAvailable()) standbys.add(cand)
        }
    }
}
