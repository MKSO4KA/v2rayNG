package com.v2ray.ang.smartpool

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

object SmartPoolStorage {
    private val lock = Any()

    fun loadHistory(): MutableMap<String, SmartNodeHistory> = synchronized(lock) {
        val json = MmkvManager.decodeSettingsString(SmartPoolConstants.PREF_NODE_HISTORY)
        if (json.isNullOrBlank()) return mutableMapOf()
        return try {
            val array = JsonUtil.fromJson(json, Array<SmartNodeHistory>::class.java)
            array?.associateBy { it.hash }?.toMutableMap() ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveHistory(historyMap: Map<String, SmartNodeHistory>) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val pruned = historyMap.values.filter { !it.isDead(now) }
        MmkvManager.encodeSettings(SmartPoolConstants.PREF_NODE_HISTORY, JsonUtil.toJson(pruned))
    }

    fun recordSuccess(hash: String, latencyMs: Long) = synchronized(lock) {
        val hist = loadHistory()
        val entry = hist.getOrPut(hash) { SmartNodeHistory(hash = hash) }
        entry.lastSuccessTime = System.currentTimeMillis()
        entry.lastLatencyMs = latencyMs
        entry.consecutiveFails = 0
        entry.penalty = 0
        saveHistory(hist)
    }

    fun recordFailure(hash: String) = synchronized(lock) {
        val hist = loadHistory()
        val entry = hist.getOrPut(hash) { SmartNodeHistory(hash = hash) }
        entry.consecutiveFails++
        entry.failCount++
        entry.penalty += 2
        saveHistory(hist)
    }
}
