package com.v2ray.ang.smartpool

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

object SmartPoolStorage {
    private val lock = Any()

    fun loadHistory(): MutableMap<String, SmartNodeHistory> = synchronized(lock) {
        try {
            if (!MmkvManager.isInitialized) return mutableMapOf()
            val json = MmkvManager.decodeSettingsString(SmartPoolConstants.PREF_NODE_HISTORY)
            if (json.isNullOrBlank()) return mutableMapOf()
            val array = JsonUtil.fromJson(json, Array<SmartNodeHistory>::class.java)
            array?.associateBy { it.hash }?.toMutableMap() ?: mutableMapOf()
        } catch (_: Throwable) {
            mutableMapOf()
        }
    }

    fun saveHistory(historyMap: Map<String, SmartNodeHistory>): Unit = synchronized(lock) {
        try {
            if (!MmkvManager.isInitialized) return
            val now = System.currentTimeMillis()
            val pruned = historyMap.values.filter { !it.isDead(now) }
            MmkvManager.encodeSettings(SmartPoolConstants.PREF_NODE_HISTORY, JsonUtil.toJson(pruned))
        } catch (_: Throwable) {}
    }

    fun recordSuccess(hash: String, latencyMs: Long) = synchronized(lock) {
        try {
            val hist = loadHistory()
            val entry = hist.getOrPut(hash) { SmartNodeHistory(hash = hash) }
            entry.lastSuccessTime = System.currentTimeMillis()
            entry.lastLatencyMs = latencyMs
            entry.consecutiveFails = 0
            entry.failCount = 0
            saveHistory(hist)
        } catch (_: Throwable) {}
    }

    fun recordFailure(hash: String) = synchronized(lock) {
        try {
            val hist = loadHistory()
            val entry = hist.getOrPut(hash) { SmartNodeHistory(hash = hash) }
            entry.consecutiveFails++
            entry.failCount++
            saveHistory(hist)
        } catch (_: Throwable) {}
    }

    fun resetFailures(hashes: Set<String>) {
        synchronized(lock) {
            try {
                if (!MmkvManager.isInitialized) return
                val hist = loadHistory()
                for (h in hashes) {
                    hist[h]?.let {
                        it.consecutiveFails = 0
                        it.failCount = 0
                    }
                }
                saveHistory(hist)
            } catch (_: Throwable) {}
        }
    }

}