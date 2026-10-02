package com.v2ray.ang.handler

import android.content.Context
import android.content.res.AssetManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.io.FileOutputStream

object SettingsAssetHelper {
    fun initAssets(context: Context, assets: AssetManager) {
        // Geo databases are excluded; no asset copying needed.
    }

}
