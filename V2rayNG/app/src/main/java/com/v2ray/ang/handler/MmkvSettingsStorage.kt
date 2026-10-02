package com.v2ray.ang.handler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

class MmkvSettingsStorage(private val storage: MMKV) {
    fun encode(key: String, value: String?): Boolean = storage.encode(key, value)
    fun encode(key: String, value: Int): Boolean = storage.encode(key, value)
    fun encode(key: String, value: Long): Boolean = storage.encode(key, value)
    fun encode(key: String, value: Boolean): Boolean = storage.encode(key, value)
    fun decodeString(key: String): String? = storage.decodeString(key)
    fun decodeString(key: String, defaultValue: String?): String? = storage.decodeString(key, defaultValue)
    fun decodeInt(key: String, defaultValue: Int): Int = storage.decodeInt(key, defaultValue)
    fun decodeLong(key: String, defaultValue: Long): Long = storage.decodeLong(key, defaultValue)
    fun decodeBool(key: String): Boolean = storage.decodeBool(key, false)
    fun decodeBool(key: String, defaultValue: Boolean): Boolean = storage.decodeBool(key, defaultValue)

    @Composable
    fun rememberMmkvString(key: String, default: String = ""): MutableState<String> {
        val state = remember(key) { mutableStateOf(decodeString(key, default) ?: default) }
        LaunchedEffect(key) {
            snapshotFlow { state.value }.drop(1).distinctUntilChanged().collectLatest {
                encode(key, it)
                SettingsChangeManager.notifySettingChanged(key)
            }
        }
        return state
    }

    @Composable
    fun rememberMmkvBool(key: String, default: Boolean = false): MutableState<Boolean> {
        val state = remember(key) { mutableStateOf(decodeBool(key, default)) }
        LaunchedEffect(key) {
            snapshotFlow { state.value }.drop(1).distinctUntilChanged().collectLatest {
                encode(key, it)
                SettingsChangeManager.notifySettingChanged(key)
            }
        }
        return state
    }
}
