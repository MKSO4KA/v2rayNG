package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        val existingWorkPolicy =
            if (forceReschedule) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        MmkvManager.decodeSubscriptions().forEach { sub ->
            scheduleOne(
                context = context,
                subId = sub.guid,
                shouldRun = sub.subscription.autoUpdate,
                intervalMinutes = maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, sub.subscription.updateInterval),
                lastUpdated = sub.subscription.lastUpdated,
                existingWorkPolicy = existingWorkPolicy
            )
        }

        syncQsTile(context, existingWorkPolicy)

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
        )
    }

    /**
     * Sync the Quick Tile auto-update task.
     */
    fun syncQsTile(context: Context = AngApplication.application, existingWorkPolicy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE) {
        val autoUpdate = MmkvManager.decodeSettingsBool(AppConfig.PREF_QS_TILE_AUTO_UPDATE, false)
        val interval = MmkvManager.decodeSettingsLong(AppConfig.PREF_QS_TILE_AUTO_UPDATE_INTERVAL, 1440L)
        val lastUpdate = MmkvManager.decodeSettingsLong(AppConfig.PREF_QS_TILE_LAST_UPDATE, 0L)
        
        scheduleOne(
            context = context,
            subId = AppConfig.QS_TILE_RESERVED_ID,
            shouldRun = autoUpdate,
            intervalMinutes = maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, interval),
            lastUpdated = lastUpdate,
            existingWorkPolicy = existingWorkPolicy
        )
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        scheduleOne(
            context = context,
            subId = subId,
            shouldRun = subItem.autoUpdate,
            intervalMinutes = maxOf(AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES, subItem.updateInterval),
            lastUpdated = subItem.lastUpdated,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    private fun scheduleOne(
        context: Context,
        subId: String,
        shouldRun: Boolean,
        intervalMinutes: Long,
        lastUpdated: Long,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val rw = RemoteWorkManager.getInstance(context)
        if (!shouldRun) {
            rw.cancelUniqueWork(taskName(subId))
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for $subId")
            return
        }

        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        rw.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [$subId] interval=${intervalMinutes}min " +
                "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    private const val KEY_SUB_ID = "subId"

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        private val notificationManager = NotificationManagerCompat.from(applicationContext)
        private val notification =
            NotificationCompat.Builder(applicationContext, AppConfig.SUBSCRIPTION_UPDATE_CHANNEL)
                .setWhen(0)
                .setTicker("Update")
                .setContentTitle(applicationContext.getString(R.string.title_pref_auto_update_subscription))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update starting: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        AppConfig.SUBSCRIPTION_UPDATE_CHANNEL,
                        AppConfig.SUBSCRIPTION_UPDATE_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_MIN
                    )
                )
            }

            if (subId == AppConfig.QS_TILE_RESERVED_ID) {
                notificationManager.notify(3, notification.setContentTitle("Updating Quick Tile Filters").build())
                GistRuleProvider.syncQuickTileGists()
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_LAST_UPDATE, System.currentTimeMillis())
                notificationManager.cancel(3)
                return Result.success()
            }

            val subItem = MmkvManager.decodeSubscription(subId)
            if (subItem == null) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: no subscription found for $subId")
                return Result.success()
            }

            if (!subItem.autoUpdate) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: auto-update disabled for $subId, skip")
                return Result.success()
            }

            val sub = SubscriptionCache(subId, subItem)
            notificationManager.notify(3, notification.build())
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update: ---${sub.subscription.remarks}")
            
            AngConfigManager.updateConfigViaSub(sub)
            
            notification.setContentText("Updating ${sub.subscription.remarks}")
            notificationManager.cancel(3)
            return Result.success()
        }
    }
}

