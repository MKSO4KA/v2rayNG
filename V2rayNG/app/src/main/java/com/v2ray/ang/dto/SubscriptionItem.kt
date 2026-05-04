package com.v2ray.ang.dto

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440,
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var autoGroupRules: MutableList<AutoGroupRule> = mutableListOf(),
    var autoGroupGistUrl: String? = null,
    var lastGistRulesJson: String? = null,
    var blocklistGistUrl: String? = null,
    var lastBlocklistJson: String? = null
)

