package com.v2ray.ang.dto.gist

import java.io.Serializable

data class GistRuleItem(
    var remarks: String = "",
    var regex: String = "",
    var strategy: String = "lowest_latency",
    var interval: String = "20s",
    var tolerance: Double = 30.0,
    var validationMethod: String = "normal_ping",
    var portLimit: Int = 256,
    var subUpdateInterval: String = "60m",
    var sourceGroup: String = ""
) : Serializable
