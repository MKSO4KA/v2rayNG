package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.util.Utils

object AngConfigManager {
    fun share2Clipboard(context: Context, guid: String): Int = AngConfigShareManager.share2Clipboard(context, guid)
    fun shareNonCustomConfigsToClipboard(context: Context, serverList: List<String>): Int = AngConfigShareManager.shareNonCustomConfigsToClipboard(context, serverList)
    fun share2QRCode(guid: String): Bitmap? = AngConfigShareManager.share2QRCode(guid)
    fun shareFullContent2Clipboard(context: Context, guid: String?): Int = AngConfigShareManager.shareFullContent2Clipboard(context, guid)

    fun importBatchConfig(server: String?, subid: String, append: Boolean): Pair<Int, Int> {
        return try {
            var count = AngConfigBatchImporter.parseBatchConfig(Utils.decode(server), subid, append)
            if (count <= 0) count = AngConfigBatchImporter.parseBatchConfig(server, subid, append)
            if (count <= 0) count = AngConfigBatchImporter.parseCustomConfigServer(server, subid, append)
            var countSub = AngSubscriptionUpdater.parseBatchSubscription(server)
            if (countSub <= 0) countSub = AngSubscriptionUpdater.parseBatchSubscription(Utils.decode(server))
            if (countSub > 0) AngSubscriptionUpdater.updateConfigViaSubAll()
            count to countSub
        } catch (_: ProfileStorageException) {
            0 to 0
        }
    }

    fun updateConfigViaSubAll(): SubscriptionUpdateResult = AngSubscriptionUpdater.updateConfigViaSubAll()
    fun updateConfigViaSub(it: SubscriptionCache): SubscriptionUpdateResult = AngSubscriptionUpdater.updateConfigViaSub(it)
    fun removeInvalidServer(subId: String) = AngSubscriptionUpdater.removeInvalidServer(subId)
    fun sortByTestResultsForSub(subId: String) = AngSubscriptionUpdater.sortByTestResultsForSub(subId)

    fun generateDescription(profile: ProfileItem): String {
        val server = profile.server
        val port = profile.serverPort
        if (server.isNullOrBlank() && port.isNullOrBlank()) return ""
        val addrPart = server?.let {
            if (it.contains(":")) it.split(":").take(2).joinToString(":", postfix = ":***")
            else it.split('.').dropLast(1).joinToString(".", postfix = ".***")
        } ?: ""
        return "$addrPart : ${port ?: ""}"
    }
}

