package com.v2ray.ang.smartpool.crash

import android.content.Context
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.smartpool.SmartNodeHasher
import com.v2ray.ang.smartpool.SmartPoolConfigBuilder
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libv2ray.Libv2ray
import java.security.MessageDigest
import java.util.regex.Pattern

object SmartPoolDryRunValidator {
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours cooldown
    private val TAG_REGEX = Pattern.compile("tag (out-\\d+)")

    fun validateAndSanitizePool(
        context: Context?,
        rawCandidates: List<ProfileItem>,
        maxPorts: Int = SmartPoolConstants.DEFAULT_PORT_LIMIT,
        enableReportUpload: Boolean = true
    ): Pair<V2rayConfig, String> {
        var currentCandidates = rawCandidates.toMutableList()
        val faultedNodes = mutableListOf<Pair<String, ProfileItem>>()
        var lastErrorMsg = ""

        for (iteration in 0 until 10) {
            val config = SmartPoolConfigBuilder.buildMultiInboundConfig(context, currentCandidates, maxPorts)
            val configJson = JsonUtil.toJson(config) ?: "{}"
            val error = testOutboundsSyntax(configJson)

            if (error == null) {
                if (faultedNodes.isNotEmpty() && enableReportUpload && context != null) {
                    handleBatchQuarantineReport(context, faultedNodes, lastErrorMsg, recovered = true)
                }
                return Pair(config, configJson)
            }

            lastErrorMsg = error
            val badTag = extractTag(error)
            val badNode = findNodeByTag(badTag, currentCandidates)

            if (badNode != null && badTag != null) {
                faultedNodes.add(Pair(badTag, badNode))
                SmartPoolQuarantineManager.quarantineNode(badTag, badNode, error)
                val nodeDump = JsonUtil.toJsonPretty(badNode) ?: "{}"
                val nodeName = badNode.remarks.ifBlank { "unnamed" }
                SmartPoolLogRingBuffer.log("=== FAULTED NODE CONFIG DUMP: '$nodeName' ($badTag) ===\n$nodeDump")
                SmartPoolLogRingBuffer.log("=== XRAY VALIDATION ERROR ===\n$error")
                currentCandidates.remove(badNode)
            } else {
                LogUtil.w("SmartPoolValidator", "Could not isolate specific node from error: $error")
                break
            }
        }

        if (faultedNodes.isNotEmpty() && enableReportUpload && context != null) {
            handleBatchQuarantineReport(context, faultedNodes, lastErrorMsg, recovered = false)
        }
        val fallbackConfig = SmartPoolConfigBuilder.buildMultiInboundConfig(context, currentCandidates, maxPorts)
        return Pair(fallbackConfig, JsonUtil.toJsonPretty(fallbackConfig).orEmpty())
    }

    private fun testOutboundsSyntax(configJson: String): String? {
        return try {
            Libv2ray.measureOutboundDelay(configJson, "https://www.gstatic.com/generate_204")
            null
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }

    private fun extractTag(error: String): String? {
        val matcher = TAG_REGEX.matcher(error)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun findNodeByTag(tag: String?, candidates: List<ProfileItem>): ProfileItem? {
        if (tag == null) return null
        val port = tag.removePrefix("out-").toIntOrNull() ?: return null
        val index = port - SmartPoolConstants.BASE_POOL_PORT
        return candidates.getOrNull(index)
    }

    private fun handleBatchQuarantineReport(
        context: Context,
        faultedNodes: List<Pair<String, ProfileItem>>,
        errorSummary: String,
        recovered: Boolean
    ) {
        val sortedHashes = faultedNodes.map { SmartNodeHasher.computeNodeHash(it.second) }.sorted().joinToString("|")
        val md = MessageDigest.getInstance("SHA-256")
        val fingerprint = md.digest(sortedHashes.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        val cooldownKey = "pref_crash_cooldown_$fingerprint"
        val lastSent = MmkvManager.decodeSettingsLong(cooldownKey, 0L)
        val now = System.currentTimeMillis()
        if (now - lastSent < COOLDOWN_MS) {
            LogUtil.i("SmartPoolValidator", "Duplicate failure signature $fingerprint suppressed by 24h cooldown")
            return
        }

        MmkvManager.encodeSettings(cooldownKey, now)
        val payload = CrashReportPayload().apply {
            populateContext(context, RuntimeException(errorSummary))
            quarantinedNodeTag = faultedNodes.joinToString(", ") { it.first }
            quarantinedNodeRemarks = faultedNodes.joinToString(", ") { it.second.remarks }
            customLabels.add(if (recovered) "recovered" else "failed")
            val logs = SmartPoolLogRingBuffer.getAllBufferedLogs().joinToString("\n")
            encryptedAgePayload = AgeEncryption.encryptToArmor(logs.toByteArray(Charsets.UTF_8))
        }

        CoroutineScope(Dispatchers.IO).launch {
            CrashReportUploader.dispatchCrash(context, payload)
        }
    }
}
