package com.v2ray.ang.smartpool

import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Координатор обновлений подписок: устраняет дублирование параллельных запросов
 * и отслеживает интервалы тишины (cooldown) между множественными SmartPool и общим шедулером.
 */
object SubscriptionUpdateCoordinator {
    private val subLocks = ConcurrentHashMap<String, Mutex>()
    private val lastScheduledAttempts = ConcurrentHashMap<String, Long>()

    fun canUpdate(
        subId: String,
        callerIntervalMs: Long = 0L,
        force: Boolean = false,
        lastUpdatedTime: Long = 0L
    ): Boolean {
        if (force) return true
        if (callerIntervalMs <= 0L) return true
        val now = System.currentTimeMillis()
        val effectiveLast = if (lastUpdatedTime > 0L) lastUpdatedTime else (lastScheduledAttempts[subId] ?: 0L)
        if (effectiveLast <= 0L) return true

        val elapsed = now - effectiveLast
        val minThreshold = (callerIntervalMs * 0.8).toLong().coerceAtLeast(15_000L)
        if (elapsed < minThreshold) {
            val elapsedSec = elapsed / 1000
            val neededSec = callerIntervalMs / 1000
            LogUtil.i(
                SmartPoolConstants.TAG,
                "⏳ [Coordinator] Пропуск дублирования подписки '$subId': обновлено $elapsedSec сек. назад (интервал: $neededSec сек.)"
            )
            return false
        }
        return true
    }

    suspend fun <T> withSubscriptionLock(subId: String, block: suspend () -> T): T {
        val mutex = subLocks.computeIfAbsent(subId) { Mutex() }
        return mutex.withLock {
            try {
                block()
            } finally {
                lastScheduledAttempts[subId] = System.currentTimeMillis()
            }
        }
    }

    fun markUpdated(subId: String) {
        lastScheduledAttempts[subId] = System.currentTimeMillis()
    }
}
