package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

class MmkvSubscriptionStorage(private val mainStorage: MMKV, private val subStorage: MMKV) {
    private val KEY_SUB_IDS = "SUB_IDS"

    fun getSubscriptionId(subscriptionId: String?): String = subscriptionId?.ifEmpty { DEFAULT_SUBSCRIPTION_ID } ?: DEFAULT_SUBSCRIPTION_ID

    private fun initSubsList() {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) return
        subStorage.allKeys()?.forEach { subsList.add(it) }
        encodeSubsList(subsList)
    }

    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()
        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    fun removeSubscription(subid: String) {
        subStorage.remove(subid)
        val subsList = decodeSubsList()
        subsList.remove(subid)
        encodeSubsList(subsList)
        MmkvManager.removeServerViaSubid(subid)
    }

    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))
        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
    }

    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
    }

    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) mutableListOf() else JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.distinct()?.toMutableList() ?: mutableListOf()
    }
}
