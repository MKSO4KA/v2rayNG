package com.v2ray.ang.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import libv2ray.ProcessFinder
import java.net.InetSocketAddress

class XrayProcessFinder(context: Context) : ProcessFinder {
    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || cm == null) return -1L
        val proto = when (network) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return -1L
        }
        if (destIP.isBlank() || destPort == 0L) return -1L
        return try {
            val uid = cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(srcIP, srcPort.toInt()),
                InetSocketAddress(destIP, destPort.toInt())
            ).toLong()
            LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
            uid
        } catch (_: Exception) {
            -1L
        }
    }
}
