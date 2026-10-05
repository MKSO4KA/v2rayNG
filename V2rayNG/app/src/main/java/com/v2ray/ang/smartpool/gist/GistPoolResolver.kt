package com.v2ray.ang.smartpool.gist

import com.v2ray.ang.dto.gist.GistPoolItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.util.JsonUtil

object GistPoolResolver {
    private const val PREF_GIST_POOLS = "pref_gist_pools_cache"

    fun savePools(pools: List<GistPoolItem>) {
        try {
            if (!MmkvManager.isInitialized) return
            MmkvManager.encodeSettings(PREF_GIST_POOLS, JsonUtil.toJson(pools))
        } catch (_: Throwable) {}
    }

    fun getPools(): List<GistPoolItem> {
        return try {
            if (!MmkvManager.isInitialized) return emptyList()
            val json = MmkvManager.decodeSettingsString(PREF_GIST_POOLS)
            if (json.isNullOrBlank()) return emptyList()
            JsonUtil.fromJson(json, Array<GistPoolItem>::class.java)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }


    fun findPoolForRemarks(remarks: String?): GistPoolItem? {
        if (remarks.isNullOrBlank()) return null
        val pools = getPools()
        return pools.firstOrNull { it.remarks.equals(remarks, ignoreCase = true) }
            ?: pools.firstOrNull { remarks.contains(it.remarks, ignoreCase = true) }
    }

    fun resolveTestUrls(remarks: String?): List<String> {
        val pool = findPoolForRemarks(remarks)
        val urls = pool?.testUrls?.filter { it.isNotBlank() } ?: emptyList()
        return if (urls.isNotEmpty()) urls else listOf(SmartPoolConstants.TEST_URL_FALLBACK)
    }

    fun resolveBaselineUrl(remarks: String?): String {
        val pool = findPoolForRemarks(remarks)
        return pool?.baselineUrl?.takeIf { it.isNotBlank() } ?: SmartPoolConstants.BASELINE_TEST_URL
    }
}
