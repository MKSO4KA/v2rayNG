package com.v2ray.ang.smartpool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionUpdateCoordinatorTest {

    @Test
    fun testCooldownSuppressesRecentUpdate() {
        val subId = "sub-recent"
        val now = System.currentTimeMillis()
        val intervalMs = 600_000L // 10 минут
        val recentUpdate = now - 60_000L // 1 минуту назад

        val canRun = SubscriptionUpdateCoordinator.canUpdate(subId, intervalMs, force = false, lastUpdatedTime = recentUpdate)
        assertFalse(canRun, "Повторное обновление должно блокироваться кулдауном")
    }

    @Test
    fun testCooldownAllowsUpdateAfterIntervalElapsed() {
        val subId = "sub-elapsed"
        val now = System.currentTimeMillis()
        val intervalMs = 600_000L // 10 минут
        val oldUpdate = now - 650_000L // прошло более 10 минут

        val canRun = SubscriptionUpdateCoordinator.canUpdate(subId, intervalMs, force = false, lastUpdatedTime = oldUpdate)
        assertTrue(canRun, "Обновление должно разрешаться после истечения интервала")
    }

    @Test
    fun testForceUpdateBypassesCooldown() {
        val subId = "sub-forced"
        val now = System.currentTimeMillis()
        val intervalMs = 600_000L
        val recentUpdate = now - 1_000L

        val canRun = SubscriptionUpdateCoordinator.canUpdate(subId, intervalMs, force = true, lastUpdatedTime = recentUpdate)
        assertTrue(canRun, "Принудительное обновление должно игнорировать cooldown")
    }

    @Test
    fun testZeroIntervalAllowsImmediateUpdate() {
        val subId = "sub-zero-interval"
        val now = System.currentTimeMillis()

        val canRun = SubscriptionUpdateCoordinator.canUpdate(subId, callerIntervalMs = 0L, force = false, lastUpdatedTime = now)
        assertTrue(canRun, "При нулевом интервале обновление не должно блокироваться")
    }

    @Test
    fun testSafetyFloorEnforcesMinimumDuration() {
        val subId = "sub-floor"
        val now = System.currentTimeMillis()
        val shortIntervalMs = 10_000L // короткий интервал 10с
        val justNow = now - 3_000L // 3с назад

        val canRun = SubscriptionUpdateCoordinator.canUpdate(subId, callerIntervalMs = shortIntervalMs, force = false, lastUpdatedTime = justNow)
        assertFalse(canRun, "Минимальный порог безопасности в 15с должен предотвращать повторный запуск")
    }

    @Test
    fun testConcurrentLockBlocksParallelExecutionForSameSub() = runBlocking {
        val subId = "sub-concurrent-same"
        val counter = AtomicInteger(0)
        val runningConcurrently = AtomicInteger(0)
        var maxParallel = 0

        val tasks = (1..5).map {
            async {
                SubscriptionUpdateCoordinator.withSubscriptionLock(subId) {
                    val current = runningConcurrently.incrementAndGet()
                    if (current > maxParallel) maxParallel = current
                    delay(50)
                    counter.incrementAndGet()
                    runningConcurrently.decrementAndGet()
                    Unit
                }
            }
        }
        tasks.awaitAll()

        assertEquals(5, counter.get(), "Все 5 задач должны успешно выполниться")
        assertEquals(1, maxParallel, "Для одного subId параллельное выполнение должно быть строго 1")
    }

    @Test
    fun testIndependentLocksAllowConcurrentExecutionForDifferentSubs() = runBlocking {
        val counter = AtomicInteger(0)
        val task1 = async {
            SubscriptionUpdateCoordinator.withSubscriptionLock("sub-A") {
                delay(50)
                counter.incrementAndGet()
                Unit
            }
        }
        val task2 = async {
            SubscriptionUpdateCoordinator.withSubscriptionLock("sub-B") {
                delay(50)
                counter.incrementAndGet()
                Unit
            }
        }
        awaitAll(task1, task2)
        assertEquals(2, counter.get(), "Обе независимые подписки должны выполниться")
    }

    @Test
    fun testMarkUpdatedRefreshesTimestamp() {
        val subId = "sub-mark-test"
        SubscriptionUpdateCoordinator.markUpdated(subId)
        val canRunImmediately = SubscriptionUpdateCoordinator.canUpdate(subId, callerIntervalMs = 60_000L, force = false, lastUpdatedTime = 0L)
        assertFalse(canRunImmediately, "После markUpdated немедленный повторный запуск должен блокироваться")
    }
}
