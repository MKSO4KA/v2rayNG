package com.v2ray.ang.dto

data class AutoGroupRule(
    var id: String = "",
    var remarks: String = "",
    var regex: String = "",
    var type: String = "0", // 0: Least Ping, 1: Least Load, 2: Random, 3: Round Robin
    var tolerance: Double? = 50.0,
    var interval: String? = "3m",
    var isFromGist: Boolean = false
)

