package com.v2ray.ang.dto.gist

import java.io.Serializable

data class GistPoolItem(
    var remarks: String = "",
    var testUrls: List<String> = emptyList(),
    var testIps: List<String> = emptyList(),
    var baselineUrl: String = "https://1.1.1.1/cdn-cgi/trace"
) : Serializable
