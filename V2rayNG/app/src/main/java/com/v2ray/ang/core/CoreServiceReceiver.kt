package com.v2ray.ang.core

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CoreReceiveMessageHandler(private val onMeasureDelay: (String) -> Unit) : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        val serviceControl = CoreServiceManager.serviceControl?.get() ?: return
        when (intent?.getIntExtra("key", 0)) {
            AppConfig.MSG_REGISTER_CLIENT -> {
                val state = if (CoreServiceManager.isRunning()) AppConfig.MSG_STATE_RUNNING else AppConfig.MSG_STATE_NOT_RUNNING
                MessageHelper.sendMsg2UI(serviceControl.getService(), state, "")
            }
            AppConfig.MSG_STATE_STOP -> {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                serviceControl.stopService()
            }
            AppConfig.MSG_STATE_RESTART -> {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        serviceControl.stopService()
                        delay(500L)
                        LauncherManager.startService(serviceControl.getService())
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            AppConfig.MSG_MEASURE_DELAY -> {
                if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                onMeasureDelay(intent.getStringExtra("content").orEmpty())
            }
        }
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                NotificationManager.stopSpeedNotification()
            }
            Intent.ACTION_SCREEN_ON -> {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                NotificationManager.startSpeedNotification()
            }
        }
    }
}
