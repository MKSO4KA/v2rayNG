package com.v2ray.ang.core

import android.app.Service
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreController

object CoreDelayTester {
    fun measureV2rayDelay(
        scope: CoroutineScope,
        coreController: CoreController,
        service: Service?,
        requestId: String,
        isRunningFn: () -> Boolean,
        isReloadingFn: () -> Boolean
    ) {
        if (service == null) return
        if (!isRunningFn() || isReloadingFn()) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            var time = -1L
            var errorStr = ""
            val isSmartPool = com.v2ray.ang.smartpool.SmartPoolManager.activeProfile != null || SettingsManager.getSocksPort() == com.v2ray.ang.smartpool.SmartPoolConstants.DISPATCHER_PORT
            if (isSmartPool) {
                time = measureViaSmartPoolDispatcher(SettingsManager.getDelayTestUrl())
                if (time == -1L) {
                    ensureActive()
                    time = measureViaSmartPoolDispatcher(SettingsManager.getDelayTestUrl(true))
                }
                if (time == -1L) errorStr = "SmartPool dispatcher unreachable"
            } else {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "Native measureDelay failed, attempting fallback to local proxy", e)
                    time = measureViaSmartPoolDispatcher(SettingsManager.getDelayTestUrl())
                    if (time == -1L) errorStr = e.message?.substringAfter("\":").orEmpty()
                }
                if (time == -1L) {
                    ensureActive()
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        LogUtil.w(AppConfig.TAG, "Native measureDelay secondary failed, fallback to local proxy", e)
                        time = measureViaSmartPoolDispatcher(SettingsManager.getDelayTestUrl(true))
                        if (time == -1L) errorStr = e.message?.substringAfter("\":").orEmpty()
                    }
                }
            }
            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunningFn()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    private fun measureViaSmartPoolDispatcher(urlStr: String): Long {
        val start = System.currentTimeMillis()
        val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", com.v2ray.ang.smartpool.SmartPoolConstants.DISPATCHER_PORT))
        val conn = java.net.URL(urlStr).openConnection(proxy) as? java.net.HttpURLConnection ?: return -1L
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        return try {
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..399) (System.currentTimeMillis() - start) else -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
