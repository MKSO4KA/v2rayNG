package com.v2ray.ang.ui.server

import android.app.Activity
import android.content.Intent

object ProfileEditorResult {

    const val EXTRA_ACTION =
        "com.v2ray.ang.extra.PROFILE_EDITOR_ACTION"

    const val EXTRA_GUID =
        "com.v2ray.ang.extra.PROFILE_EDITOR_GUID"

    const val EXTRA_RESTART_SERVICE =
        "com.v2ray.ang.extra.PROFILE_EDITOR_RESTART_SERVICE"

    const val ACTION_SAVED = "saved"
    const val ACTION_DELETED = "deleted"

    fun finishSaved(
        activity: Activity,
        guid: String,
        restartService: Boolean
    ) {
        activity.setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_ACTION, ACTION_SAVED)
                putExtra(EXTRA_GUID, guid)
                putExtra(EXTRA_RESTART_SERVICE, restartService)
            }
        )
        activity.finish()
    }

    fun finishDeleted(activity: Activity, guid: String) {
        activity.setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_ACTION, ACTION_DELETED)
                putExtra(EXTRA_GUID, guid)
                putExtra(EXTRA_RESTART_SERVICE, false)
            }
        )
        activity.finish()
    }
}