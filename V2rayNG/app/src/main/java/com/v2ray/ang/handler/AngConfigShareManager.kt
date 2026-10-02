package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.fmt.WireguardFmt
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils

object AngConfigShareManager {
    fun share2Clipboard(context: Context, guid: String): Int {
        return try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) -1 else { Utils.setClipboard(context, conf); 0 }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config to clipboard", e)
            -1
        }
    }

    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int {
        return try {
            val sb = StringBuilder()
            for (guid in serverList) {
                val url = shareConfig(guid)
                if (TextUtils.isEmpty(url)) continue
                sb.append(url).appendLine()
            }
            if (sb.isNotEmpty()) Utils.setClipboard(context, sb.toString())
            sb.lines().count() - 1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share non-custom configs to clipboard", e)
            -1
        }
    }

    fun share2QRCode(guid: String): Bitmap? {
        return try {
            val conf = shareConfig(guid)
            if (TextUtils.isEmpty(conf)) null else QRCodeDecoder.createQRCode(conf)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config as QR code", e)
            null
        }
    }

    fun shareFullContent2Clipboard(context: Context, guid: String?): Int {
        return try {
            if (guid == null) return -1
            val result = CoreConfigManager.getV2rayConfig(context, guid)
            if (result.status) { Utils.setClipboard(context, result.content); 0 } else -1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share full content to clipboard", e)
            -1
        }
    }

    fun shareConfig(guid: String): String {
        return try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return ""
            config.configType.protocolScheme + when (config.configType) {
                EConfigType.VMESS -> VmessFmt.toUri(config)
                EConfigType.SHADOWSOCKS -> ShadowsocksFmt.toUri(config)
                EConfigType.SOCKS -> SocksFmt.toUri(config)
                EConfigType.VLESS -> VlessFmt.toUri(config)
                EConfigType.TROJAN -> TrojanFmt.toUri(config)
                EConfigType.WIREGUARD -> WireguardFmt.toUri(config)
                EConfigType.HYSTERIA2 -> Hysteria2Fmt.toUri(config)
                else -> ""
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to share config for GUID: $guid", e)
            ""
        }
    }
}
