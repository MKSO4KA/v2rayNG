package com.v2ray.ang.ui.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.smartpool.MimicryProfile
import com.v2ray.ang.smartpool.SmartRadarDialog
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

@Composable
fun SubMimicrySection(
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    hwid: String,
    onHwidChange: (String) -> Unit,
    os: String,
    onOsChange: (String) -> Unit,
    osVer: String,
    onOsVerChange: (String) -> Unit,
    appVer: String,
    onAppVerChange: (String) -> Unit,
    encoding: String,
    onEncodingChange: (String) -> Unit,
    locale: String,
    onLocaleChange: (String) -> Unit,
    lang: String,
    onLangChange: (String) -> Unit,
    onProfileApplied: (MimicryProfile) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showRadarDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.title_mimicry_profile),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Happ Preset button
                IconButton(
                    onClick = {
                        onProfileApplied(MimicryProfile.HAPP_DEFAULT)
                        context.toastSuccess(R.string.toast_mimicry_happ_applied)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore_24dp),
                        contentDescription = stringResource(R.string.mimicry_action_happ_preset),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Smart Radar button
                IconButton(
                    onClick = { showRadarDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan_24dp),
                        contentDescription = stringResource(R.string.mimicry_action_radar),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Copy Profile button
                IconButton(
                    onClick = {
                        val profile = MimicryProfile(
                            userAgent = userAgent.ifBlank { "v2rayNG/1.8.5" },
                            model = model,
                            hwid = hwid,
                            os = os,
                            osVer = osVer,
                            appVer = appVer,
                            encoding = encoding.ifBlank { "gzip" },
                            locale = locale,
                            lang = lang
                        )
                        val json = JsonUtil.toJsonPretty(profile) ?: JsonUtil.toJson(profile)
                        clipboardManager.setText(AnnotatedString(json))
                        context.toastSuccess(R.string.toast_mimicry_copied)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.mimicry_action_copy),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Paste Profile button
                IconButton(
                    onClick = {
                        try {
                            val rawText = Utils.getClipboard(context)
                            if (rawText.isNullOrBlank()) {
                                context.toast(R.string.toast_none_data_clipboard)
                                return@IconButton
                            }
                            val parsed = JsonUtil.fromJsonSafe(rawText, MimicryProfile::class.java)
                            if (parsed != null) {
                                onProfileApplied(parsed)
                                context.toastSuccess(R.string.toast_mimicry_pasted)
                            } else {
                                context.toast(R.string.toast_mimicry_invalid_json)
                            }
                        } catch (e: Exception) {
                            context.toast(R.string.toast_mimicry_invalid_json)
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud_download_24dp),
                        contentDescription = stringResource(R.string.mimicry_action_paste),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        FormTextField(stringResource(R.string.sub_setting_user_agent), userAgent, onUserAgentChange)
        FormTextField(stringResource(R.string.mimicry_model), model, onModelChange)
        FormTextField(stringResource(R.string.mimicry_hwid), hwid, onHwidChange)
        FormTextField(stringResource(R.string.mimicry_os), os, onOsChange)
        FormTextField(stringResource(R.string.mimicry_os_ver), osVer, onOsVerChange)
        FormTextField(stringResource(R.string.mimicry_app_ver), appVer, onAppVerChange)
        FormTextField(stringResource(R.string.mimicry_encoding), encoding, onEncodingChange)
        FormTextField(stringResource(R.string.mimicry_locale), locale, onLocaleChange)
        FormTextField(stringResource(R.string.mimicry_lang), lang, onLangChange)
    }

    if (showRadarDialog) {
        SmartRadarDialog(
            onDismiss = { showRadarDialog = false },
            onProfileCaptured = {
                onProfileApplied(it)
                context.toastSuccess(R.string.toast_mimicry_pasted)
            }
        )
    }
}
