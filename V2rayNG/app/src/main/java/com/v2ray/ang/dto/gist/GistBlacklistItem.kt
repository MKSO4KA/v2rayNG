package com.v2ray.ang.dto.gist

import java.io.Serializable

data class GistBlacklistItem(
    var pattern: String = "",
    var comment: String = ""
) : Serializable
