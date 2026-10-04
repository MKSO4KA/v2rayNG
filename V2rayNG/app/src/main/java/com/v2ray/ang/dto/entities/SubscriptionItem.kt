package com.v2ray.ang.dto.entities

import com.v2ray.ang.smartpool.MimicryProfile

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    var requestHeaders: String? = null,
    var mimicryModel: String? = null,
    var mimicryHwid: String? = null,
    var mimicryOs: String? = null,
    var mimicryOsVer: String? = null,
    var mimicryAppVer: String? = null,
    var mimicryEncoding: String? = null,
    var mimicryLocale: String? = null,
    var mimicryLang: String? = null,
) {
    fun toMimicryProfile(): MimicryProfile {
        return MimicryProfile(
            userAgent = userAgent?.ifBlank { null } ?: "v2rayNG/1.8.5",
            model = mimicryModel?.ifBlank { null } ?: "Android-Device",
            hwid = mimicryHwid?.ifBlank { null } ?: "[[MASK]]<<RND:16>>",
            os = mimicryOs?.ifBlank { null } ?: "Android",
            osVer = mimicryOsVer?.ifBlank { null } ?: "14",
            appVer = mimicryAppVer?.ifBlank { null } ?: "1.8.5",
            encoding = mimicryEncoding?.ifBlank { null } ?: "gzip",
            locale = mimicryLocale?.ifBlank { null } ?: "ru_RU",
            lang = mimicryLang?.ifBlank { null } ?: "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    fun applyMimicryProfile(p: MimicryProfile) {
        userAgent = p.userAgent
        mimicryModel = p.model
        mimicryHwid = p.hwid
        mimicryOs = p.os
        mimicryOsVer = p.osVer
        mimicryAppVer = p.appVer
        mimicryEncoding = p.encoding
        mimicryLocale = p.locale
        mimicryLang = p.lang
    }
}

