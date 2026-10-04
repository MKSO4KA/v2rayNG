package com.v2ray.ang.smartpool

data class SmartNodeHistory(
    val hash: String,
    val firstSeenTime: Long = System.currentTimeMillis(),
    var lastSuccessTime: Long = System.currentTimeMillis(),
    var consecutiveFails: Int = 0,
    var failCount: Int = 0,
    var penalty: Int = 0,
    var lastLatencyMs: Long = -1L
) {
    fun isDead(now: Long = System.currentTimeMillis(), deadlineMs: Long = SmartPoolConstants.NODE_DEADLINE_MS): Boolean {
        // Нода считается мертвой исключительно при 24 активных отказах подряд.
        // Простой без сети (оффлайн) не уничтожает ноды.
        return consecutiveFails >= SmartPoolConstants.MAX_CONSECUTIVE_FAILS
    }

    fun computeHealthScore(): Double {
        val latencyScore = if (lastLatencyMs > 0) 1000.0 / (lastLatencyMs + 10.0) else 0.0
        val failPenalty = (consecutiveFails * 10.0) + (penalty * 2.0)
        return (latencyScore - failPenalty).coerceAtLeast(-1000.0)
    }
}
