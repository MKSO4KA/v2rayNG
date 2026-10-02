package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.TAG
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

internal class ProfileStorageException(message: String) : IllegalStateException(message)

object MmkvManager {
    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"

    val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    val settingsHandler by lazy { MmkvSettingsStorage(settingsStorage) }
    val subscriptionHandler by lazy { MmkvSubscriptionStorage(mainStorage, subStorage) }

    fun initialize(context: Context) {
        val logLevel = if (BuildConfig.DEBUG) MMKVLogLevel.LevelDebug else MMKVLogLevel.LevelInfo
        MMKV.initialize(context, context.filesDir.resolve("mmkv").absolutePath, null, logLevel, object : MMKVHandler {
            override fun onMMKVCRCCheckFail(mmapID: String) = MMKVRecoverStrategic.OnErrorRecover
            override fun onMMKVFileLengthError(mmapID: String) = MMKVRecoverStrategic.OnErrorRecover
            override fun wantLogRedirecting(): Boolean = false
            override fun mmkvLog(level: MMKVLogLevel, file: String, line: Int, function: String, message: String) = Unit
        })
    }

    inline fun <T> withProfileIndexLock(block: () -> T): T = synchronized(mainStorage) {
        mainStorage.lock()
        try { block() } finally { mainStorage.unlock() }
    }

    fun readLegacyServerList(): String? = mainStorage.decodeString(KEY_ANG_CONFIGS)
    fun getSelectServer(): String? = mainStorage.decodeString(KEY_SELECTED_SERVER)
    fun setSelectServer(guid: String) { withProfileIndexLock { mainStorage.encode(KEY_SELECTED_SERVER, guid) } }

    private fun serverListKey(subId: String): String = "$KEY_SUB_SERVER_PREFIX${subscriptionHandler.getSubscriptionId(subId)}"
    private fun persistServerList(list: List<String>, subId: String): Boolean = mainStorage.encode(serverListKey(subId), JsonUtil.toJson(list))

    fun encodeServerList(serverList: MutableList<String>, subscriptionId: String) { withProfileIndexLock { persistServerList(serverList, subscriptionId) } }
    fun decodeServerList(subscriptionId: String): MutableList<String> {
        val json = mainStorage.decodeString(serverListKey(subscriptionId))
        return if (json.isNullOrBlank()) mutableListOf() else JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
    }

    fun decodeAllServerList(): MutableList<String> {
        val allServers = mutableListOf<String>()
        val subsList = decodeSubsList()
        if (!subsList.contains(DEFAULT_SUBSCRIPTION_ID)) allServers.addAll(decodeServerList(DEFAULT_SUBSCRIPTION_ID))
        subsList.forEach { allServers.addAll(decodeServerList(it)) }
        return allServers
    }

    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) return null
        val json = profileFullStorage.decodeString(guid)
        return if (json.isNullOrBlank()) null else JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
    }

    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        withProfileIndexLock {
            if (!profileFullStorage.encode(key, JsonUtil.toJson(config))) throw ProfileStorageException("Failed payload")
            val subId = subscriptionHandler.getSubscriptionId(config.subscriptionId)
            val list = decodeServerList(subId)
            if (!list.contains(key)) {
                list.add(0, key)
                persistServerList(list, subId)
                if (getSelectServer().isNullOrBlank()) mainStorage.encode(KEY_SELECTED_SERVER, key)
            }
        }
        return key
    }

    internal fun saveServerProfiles(profiles: Map<String, ProfileItem>, rawConfigs: Map<String, String>, subscriptionId: String, append: Boolean) { withProfileIndexLock {
        if (profiles.isEmpty()) return@withProfileIndexLock
        profiles.forEach {
            profileFullStorage.encode(it.key, JsonUtil.toJson(it.value))
            rawConfigs[it.key]?.let { raw -> serverRawStorage.encode(it.key, raw) }
        }
        val list = if (append) decodeServerList(subscriptionId) else mutableListOf()
        val set = list.toHashSet()
        profiles.keys.forEach { if (set.add(it)) list.add(0, it) }
        persistServerList(list, subscriptionId)
    } }

    fun removeServer(guid: String) {
        if (guid.isBlank()) return
        val config = decodeServerConfig(guid)
        val subId = subscriptionHandler.getSubscriptionId(config?.subscriptionId)
        val list = decodeServerList(subId)
        list.remove(guid)
        encodeServerList(list, subId)
        if (getSelectServer() == guid) mainStorage.remove(KEY_SELECTED_SERVER)
        profileFullStorage.remove(guid)
        serverAffStorage.remove(guid)
    }

    fun removeServerViaSubid(subscriptionId: String?) {
        val subId = subscriptionHandler.getSubscriptionId(subscriptionId)
        val list = decodeServerList(subId)
        list.forEach {
            if (getSelectServer() == it) mainStorage.remove(KEY_SELECTED_SERVER)
            profileFullStorage.remove(it)
            serverAffStorage.remove(it)
        }
        list.clear()
        encodeServerList(list, subId)
    }

    fun removeServers(guids: List<String>, subscriptionId: String) {
        if (guids.isEmpty()) return
        val subId = subscriptionHandler.getSubscriptionId(subscriptionId)
        val list = decodeServerList(subId)
        if (list.removeAll(guids)) encodeServerList(list, subId)
        val sel = getSelectServer()
        guids.forEach {
            if (sel == it) mainStorage.remove(KEY_SELECTED_SERVER)
            profileFullStorage.remove(it)
            serverAffStorage.remove(it)
            serverRawStorage.remove(it)
        }
    }

    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) return null
        val json = serverAffStorage.decodeString(guid)
        return if (json.isNullOrBlank()) null else JsonUtil.fromJsonSafe(json, ServerAffiliationInfo::class.java)
    }

    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) return
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    fun clearAllTestDelayResults(keys: List<String>?) {
        keys?.forEach { key ->
            decodeServerAffiliationInfo(key)?.let { aff ->
                aff.testDelayMillis = 0
                serverAffStorage.encode(key, JsonUtil.toJson(aff))
            }
        }
    }

    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        profileFullStorage.clearAll()
        serverAffStorage.clearAll()
        serverRawStorage.clearAll()
        decodeSubscriptions().forEach { encodeServerList(mutableListOf(), it.guid) }
        return count
    }

    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { if (it.testDelayMillis < 0L) { removeServer(guid); count++ } }
        } else {
            serverAffStorage.allKeys()?.forEach { key -> decodeServerAffiliationInfo(key)?.let { if (it.testDelayMillis < 0L) { removeServer(key); count++ } } }
        }
        return count
    }

    fun encodeServerRaw(guid: String, config: String) { serverRawStorage.encode(guid, config) }
    fun decodeServerRaw(guid: String): String? = serverRawStorage.decodeString(guid)

    fun decodeSubscriptions(): List<SubscriptionCache> = subscriptionHandler.decodeSubscriptions()
    fun removeSubscription(subid: String) = subscriptionHandler.removeSubscription(subid)
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) = subscriptionHandler.encodeSubscription(guid, subItem)
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? = subscriptionHandler.decodeSubscription(subscriptionId)
    fun encodeSubsList(subsList: MutableList<String>) = subscriptionHandler.encodeSubsList(subsList)
    fun decodeSubsList(): MutableList<String> = subscriptionHandler.decodeSubsList()

    fun encodeSettings(key: String, value: String?): Boolean = settingsHandler.encode(key, value)
    fun encodeSettings(key: String, value: Int): Boolean = settingsHandler.encode(key, value)
    fun encodeSettings(key: String, value: Long): Boolean = settingsHandler.encode(key, value)
    fun encodeSettings(key: String, value: Boolean): Boolean = settingsHandler.encode(key, value)
    fun decodeSettingsString(key: String): String? = settingsHandler.decodeString(key)
    fun decodeSettingsString(key: String, defaultValue: String?): String? = settingsHandler.decodeString(key, defaultValue)
    fun decodeSettingsInt(key: String, defaultValue: Int): Int = settingsHandler.decodeInt(key, defaultValue)
    fun decodeSettingsLong(key: String, defaultValue: Long): Long = settingsHandler.decodeLong(key, defaultValue)
    fun decodeSettingsBool(key: String): Boolean = settingsHandler.decodeBool(key)
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean = settingsHandler.decodeBool(key, defaultValue)

    @Composable
    fun rememberMmkvString(key: String, default: String = ""): MutableState<String> = settingsHandler.rememberMmkvString(key, default)
    @Composable
    fun rememberMmkvBool(key: String, default: Boolean = false): MutableState<Boolean> = settingsHandler.rememberMmkvBool(key, default)
}
