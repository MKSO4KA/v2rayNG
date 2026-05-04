package com.v2ray.ang.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySubEditBinding
import com.v2ray.ang.databinding.DialogMimicryBinding
import com.v2ray.ang.dto.MimicryPreset
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class SubEditActivity : BaseActivity() {
    private val binding by lazy { ActivitySubEditBinding.inflate(layoutInflater) }

    private var del_config: MenuItem? = null
    private var save_config: MenuItem? = null

    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }

    private var mimicryUserAgent: String? = null
    private var mimicryModel: String? = null
    private var mimicryHwid: String? = null
    private var mimicryOs: String? = null
    private var mimicryOsVer: String? = null
    private var mimicryAppVer: String? = null
    private var mimicryEncoding: String? = null
    private var mimicryLocale: String? = null
    private var mimicryLang: String? = null

    private var radarJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var radarAnimator: ObjectAnimator? = null

    private var mimicryPresets = mutableListOf<MimicryPreset>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_sub_setting))

        SettingsChangeManager.makeSetupGroupTab()
        val subItem = MmkvManager.decodeSubscription(editSubId)
        if (subItem != null) {
            bindingServer(subItem)
        } else {
            clearServer()
        }

        binding.btnManageAutoGroups.setOnClickListener {
            val intent = Intent(this, AutoGroupListActivity::class.java)
            intent.putExtra("subId", editSubId)
            startActivity(intent)
        }

        binding.btnMimicry.setOnClickListener {
            showMimicryDialog()
        }
    }

    private fun showMimicryDialog() {
        val dialogBinding = DialogMimicryBinding.inflate(layoutInflater)
        
        mimicryPresets = MmkvManager.decodeMimicryPresets()

        dialogBinding.etUserAgent.text = Utils.getEditable(mimicryUserAgent ?: "")
        dialogBinding.etModel.text = Utils.getEditable(mimicryModel ?: "")
        dialogBinding.etHwid.text = Utils.getEditable(mimicryHwid ?: "")
        dialogBinding.etOs.text = Utils.getEditable(mimicryOs ?: "")
        dialogBinding.etOsVer.text = Utils.getEditable(mimicryOsVer ?: "")
        dialogBinding.etAppVer.text = Utils.getEditable(mimicryAppVer ?: "")
        dialogBinding.etEncoding.text = Utils.getEditable(mimicryEncoding ?: "")
        dialogBinding.etLocale.text = Utils.getEditable(mimicryLocale ?: "")
        dialogBinding.etLang.text = Utils.getEditable(mimicryLang ?: "")

        dialogBinding.tvPresetSelector.setOnClickListener {
            showPresetSelector(dialogBinding)
        }

        dialogBinding.btnStartRadar.setOnClickListener {
            if (radarJob?.isActive == true) {
                stopMimicryRadar(dialogBinding)
            } else {
                startMimicryRadar(dialogBinding)
            }
        }

        dialogBinding.ivRadarFaq.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.mimicry_faq_title)
                .setMessage(R.string.mimicry_faq_content)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        dialogBinding.btnSavePreset.setOnClickListener {
            promptSavePreset(dialogBinding)
        }

        dialogBinding.btnResetMimicry.setOnClickListener {
            dialogBinding.etUserAgent.text = null
            dialogBinding.etModel.text = null
            dialogBinding.etHwid.text = null
            dialogBinding.etOs.text = null
            dialogBinding.etOsVer.text = null
            dialogBinding.etAppVer.text = null
            dialogBinding.etEncoding.text = null
            dialogBinding.etLocale.text = null
            dialogBinding.etLang.text = null
            dialogBinding.tvPresetSelector.text = getString(R.string.mimicry_preset_select)
            Toast.makeText(this, "Reset to App Defaults. All custom headers cleared.", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.sub_setting_mimicry)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                mimicryUserAgent = dialogBinding.etUserAgent.text.toString().takeIf { it.isNotBlank() }
                mimicryModel = dialogBinding.etModel.text.toString().takeIf { it.isNotBlank() }
                mimicryHwid = dialogBinding.etHwid.text.toString().takeIf { it.isNotBlank() }
                mimicryOs = dialogBinding.etOs.text.toString().takeIf { it.isNotBlank() }
                mimicryOsVer = dialogBinding.etOsVer.text.toString().takeIf { it.isNotBlank() }
                mimicryAppVer = dialogBinding.etAppVer.text.toString().takeIf { it.isNotBlank() }
                mimicryEncoding = dialogBinding.etEncoding.text.toString().takeIf { it.isNotBlank() }
                mimicryLocale = dialogBinding.etLocale.text.toString().takeIf { it.isNotBlank() }
                mimicryLang = dialogBinding.etLang.text.toString().takeIf { it.isNotBlank() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { 
                stopMimicryRadar(null)
            }
            .create()

        dialog.show()
    }

    private fun showPresetSelector(dialogBinding: DialogMimicryBinding) {
        mimicryPresets = MmkvManager.decodeMimicryPresets()
        if (mimicryPresets.isEmpty()) {
            Toast.makeText(this, "No saved presets available.", Toast.LENGTH_SHORT).show()
            return
        }

        val names = mimicryPresets.map { it.name }.toTypedArray()
        val listView = ListView(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Preset (Long-Press to Delete)")
            .setView(listView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val preset = mimicryPresets[position]
            applyPresetToUI(dialogBinding, preset)
            dialogBinding.tvPresetSelector.text = preset.name
            dialog.dismiss()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val presetToDelete = mimicryPresets[position]
            AlertDialog.Builder(this@SubEditActivity)
                .setTitle("Delete Preset")
                .setMessage("Permanently remove '${presetToDelete.name}'?")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    mimicryPresets.removeAt(position)
                    MmkvManager.encodeMimicryPresets(mimicryPresets)
                    Toast.makeText(this@SubEditActivity, "Preset deleted.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        dialog.show()
    }

    private fun applyPresetToUI(dialogBinding: DialogMimicryBinding, preset: MimicryPreset) {
        dialogBinding.etUserAgent.text = Utils.getEditable(preset.userAgent ?: "")
        dialogBinding.etModel.text = Utils.getEditable(preset.model ?: "")
        dialogBinding.etHwid.text = Utils.getEditable(preset.hwid ?: "")
        dialogBinding.etOs.text = Utils.getEditable(preset.os ?: "")
        dialogBinding.etOsVer.text = Utils.getEditable(preset.osVer ?: "")
        dialogBinding.etAppVer.text = Utils.getEditable(preset.appVer ?: "")
        dialogBinding.etEncoding.text = Utils.getEditable(preset.encoding ?: "")
        dialogBinding.etLocale.text = Utils.getEditable(preset.locale ?: "")
        dialogBinding.etLang.text = Utils.getEditable(preset.lang ?: "")
        Toast.makeText(this, "Preset '${preset.name}' applied.", Toast.LENGTH_SHORT).show()
    }

    private fun promptSavePreset(dialogBinding: DialogMimicryBinding) {
        val input = EditText(this)
        input.hint = "Preset Name"
        AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    val newPreset = MimicryPreset(
                        name = name,
                        userAgent = dialogBinding.etUserAgent.text.toString().takeIf { it.isNotBlank() },
                        model = dialogBinding.etModel.text.toString().takeIf { it.isNotBlank() },
                        hwid = dialogBinding.etHwid.text.toString().takeIf { it.isNotBlank() },
                        os = dialogBinding.etOs.text.toString().takeIf { it.isNotBlank() },
                        osVer = dialogBinding.etOsVer.text.toString().takeIf { it.isNotBlank() },
                        appVer = dialogBinding.etAppVer.text.toString().takeIf { it.isNotBlank() },
                        encoding = dialogBinding.etEncoding.text.toString().takeIf { it.isNotBlank() },
                        locale = dialogBinding.etLocale.text.toString().takeIf { it.isNotBlank() },
                        lang = dialogBinding.etLang.text.toString().takeIf { it.isNotBlank() }
                    )
                    mimicryPresets.add(newPreset)
                    MmkvManager.encodeMimicryPresets(mimicryPresets)
                    dialogBinding.tvPresetSelector.text = name
                    Toast.makeText(this, "Preset saved.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startMimicryRadar(dialogBinding: DialogMimicryBinding) {
        stopMimicryRadar(dialogBinding)
        
        radarJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                val port = serverSocket?.localPort ?: return@launch
                val url = "http://127.0.0.1:$port/"
                
                withContext(Dispatchers.Main) {
                    Utils.setClipboard(this@SubEditActivity, url)
                    dialogBinding.btnStartRadar.text = getString(R.string.mimicry_radar_cancel)
                    dialogBinding.tvRadarStatus.text = getString(R.string.mimicry_radar_status_listening, url)
                    dialogBinding.tvRadarStatus.setTextColor(ContextCompat.getColor(this@SubEditActivity, R.color.md_theme_secondary))
                    startRadarAnimation(dialogBinding.ivRadarSweep)
                }

                val captures = mutableListOf<Map<String, String>>()
                val lastCaptureTime = AtomicLong(0L)
                
                while (captures.size < 3 && isActive) {
                    serverSocket?.soTimeout = 30000 // 30s timeout per hit, resets automatically on success
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            val firstLine = reader.readLine()
                            
                            // Filter to strictly GET requests to avoid HEAD duplicates
                            if (firstLine != null && firstLine.uppercase(Locale.US).startsWith("GET")) {
                                val now = System.currentTimeMillis()
                                if (now - lastCaptureTime.get() > 1000L) { // 1000ms strict debounce
                                    lastCaptureTime.set(now)
                                    
                                    val headers = mutableMapOf<String, String>()
                                    var line = reader.readLine()
                                    while (!line.isNullOrBlank()) {
                                        val split = line.split(":", limit = 2)
                                        if (split.size == 2) {
                                            headers[split[0].trim().lowercase(Locale.US)] = split[1].trim()
                                        }
                                        line = reader.readLine()
                                    }
                                    
                                    captures.add(headers)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@SubEditActivity, "Signal intercepted: ${captures.size}/3. Timer reset.", Toast.LENGTH_SHORT).show()
                                        dialogBinding.tvRadarStatus.text = "Captured ${captures.size}/3... Waiting for next."
                                    }
                                }
                            }
                            
                            // Respond with a decoy vless link encoded in base64 to prevent external app from reporting an error
                            val fakeVless = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@127.0.0.1:443?encryption=none&security=none&type=tcp&headerType=none#MimicryDecoy"
                            val base64Vless = Utils.encode(fakeVless)
                            val bodyBytes = "$base64Vless\n".toByteArray()
                            val out = s.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                            out.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray())
                            out.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
                            out.write("Connection: close\r\n\r\n".toByteArray())
                            out.write(bodyBytes)
                            out.flush()
                        }
                    } catch (e: SocketTimeoutException) {
                        break // Timeout hit
                    }
                }

                withContext(Dispatchers.Main) {
                    stopRadarAnimation(dialogBinding.ivRadarSweep)
                    dialogBinding.btnStartRadar.text = getString(R.string.mimicry_radar_start)
                    if (captures.size == 3) {
                        val result = analyzeCaptures(captures)
                        applyRadarResultToUI(dialogBinding, result)
                        dialogBinding.tvRadarStatus.text = getString(R.string.mimicry_radar_status_done)
                        dialogBinding.tvRadarStatus.setTextColor(ContextCompat.getColor(this@SubEditActivity, R.color.md_theme_tertiary))
                        Toast.makeText(this@SubEditActivity, "Triangulation complete! Preset ready.", Toast.LENGTH_LONG).show()
                    } else if (isActive) {
                        dialogBinding.tvRadarStatus.text = getString(R.string.mimicry_radar_status_timeout)
                        dialogBinding.tvRadarStatus.setTextColor(ContextCompat.getColor(this@SubEditActivity, R.color.md_theme_error))
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    withContext(Dispatchers.Main) {
                        stopRadarAnimation(dialogBinding.ivRadarSweep)
                        dialogBinding.btnStartRadar.text = getString(R.string.mimicry_radar_start)
                        dialogBinding.tvRadarStatus.text = "Radar error: ${e.message}"
                        dialogBinding.tvRadarStatus.setTextColor(ContextCompat.getColor(this@SubEditActivity, R.color.md_theme_error))
                    }
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    private fun stopMimicryRadar(dialogBinding: DialogMimicryBinding?) {
        radarJob?.cancel()
        radarJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        radarAnimator?.cancel()
        
        dialogBinding?.btnStartRadar?.text = getString(R.string.mimicry_radar_start)
        dialogBinding?.tvRadarStatus?.text = getString(R.string.mimicry_radar_status_inactive)
        dialogBinding?.tvRadarStatus?.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant))
        dialogBinding?.ivRadarSweep?.visibility = View.INVISIBLE
    }
    
    private fun startRadarAnimation(view: View) {
        view.visibility = View.VISIBLE
        radarAnimator = ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 360f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRadarAnimation(view: View) {
        radarAnimator?.cancel()
        view.visibility = View.INVISIBLE
    }

    private fun analyzeCaptures(captures: List<Map<String, String>>): Map<String, String> {
        val keysToMap = mapOf(
            "user-agent" to "UserAgent",
            "x-device-model" to "Model",
            "model" to "Model",
            "x-hwid" to "HWID",
            "hwid" to "HWID",
            "x-device-os" to "OS",
            "os" to "OS",
            "x-ver-os" to "OSVer",
            "os-version" to "OSVer",
            "x-app-version" to "AppVer",
            "app-version" to "AppVer",
            "accept-encoding" to "Encoding",
            "x-device-locale" to "Locale",
            "locale" to "Locale",
            "accept-language" to "Lang"
        )

        val result = mutableMapOf<String, String>()

        keysToMap.forEach { (headerKey, modelKey) ->
            if (!result.containsKey(modelKey)) {
                val vals = captures.mapNotNull { it[headerKey] }
                if (vals.size == 3) {
                    val v1 = vals[0]
                    val v2 = vals[1]
                    val v3 = vals[2]
                    if (v1 == v2 && v2 == v3) {
                        result[modelKey] = v1
                    } else {
                        result[modelKey] = generateMask(v1, v2, v3)
                    }
                }
            }
        }
        return result
    }

    private fun generateMask(s1: String, s2: String, s3: String): String {
        val minLen = minOf(s1.length, s2.length, s3.length)
        if (minLen == 0) return ""
        
        val sb = StringBuilder()
        sb.append("[[MASK]]")
        
        val differingLengths = (s1.length != s2.length || s2.length != s3.length)
        
        for (i in 0 until minLen) {
            val c1 = s1[i]
            val c2 = s2[i]
            val c3 = s3[i]
            
            if (c1 == c2 && c2 == c3) {
                sb.append(c1)
            } else if (c1.isDigit() && c2.isDigit() && c3.isDigit()) {
                sb.append("<<D>>")
            } else if (c1.isLowerCase() && c2.isLowerCase() && c3.isLowerCase()) {
                sb.append("<<L>>")
            } else if (c1.isUpperCase() && c2.isUpperCase() && c3.isUpperCase()) {
                sb.append("<<U>>")
            } else if (c1.isLetterOrDigit() && c2.isLetterOrDigit() && c3.isLetterOrDigit()) {
                sb.append("<<A>>")
            } else {
                sb.append("<<A>>")
            }
        }
        
        if (differingLengths) {
            val maxLen = maxOf(s1.length, s2.length, s3.length)
            sb.append("<<RND:${maxLen - minLen}>>")
        }
        
        return sb.toString()
    }

    private fun applyRadarResultToUI(dialogBinding: DialogMimicryBinding, result: Map<String, String>) {
        result["UserAgent"]?.let { dialogBinding.etUserAgent.text = Utils.getEditable(it) }
        result["Model"]?.let { dialogBinding.etModel.text = Utils.getEditable(it) }
        result["HWID"]?.let { dialogBinding.etHwid.text = Utils.getEditable(it) }
        result["OS"]?.let { dialogBinding.etOs.text = Utils.getEditable(it) }
        result["OSVer"]?.let { dialogBinding.etOsVer.text = Utils.getEditable(it) }
        result["AppVer"]?.let { dialogBinding.etAppVer.text = Utils.getEditable(it) }
        result["Encoding"]?.let { dialogBinding.etEncoding.text = Utils.getEditable(it) }
        result["Locale"]?.let { dialogBinding.etLocale.text = Utils.getEditable(it) }
        result["Lang"]?.let { dialogBinding.etLang.text = Utils.getEditable(it) }
    }

    private fun bindingServer(subItem: SubscriptionItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(subItem.remarks)
        binding.etUrl.text = Utils.getEditable(subItem.url)
        binding.etFilter.text = Utils.getEditable(subItem.filter)
        binding.chkEnable.isChecked = subItem.enabled
        binding.autoUpdateCheck.isChecked = subItem.autoUpdate
        binding.etUpdateInterval.text = Utils.getEditable(subItem.updateInterval.toString())
        binding.allowInsecureUrl.isChecked = subItem.allowInsecureUrl
        binding.etPreProfile.text = Utils.getEditable(subItem.prevProfile)
        binding.etNextProfile.text = Utils.getEditable(subItem.nextProfile)
        binding.etAutoGroupGistUrl.text = Utils.getEditable(subItem.autoGroupGistUrl)
        binding.etBlocklistGistUrl.text = Utils.getEditable(subItem.blocklistGistUrl)

        mimicryUserAgent = subItem.userAgent
        mimicryModel = subItem.model
        mimicryHwid = subItem.hwid
        mimicryOs = subItem.os
        mimicryOsVer = subItem.osVer
        mimicryAppVer = subItem.appVer
        mimicryEncoding = subItem.encoding
        mimicryLocale = subItem.locale
        mimicryLang = subItem.lang

        return true
    }

    private fun clearServer(): Boolean {
        binding.etRemarks.text = null
        binding.etUrl.text = null
        binding.etFilter.text = null
        binding.chkEnable.isChecked = true
        binding.etUpdateInterval.text = null
        binding.etPreProfile.text = null
        binding.etNextProfile.text = null
        binding.etAutoGroupGistUrl.text = null
        binding.etBlocklistGistUrl.text = null

        mimicryUserAgent = null
        mimicryModel = null
        mimicryHwid = null
        mimicryOs = null
        mimicryOsVer = null
        mimicryAppVer = null
        mimicryEncoding = null
        mimicryLocale = null
        mimicryLang = null

        return true
    }

    private fun saveServer(): Boolean {
        val subItem = MmkvManager.decodeSubscription(editSubId) ?: SubscriptionItem()

        subItem.remarks = binding.etRemarks.text.toString()
        subItem.url = binding.etUrl.text.toString()
        subItem.filter = binding.etFilter.text.toString()
        subItem.enabled = binding.chkEnable.isChecked
        subItem.autoUpdate = binding.autoUpdateCheck.isChecked

        subItem.userAgent = mimicryUserAgent
        subItem.model = mimicryModel
        subItem.hwid = mimicryHwid
        subItem.os = mimicryOs
        subItem.osVer = mimicryOsVer
        subItem.appVer = mimicryAppVer
        subItem.encoding = mimicryEncoding
        subItem.locale = mimicryLocale
        subItem.lang = mimicryLang

        val intervalInput = binding.etUpdateInterval.text.toString().trim()
        val intervalMinutes = intervalInput.toLongOrNull()
        if (subItem.autoUpdate) {
            if (intervalMinutes == null) {
                subItem.updateInterval = SubscriptionItem().updateInterval
            } else if (intervalMinutes < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES) {
                toast(R.string.toast_invalid_update_interval)
                return false
            } else {
                subItem.updateInterval = intervalMinutes
            }
        } else {
            if (intervalMinutes != null && intervalMinutes >= AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES) {
                subItem.updateInterval = intervalMinutes
            }
        }

        subItem.prevProfile = binding.etPreProfile.text.toString()
        subItem.nextProfile = binding.etNextProfile.text.toString()
        subItem.allowInsecureUrl = binding.allowInsecureUrl.isChecked
        subItem.autoGroupGistUrl = binding.etAutoGroupGistUrl.text.toString().trim()
        subItem.blocklistGistUrl = binding.etBlocklistGistUrl.text.toString().trim()

        if (TextUtils.isEmpty(subItem.remarks)) {
            toast(R.string.sub_setting_remarks)
            return false
        }
        if (subItem.url.isNotEmpty()) {
            if (!Utils.isValidUrl(subItem.url)) {
                toast(R.string.toast_invalid_url)
                return false
            }

            if (!Utils.isValidSubUrl(subItem.url)) {
                toast(R.string.toast_insecure_url_protocol)
                if (!subItem.allowInsecureUrl) {
                    return false
                }
            }
        }

        val savedSubId = MmkvManager.encodeSubscription(editSubId, subItem)
        SubscriptionUpdater.syncOne(subId = savedSubId)

        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    private fun deleteServer(): Boolean {
        if (editSubId.isNotEmpty()) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.removeSubscriptionWithDefault(editSubId)
                            launch(Dispatchers.Main) {
                                finish()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                    }
                    .show()
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    SettingsManager.removeSubscriptionWithDefault(editSubId)
                    launch(Dispatchers.Main) {
                        finish()
                    }
                }
            }
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        del_config = menu.findItem(R.id.del_config)
        save_config = menu.findItem(R.id.save_config)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteServer()
            true
        }

        R.id.save_config -> {
            saveServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        stopMimicryRadar(null)
        super.onDestroy()
    }
}

