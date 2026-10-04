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
    val hash: String = SmartNodeHasher.computeNodeHash(profile)
) {
    fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        return !isBanned && now >= cooldownUntil
    }

    fun effectiveRTT(): Long {
        if (latencyMs <= 0) return Long.MAX_VALUE
        return latencyMs + (penalty * 15L)
    }
}

class SmartPoolBalancer(initialNodes: List<ProfileItem>) {
    private val lock = Any()
    private val nodes = mutableListOf<SmartNodeState>()
    private var activeLeader: SmartNodeState? = null
    private var standbys = mutableListOf<SmartNodeState>()
    private val rrIndex = AtomicLong(0)
    val activeConnections = AtomicInteger(0)
    var onLeaderChanged: ((SmartNodeState) -> Unit)? = null
        set(value) {
            field = value
            activeLeader?.let { value?.invoke(it) }
        }

    init {
        val unique = SmartPoolNodeFilter.filterAndDeduplicate(initialNodes)
        unique.forEachIndexed { i, p ->
            nodes.add(SmartNodeState(profile = p, localPort = SmartPoolConstants.BASE_POOL_PORT + i))
        }
        if (nodes.isNotEmpty()) {
            activeLeader = nodes[0]
            standbys = nodes.drop(1).take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        }
    }

    private fun notifyLeader(leader: SmartNodeState?) {
        if (leader != null) {
            LogUtil.i(SmartPoolConstants.TAG, "👑 [Лидер] Активен узел: '${leader.profile.remarks}' (порт: 127.0.0.1:${leader.localPort})")
            onLeaderChanged?.invoke(leader)
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
        changedLeader?.let { notifyLeader(it) }
        return leader
    }

    fun getRotatedNode(): SmartNodeState? = synchronized(lock) {
        val pool = (listOfNotNull(activeLeader) + standbys).filter { it.isAvailable() }
        val candidates = if (pool.isNotEmpty()) pool else nodes.filter { it.isAvailable() }
        if (candidates.isEmpty()) return null
        val idx = (rrIndex.getAndIncrement() % candidates.size).toInt()
        return candidates[idx]
    }

    fun penalize(node: SmartNodeState) {
        synchronized(lock) {
            node.penalty += 2
            node.failCount++
            LogUtil.w(SmartPoolConstants.TAG, "⚠️ [Штраф] Узел '${node.profile.remarks}' оштрафован (штраф=${node.penalty}, ошибки=${node.failCount})")
            if (node.failCount >= 3) {
                node.cooldownUntil = System.currentTimeMillis() + (SmartPoolConstants.COOLDOWN_MINUTES * 60 * 1000L)
                LogUtil.w(SmartPoolConstants.TAG, "⛔ [Кулдаун] Узел '${node.profile.remarks}' отправлен в кулдаун на ${SmartPoolConstants.COOLDOWN_MINUTES} мин!")
            }
        }
        if (activeLeader?.localPort == node.localPort) {
            rotateLeader()
        }
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
        newLeader?.let {
            notifyLeader(it)
            LogUtil.i(SmartPoolConstants.TAG, "🔄 [Ротация] Лидер переключен -> '${it.profile.remarks}' (порт: 127.0.0.1:${it.localPort})")
        }
    }

    fun updateStandbys(verified: List<SmartNodeState>, toleranceMs: Double = 0.0) {
        var newLeader: SmartNodeState? = null
        synchronized(lock) {
            if (verified.isEmpty()) return
            val sorted = verified.sortedBy { it.effectiveRTT() }
            val best = sorted[0]
            val current = activeLeader
            val shouldSwitch = if (current != null && current.isAvailable() && current.profile.remarks != best.profile.remarks) {
                best.effectiveRTT() < (current.effectiveRTT() - toleranceMs.toLong())
            } else {
                best.profile.remarks != current?.profile?.remarks
            }
            if (shouldSwitch) {
                newLeader = best
                activeLeader = best
            }
            standbys = sorted.filter { it.localPort != activeLeader?.localPort }.take(SmartPoolConstants.STANDBY_CAPACITY).toMutableList()
        }
        newLeader?.let { notifyLeader(it) }
    }

    fun differentialUpdate(newCandidates: List<ProfileItem>): Boolean {
        synchronized(lock) {
            val unique = SmartPoolNodeFilter.filterAndDeduplicate(newCandidates)
            val newHashes = unique.map { SmartNodeHasher.computeNodeHash(it) }
            val currentHashes = nodes.map { it.hash }

            if (newHashes == currentHashes) {
                unique.forEachIndexed { i, p ->
                    if (i < nodes.size) nodes[i].profile = p
                }
                LogUtil.i(SmartPoolConstants.TAG, "🔍 [Сверка нод] Все ${nodes.size} нод без изменений по хэшам. Статистика задержки и Лидер '${activeLeader?.profile?.remarks}' сохранены!")
                return false
            }

            LogUtil.i(SmartPoolConstants.TAG, "⚡ [Сверка нод] Обнаружены изменения в составе нод (было ${nodes.size}, стало ${unique.size}). Выполняем точечное слияние...")
            val existingByHash = nodes.associateBy { it.hash }.toMutableMap()
            val usedPorts = nodes.map { it.localPort }.toMutableSet()
            val updatedNodes = mutableListOf<SmartNodeState>()

            var nextPort = SmartPoolConstants.BASE_POOL_PORT
            fun allocatePort(): Int {
                while (nextPort in usedPorts) {
                    nextPort++
                }
                usedPorts.add(nextPort)
                return nextPort
            }

            for (profile in unique) {
                val h = SmartNodeHasher.computeNodeHash(profile)
                val existing = existingByHash[h]
                if (existing != null) {
                    existing.profile = profile
                    updatedNodes.add(existing)
                } else {
                    val port = allocatePort()
                    updatedNodes.add(SmartNodeState(profile = profile, localPort = port, hash = h))
                }
            }

            nodes.clear()
            nodes.addAll(updatedNodes)

            val survivingLeader = nodes.firstOrNull { it.hash == activeLeader?.hash }
            if (survivingLeader != null && survivingLeader.isAvailable()) {
                activeLeader = survivingLeader
            } else {
                activeLeader = nodes.firstOrNull { it.isAvailable() }
                notifyLeader(activeLeader)
            }

            val leaderHash = activeLeader?.hash
            standbys = nodes.filter { it.hash != leaderHash && it.isAvailable() }
                .sortedBy { it.effectiveRTT() }
                .take(SmartPoolConstants.STANDBY_CAPACITY)
                .toMutableList()

            return true
        }
    }

    fun listAll(): List<SmartNodeState> = synchronized(lock) { nodes.toList() }

    fun getStandbyCount(): Int = synchronized(lock) { standbys.count { it.isAvailable() } }

    fun needsReplenishment(): Boolean = synchronized(lock) {
        standbys.count { it.isAvailable() } < (SmartPoolConstants.STANDBY_CAPACITY / 2)
    }

    fun getHotNodes(): List<SmartNodeState> = synchronized(lock) {
        (listOfNotNull(activeLeader) + standbys).filter { !it.isBanned }
    }

    fun getColdCandidates(): List<SmartNodeState> = synchronized(lock) {
        val hotPorts = (listOfNotNull(activeLeader) + standbys).map { it.localPort }.toSet()
        nodes.filter { it.localPort !in hotPorts && it.isAvailable() }
    }

    fun fillStandbys(recruited: List<SmartNodeState>) = synchronized(lock) {
        standbys.removeAll { !it.isAvailable() }
        val existingPorts = (listOfNotNull(activeLeader) + standbys).map { it.localPort }.toSet()
        for (cand in recruited) {
            if (standbys.size >= SmartPoolConstants.STANDBY_CAPACITY) break
            if (cand.localPort !in existingPorts && cand.isAvailable()) {
                standbys.add(cand)
            }
        }
    }
}
