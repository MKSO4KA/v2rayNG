package com.v2ray.ang.smartpool

import com.v2ray.ang.handler.AngSubscriptionUpdater
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Locale

class SmartPoolSubAutoUpdater(
    private val subscriptionId: String,
    private val intervalStr: String?
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    fun start() {
        if (running) return
        val intervalMs = parseIntervalToMillis(intervalStr)
        running = true
        LogUtil.i(
            SmartPoolConstants.TAG,
            "🚀 [AutoUpdater] Запущен цикл автообновления подписки '${subscriptionId.ifBlank { "Все" }}' (интервал: ${intervalMs / 1000} сек.)"
        )
        scope.launch {
            while (scope.isActive && running) {
                delay(intervalMs)
                if (!running) break
                performUpdate()
            }
        }
    }

    fun stop() {
        running = false
        LogUtil.i(SmartPoolConstants.TAG, "⏹️ [AutoUpdater] Цикл автообновления остановлен.")
    }

    fun performUpdate() {
        try {
            val allSubs = MmkvManager.decodeSubscriptions()
            val targetSubs = if (subscriptionId.isBlank()) {
                allSubs
            } else {
                allSubs.filter { it.guid == subscriptionId }
            }

            if (targetSubs.isEmpty()) {
                LogUtil.d(SmartPoolConstants.TAG, "ℹ️ [AutoUpdater] Нет активных подписок для обновления (ID: '$subscriptionId')")
                return
            }

            for (sub in targetSubs) {
                LogUtil.i(SmartPoolConstants.TAG, "🔄 [AutoUpdater] Таймер сработал: запрос обновления подписки '${sub.subscription.remarks}'...")
                val res = AngSubscriptionUpdater.updateConfigViaSub(sub)
                LogUtil.i(
                    SmartPoolConstants.TAG,
                    "🏁 [AutoUpdater] Завершено обновление '${sub.subscription.remarks}': успешно=${res.successCount}, ошибок=${res.failureCount}, узлов=${res.configCount}"
                )
            }
        } catch (e: Exception) {
            LogUtil.e(SmartPoolConstants.TAG, "❌ [AutoUpdater] Сбой в цикле автообновления", e)
        }
    }

    companion object {
        fun computeHash(content: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun parseIntervalToMillis(raw: String?, defaultMs: Long = 20000L): Long {
            if (raw.isNullOrBlank()) return defaultMs
            val clean = raw.trim().lowercase(Locale.ROOT)
            return try {
                when {
                    clean.endsWith("ms") -> clean.removeSuffix("ms").trim().toLong()
                    clean.endsWith("s") || clean.endsWith("сек") || clean.endsWith("с") -> {
                        val num = clean.replace(Regex("[^0-9.]"), "").toDouble()
                        (num * 1000).toLong()
                    }
                    clean.endsWith("m") || clean.endsWith("мин") || clean.endsWith("м") -> {
                        val num = clean.replace(Regex("[^0-9.]"), "").toDouble()
                        (num * 60 * 1000).toLong()
                    }
                    clean.endsWith("h") || clean.endsWith("ч") || clean.endsWith("час") -> {
                        val num = clean.replace(Regex("[^0-9.]"), "").toDouble()
                        (num * 3600 * 1000).toLong()
                    }
                    else -> {
                        val num = clean.toLong()
                        if (num < 1000) num * 1000 else num
                    }
                }.coerceAtLeast(5000L)
            } catch (_: Exception) {
                defaultMs
            }
        }
    }
}
