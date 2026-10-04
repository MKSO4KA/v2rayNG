package com.v2ray.ang.smartpool

import com.v2ray.ang.dto.entities.ProfileItem
import java.security.MessageDigest
import java.util.Locale

object SmartNodeHasher {

    fun computeNodeHash(profile: ProfileItem): String {
        val proto = profile.configType.name.lowercase(Locale.ROOT)
        val server = profile.server?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val port = profile.serverPort?.trim().orEmpty()
        val auth = (profile.password ?: profile.username).orEmpty().trim()
        val network = profile.network.orEmpty().trim()
        val headerType = profile.headerType.orEmpty().trim()
        val host = profile.host.orEmpty().trim()
        val path = profile.path.orEmpty().trim()
        val security = profile.security.orEmpty().trim()
        val sni = profile.sni.orEmpty().trim()
        val alpn = profile.alpn.orEmpty().trim()
        val pubKey = profile.publicKey.orEmpty().trim()
        val shortId = profile.shortId.orEmpty().trim()
        val flow = profile.flow.orEmpty().trim()
        val seed = profile.seed.orEmpty().trim()
        val xhttpMode = profile.xhttpMode.orEmpty().trim()
        val serviceName = profile.serviceName.orEmpty().trim()

        val rawKey = buildString {
            append(proto).append("://").append(server).append(":").append(port)
            append("?auth=").append(auth)
            append("&net=").append(network)
            append("&header=").append(headerType)
            append("&host=").append(host)
            append("&path=").append(path)
            append("&sec=").append(security)
            append("&sni=").append(sni)
            append("&alpn=").append(alpn)
            append("&pub=").append(pubKey)
            append("&sid=").append(shortId)
            append("&flow=").append(flow)
            append("&seed=").append(seed)
            append("&xhttp=").append(xhttpMode)
            append("&svc=").append(serviceName)
        }

        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(rawKey.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
