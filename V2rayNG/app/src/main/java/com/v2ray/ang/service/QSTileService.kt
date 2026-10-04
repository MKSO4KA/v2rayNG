package com.v2ray.ang.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.smartpool.SmartPoolConstants
import com.v2ray.ang.smartpool.SmartPoolManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    fun setState(state: Int) {
        qsTile?.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        if (state == Tile.STATE_INACTIVE) {
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.label = getString(R.string.app_name)
        } else if (state == Tile.STATE_ACTIVE) {
            qsTile?.state = Tile.STATE_ACTIVE
            val smartLeader = SmartPoolManager.currentLeaderRemarks
            qsTile?.label = if (!smartLeader.isNullOrBlank()) {
                "⚡ $smartLeader"
            } else {
                CoreServiceManager.getRunningServerName()
            }
        }
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        if (CoreServiceManager.isRunning()) {
            setState(Tile.STATE_ACTIVE)
        } else {
            setState(Tile.STATE_INACTIVE)
        }
        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onStopListening() {
        super.onStopListening()
        try {
            applicationContext.unregisterReceiver(mMsgReceive)
            mMsgReceive = null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
        }
    }

    override fun onClick() {
        super.onClick()
        when (qsTile.state) {
            Tile.STATE_INACTIVE -> {
                resolveTargetAndStart()
            }
            Tile.STATE_ACTIVE -> {
                LauncherManager.stopService(this)
            }
        }
    }

    private fun resolveTargetAndStart() {
        val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_MODE, "0")
        when (mode) {
            "1" -> {
                val targetGuid = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_TARGET_GUID)
                val chosenGuid = if (!targetGuid.isNullOrBlank() && MmkvManager.decodeServerConfig(targetGuid) != null) {
                    targetGuid
                } else {
                    findSmartPoolGuid()
                }
                if (chosenGuid != null) {
                    MmkvManager.setSelectServer(chosenGuid)
                }
            }
            "2" -> {
                val bestGuid = findBestPingGuid()
                if (bestGuid != null) {
                    MmkvManager.setSelectServer(bestGuid)
                }
            }
        }
        LauncherManager.startServiceFromToggle(this)
    }

    private fun findSmartPoolGuid(): String? {
        val smartPoolList = MmkvManager.decodeServerList(SmartPoolConstants.SMART_POOL_GROUP_ID)
        if (smartPoolList.isNotEmpty()) return smartPoolList.first()
        val allGuids = MmkvManager.decodeAllServerList()
        return allGuids.firstOrNull {
            MmkvManager.decodeServerConfig(it)?.configType == EConfigType.SMART_POOL
        }
    }

    private fun findBestPingGuid(): String? {
        val allGuids = MmkvManager.decodeAllServerList()
        return allGuids.filter {
            val config = MmkvManager.decodeServerConfig(it)
            config?.configType != EConfigType.SMART_POOL && config?.configType != EConfigType.CUSTOM
        }.minByOrNull {
            val delay = MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L
            if (delay > 0L) delay else Long.MAX_VALUE
        }
    }

    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> context?.setState(Tile.STATE_ACTIVE)
                AppConfig.MSG_STATE_NOT_RUNNING -> context?.setState(Tile.STATE_INACTIVE)
                AppConfig.MSG_STATE_START_SUCCESS -> context?.setState(Tile.STATE_ACTIVE)
                AppConfig.MSG_STATE_START_FAILURE -> context?.setState(Tile.STATE_INACTIVE)
                AppConfig.MSG_STATE_STOP_SUCCESS -> context?.setState(Tile.STATE_INACTIVE)
            }
        }
    }
}
