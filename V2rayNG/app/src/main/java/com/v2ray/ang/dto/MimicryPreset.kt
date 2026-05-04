package com.v2ray.ang.dto

data class MimicryPreset(
    val name: String,
    val userAgent: String? = null,
    val model: String? = null,
    val hwid: String? = null,
    val os: String? = null,
    val osVer: String? = null,
    val appVer: String? = null,
    val encoding: String? = null,
    val locale: String? = null,
    val lang: String? = null
)

