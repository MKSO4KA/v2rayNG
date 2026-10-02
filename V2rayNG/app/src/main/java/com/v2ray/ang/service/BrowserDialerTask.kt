package com.v2ray.ang.service

import org.json.JSONArray
import org.json.JSONObject

data class BrowserDialerTask(
    val method: String,
    val url: String,
    val streamResponse: Boolean,
    val extra: Extra
) {
    data class Extra(
        val headers: Map<String, String> = emptyMap(),
        val protocols: List<String> = emptyList(),
        val referrer: String? = null
    )

    companion object {
        fun parse(payload: String): BrowserDialerTask? = runCatching {
            val root = JSONObject(payload)
            val method = root.optString("method")
            val url = root.optString("url")
            if (method.isBlank() || url.isBlank()) return null
            val streamResponse = root.optBoolean("streamResponse", false)
            val extraObject = root.optJSONObject("extra")
            val headers = extraObject.optStringMap("headers")
            val referrer = extraObject?.optString("referrer")?.takeIf { it.isNotBlank() }
            val protocols = extraObject.optProtocols()
            BrowserDialerTask(method, url, streamResponse, Extra(headers, protocols, referrer))
        }.getOrNull()

        private fun JSONObject?.optStringMap(name: String): Map<String, String> {
            val child = this?.optJSONObject(name) ?: return emptyMap()
            val map = LinkedHashMap<String, String>()
            val iter = child.keys()
            while (iter.hasNext()) {
                val key = iter.next()
                map[key] = child.optString(key)
            }
            return map
        }

        private fun JSONObject?.optProtocols(): List<String> {
            val raw = this?.opt("protocol") ?: return emptyList()
            return when (raw) {
                is String -> raw.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
                is JSONArray -> buildList {
                    for (i in 0 until raw.length()) {
                        val item = raw.optString(i)
                        if (item.isNotBlank()) add(item)
                    }
                }
                else -> emptyList()
            }
        }
    }
}
