package com.v2ray.ang.smartpool.crash

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.smartpool.SmartNodeHasher
import java.util.concurrent.ConcurrentHashMap

object SmartPoolQuarantineManager {
    private val quarantinedTags = ConcurrentHashMap<String, String>()
    private val quarantinedHashes = ConcurrentHashMap<String, String>()

    fun quarantineNode(tag: String?, profile: ProfileItem?, reason: String) {
        if (!tag.isNullOrBlank()) {
            quarantinedTags[tag] = reason
        }
        if (profile != null) {
            val hash = SmartNodeHasher.computeNodeHash(profile)
            quarantinedHashes[hash] = reason
        }
        val name = profile?.remarks?.takeIf { it.isNotBlank() } ?: "unnamed"
        SmartPoolLogRingBuffer.log("[Quarantine] Node '$name' ($tag) quarantined. Reason: $reason")
    }

    fun isQuarantined(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        val hash = SmartNodeHasher.computeNodeHash(profile)
        return quarantinedHashes.containsKey(hash)
    }

    fun isTagQuarantined(tag: String?): Boolean {
        if (tag.isNullOrBlank()) return false
        return quarantinedTags.containsKey(tag)
    }

    fun getQuarantineReason(tag: String?): String? {
        if (tag == null) return null
        return quarantinedTags[tag]
    }

    fun getQuarantinedCount(): Int = quarantinedHashes.size

    fun clear() {
        quarantinedTags.clear()
        quarantinedHashes.clear()
        SmartPoolLogRingBuffer.log("[Quarantine] Quarantine cleared")
    }
}
