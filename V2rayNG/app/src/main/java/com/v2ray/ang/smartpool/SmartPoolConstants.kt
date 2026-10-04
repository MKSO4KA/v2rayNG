package com.v2ray.ang.smartpool

object SmartPoolConstants {
    const val TAG = "SmartPool"
    const val DISPATCHER_PORT = 10808
    const val BASE_POOL_PORT = 30001
    const val RADAR_PORT = 29999
    const val PROBE_TIMEOUT_MS = 5000L
    const val STANDBY_CAPACITY = 8
    const val COOLDOWN_MINUTES = 15L
    const val TAG_IN_PREFIX = "in-"
    const val TAG_OUT_PREFIX = "out-"
    const val SMART_POOL_REMARKS = "⚡ Smart Pool"
    const val PREF_MIMICRY_PROFILE = "smartpool_mimicry_profile"
    const val PREF_NODE_HISTORY = "smartpool_nodes_history_v1"
    const val TEST_URL_GOOGLE = "https://generativelanguage.googleapis.com/v1beta/openai/models"
    const val TEST_URL_FALLBACK = "https://www.gstatic.com/generate_204"
    const val BASELINE_TEST_URL = "https://1.1.1.1/cdn-cgi/trace"

    const val DEFAULT_PORT_LIMIT = 256
    const val MAX_PORT_LIMIT = 1024
    const val MIN_PORT_LIMIT = 16
    const val NODE_DEADLINE_MS = 86_400_000L
    const val MAX_CONSECUTIVE_FAILS = 24
}
