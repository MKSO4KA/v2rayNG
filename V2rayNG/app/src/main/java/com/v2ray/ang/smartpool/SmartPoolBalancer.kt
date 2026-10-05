package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class SmartNodeState(
    var profile: ProfileItem,
    var localPort: Int,
    var latencyMs: Long = -1,
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
        // Смерть наступает строго при 24 отказах подряд.
        return failCount >= SmartPoolConstants.MAX_CONSECUTIVE_FAILS
    }

    fun effectiveRTT(baselineMs: Long = 0L): Long {
        if (latencyMs <= 0) return Long.MAX_VALUE
        return latencyMs
    }

    fun healthScore(): Double {
        val latScore = if (latencyMs > 0) 1000.0 / (latencyMs + 5.0) else 0.0
        return latScore - (failCount * 12.0)
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
    var onDeficitDetected: ((Boolean) -> Unit)? = null

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
                failCount = hist?.failCount ?: 0
            )
            nodes.add(state)
        }
        if (nodes.isNotEmpty()) {
            activeLeader = nodes[0]
            // Standby комплектуется исключительно проверенными нодами (с latency > 0). Непроверенные не добавляются.
            standbys = nodes.drop(1).filter { it.latencyMs > 0 && it.isAvailable() }.take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        }
    }

    fun getCurrentLeader(): SmartNodeState? = synchronized(lock) { activeLeader }

    fun setEarlyLeader(node: SmartNodeState) = synchronized(lock) {
        node.failCount = 0
        val old = activeLeader
        activeLeader = node
        standbys.remove(node)
        if (old != null && old.localPort != node.localPort && old.isAvailable() && old.latencyMs > 0) {
            if (standbys.none { it.localPort == old.localPort }) {
                standbys.add(old)
            }
        }
        onLeaderChanged?.invoke(node)
    }

    fun addEarlyStandby(node: SmartNodeState) = synchronized(lock) {
        if (node.localPort == activeLeader?.localPort) return@synchronized
        if (standbys.any { it.localPort == node.localPort }) return@synchronized
        if (standbys.size < SmartPoolConstants.STANDBY_CAPACITY) {
            node.failCount = 0
            standbys.add(node)
        }
    }

    fun getActiveLeader(): SmartNodeState? {
        var changedLeader: SmartNodeState? = null
        val leader = synchronized(lock) {
            if (activeLeader != null && activeLeader!!.isAvailable()) return@synchronized activeLeader
            val standby = standbys.firstOrNull { it.isAvailable() }
            if (standby != null) {
                standby.failCount = 0
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
        val (isCritical, rotateNeeded) = synchronized(lock) {
            node.failCount++
            if (node.failCount >= 3) {
                node.cooldownUntil = System.currentTimeMillis() + (SmartPoolConstants.COOLDOWN_MINUTES * 60 * 1000L)
            }
            val crit = standbys.count { it.isAvailable() } < (SmartPoolConstants.STANDBY_CAPACITY / 2)
            val needRotate = (activeLeader?.localPort == node.localPort)
            Pair(crit, needRotate)
        }
        SmartPoolStorage.recordFailure(node.hash)
        if (rotateNeeded) rotateLeader()
        onDeficitDetected?.invoke(isCritical)
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

    fun rebalanceTopTier(candidates: List<SmartNodeState>, toleranceMs: Double = 0.0) = synchronized(lock) {
        val validAlive = candidates.filter { it.isAvailable() && it.latencyMs > 0 }
            .distinctBy { it.localPort }
            .sortedBy { it.latencyMs }
        if (validAlive.isEmpty()) return@synchronized

        val best = validAlive[0]
        val current = activeLeader
        var newLeader: SmartNodeState? = null

        if (current == null || !current.isAvailable() || current.latencyMs <= 0) {
            activeLeader = best
            newLeader = best
        } else if (best.localPort != current.localPort && best.latencyMs < (current.latencyMs - toleranceMs.toLong())) {
            activeLeader = best
            newLeader = best
        }

        standbys = validAlive.filter { it.localPort != activeLeader?.localPort }
            .take(SmartPoolConstants.STANDBY_CAPACITY)
            .toMutableList()

        newLeader?.let { onLeaderChanged?.invoke(it) }
    }

    fun updateStandbys(verified: List<SmartNodeState>, toleranceMs: Double = 0.0) {
        rebalanceTopTier(verified, toleranceMs)
    }


    fun differentialUpdate(newCandidates: List<ProfileItem>): Boolean = synchronized(lock) {
        val unique = SmartPoolNodeFilter.filterAndDeduplicate(newCandidates)
        val allowedUnique = unique.take(maxPortLimit)

        // Fast path: if hashes unchanged, only update profiles (names) and keep leader/latency
        val newHashes = allowedUnique.map { SmartNodeHasher.computeNodeHash(it) }
        val currentHashes = nodes.map { it.hash }
        if (newHashes == currentHashes) {
            allowedUnique.forEachIndexed { i, p ->
                if (i < nodes.size) {
                    nodes[i].profile = p
                }
            }
            return@synchronized false
        }

        val existingByHash = nodes.associateBy { it.hash }.toMutableMap()
        val usedPorts = nodes.map { it.localPort }.toMutableSet()
        val allValidPorts = (SmartPoolConstants.BASE_POOL_PORT until (SmartPoolConstants.BASE_POOL_PORT + maxPortLimit)).toSet()
        val freePorts = (allValidPorts - usedPorts).toMutableList()

        val updatedNodes = mutableListOf<SmartNodeState>()
        var matchedCount = 0
        var addedCount = 0

        for (profile in allowedUnique) {
            val h = SmartNodeHasher.computeNodeHash(profile)
            val existing = existingByHash.remove(h)
            if (existing != null) {
                existing.profile = profile
                existing.failCount = 0
                existing.cooldownUntil = 0
                updatedNodes.add(existing)
                matchedCount++
            } else {
                val port = if (freePorts.isNotEmpty()) freePorts.removeAt(0) else {
                    val evicted = nodes.minByOrNull { it.healthScore() }
                    evicted?.localPort ?: (SmartPoolConstants.BASE_POOL_PORT + updatedNodes.size)
                }
                updatedNodes.add(SmartNodeState(profile = profile, localPort = port, hash = h))
                addedCount++

                // Подробный лог нового узла с безопасным маскированием токенов
                val maskedAuth = (profile.password ?: profile.username)?.let {
                    if (it.length > 8) "${it.take(4)}...${it.takeLast(4)}" else "***"
                } ?: "none"
                val transportInfo = buildString {
                    append(profile.network ?: "tcp")
                    if (!profile.security.isNullOrBlank()) append("/${profile.security}")
                    if (!profile.sni.isNullOrBlank()) append(", sni=${profile.sni}")
                    if (!profile.path.isNullOrBlank()) append(", path=${profile.path}")
                    if (!profile.flow.isNullOrBlank()) append(", flow=${profile.flow}")
                }
                LogUtil.i(
                    SmartPoolConstants.TAG,
                    "➕ [Новый узел] '${profile.remarks}' (${profile.server}:${profile.serverPort}, ${profile.configType.name.lowercase()}, port=$port, auth=$maskedAuth, $transportInfo)"
                )
            }
        }

        // Стакание: ноды, отсутствующие в текущем ответе подписки, сохраняются в пуле, если у них меньше 24 ошибок подряд
        var retainedCount = 0
        var prunedDeadCount = 0
        val now = System.currentTimeMillis()
        for ((_, remainingNode) in existingByHash) {
            if (!remainingNode.isDead(now) && updatedNodes.size < maxPortLimit) {
                updatedNodes.add(remainingNode)
                retainedCount++
            } else {
                prunedDeadCount++
            }
        }

        nodes.clear()
        nodes.addAll(updatedNodes)
        val survivingLeader = nodes.firstOrNull { it.hash == activeLeader?.hash }
        activeLeader = when {
            survivingLeader != null && survivingLeader.isAvailable() -> survivingLeader
            standbys.isNotEmpty() && standbys.first().isAvailable() -> {
                val candidate = standbys.removeAt(0)
                candidate.failCount = 0
                candidate
            }
            else -> nodes.firstOrNull { it.latencyMs > 0 && it.isAvailable() } ?: nodes.firstOrNull { it.isAvailable() }
        }
        standbys = nodes.filter { it.hash != activeLeader?.hash && it.latencyMs > 0 && it.isAvailable() }
            .sortedBy { it.effectiveRTT(baselineLatencyMs) }
            .take(SmartPoolConstants.STANDBY_CAPACITY)
            .toMutableList()

        val leaderName = activeLeader?.profile?.remarks ?: "нет"
        LogUtil.i(
            SmartPoolConstants.TAG,
            "🔍 [Сверка нод] В пуле: ${nodes.size} нод (совпало: $matchedCount, новых: $addedCount, удержано живых: $retainedCount, удалено мертвых: $prunedDeadCount). Лидер: '$leaderName'"
        )
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
    fun getWorstStandbyRTT(): Long = synchronized(lock) {
        standbys.filter { it.isAvailable() && it.latencyMs > 0 }.maxOfOrNull { it.effectiveRTT(baselineLatencyMs) } ?: -1L
    }

    fun upgradeLeaderIfBetter(candidate: SmartNodeState, toleranceMs: Double = 0.0): Boolean {
        if (!candidate.isAvailable() || candidate.latencyMs <= 0) return false
        var changedLeader: SmartNodeState? = null
        val upgraded = synchronized(lock) {
            val current = activeLeader
            if (current == null) {
                candidate.failCount = 0
                activeLeader = candidate
                standbys.removeIf { it.localPort == candidate.localPort }
                changedLeader = candidate
                return@synchronized true
            }
            if (candidate.localPort == current.localPort) return@synchronized false
            val currentEff = current.effectiveRTT(baselineLatencyMs)
            val candidateEff = candidate.effectiveRTT(baselineLatencyMs)
            if (candidateEff < currentEff - toleranceMs.toLong()) {
                candidate.failCount = 0
                val old = current
                activeLeader = candidate
                standbys.removeIf { it.localPort == candidate.localPort }
                if (old.isAvailable() && old.latencyMs > 0 && standbys.none { it.localPort == old.localPort }) {
                    if (standbys.size < SmartPoolConstants.STANDBY_CAPACITY) {
                        standbys.add(old)
                    } else {
                        val worst = standbys.maxByOrNull { it.effectiveRTT(baselineLatencyMs) }
                        if (worst != null && old.effectiveRTT(baselineLatencyMs) < worst.effectiveRTT(baselineLatencyMs)) {
                            val idx = standbys.indexOf(worst)
                            if (idx != -1) standbys[idx] = old
                        }
                    }
                }
                changedLeader = candidate
                true
            } else {
                false
            }
        }
        changedLeader?.let { onLeaderChanged?.invoke(it) }
        return upgraded
    }

    fun replaceOrUpgradeStandby(candidate: SmartNodeState, toleranceMs: Double = 0.0): Boolean = synchronized(lock) {
        if (!candidate.isAvailable() || candidate.latencyMs <= 0) return false
        if (candidate.localPort == activeLeader?.localPort) return false
        if (standbys.any { it.localPort == candidate.localPort }) return false

        candidate.failCount = 0

        if (standbys.size < SmartPoolConstants.STANDBY_CAPACITY) {
            standbys.add(candidate)
            return true
        }

        val deadIdx = standbys.indexOfFirst { !it.isAvailable() }
        if (deadIdx != -1) {
            standbys[deadIdx] = candidate
            return true
        }

        val candEff = candidate.effectiveRTT(baselineLatencyMs)
        val worst = standbys.maxByOrNull { it.effectiveRTT(baselineLatencyMs) } ?: return false
        if (candEff < (worst.effectiveRTT(baselineLatencyMs) - toleranceMs.toLong())) {
            val worstIdx = standbys.indexOf(worst)
            if (worstIdx != -1) {
                standbys[worstIdx] = candidate
                return true
            }
        }
        false
    }

    fun fillStandbys(recruited: List<SmartNodeState>) = synchronized(lock) {
        standbys.removeAll { !it.isAvailable() }
        val existingPorts = (listOfNotNull(activeLeader) + standbys).map { it.localPort }.toSet()
        for (cand in recruited) {
            if (standbys.size >= SmartPoolConstants.STANDBY_CAPACITY) break
            if (cand.localPort !in existingPorts && cand.isAvailable() && cand.latencyMs > 0) {
                cand.failCount = 0
                standbys.add(cand)
            }
        }
    }
}
