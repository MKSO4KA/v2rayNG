package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivitySubEditBinding
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubEditActivity : BaseActivity() {
    private val binding by lazy { ActivitySubEditBinding.inflate(layoutInflater) }

    private var del_config: MenuItem? = null
    private var save_config: MenuItem? = null

    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }
    private val qsTargets = mutableListOf<Pair<String, String>>()

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
        
        setupQsTileSpinner()

        binding.btnManageAutoGroups.setOnClickListener {
            val intent = Intent(this, AutoGroupListActivity::class.java)
            intent.putExtra("subId", editSubId)
            startActivity(intent)
        }
    }

    private fun setupQsTileSpinner() {
        val serverList = MmkvManager.decodeServerList(editSubId)
        qsTargets.clear()
        qsTargets.add(Pair("", "Last Selected (Default)"))
        
        serverList.forEach { guid ->
            MmkvManager.decodeServerConfig(guid)?.let { config ->
                qsTargets.add(Pair(guid, config.remarks))
            }
        }
        
        val displayList = qsTargets.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spQsTileTarget.adapter = adapter
        
        val currentTarget = SettingsManager.getQsTileTargetGuid()
        val pos = qsTargets.indexOfFirst { it.first == currentTarget }
        if (pos >= 0) {
            binding.spQsTileTarget.setSelection(pos)
        }
    }

    private fun bindingServer(subItem: SubscriptionItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(subItem.remarks)
        binding.etUrl.text = Utils.getEditable(subItem.url)
        binding.etUserAgent.text = Utils.getEditable(subItem.userAgent)
        binding.etFilter.text = Utils.getEditable(subItem.filter)
        binding.chkEnable.isChecked = subItem.enabled
        binding.autoUpdateCheck.isChecked = subItem.autoUpdate
        binding.etUpdateInterval.text = Utils.getEditable(subItem.updateInterval.toString())
        binding.allowInsecureUrl.isChecked = subItem.allowInsecureUrl
        binding.etPreProfile.text = Utils.getEditable(subItem.prevProfile)
        binding.etNextProfile.text = Utils.getEditable(subItem.nextProfile)
        binding.etAutoGroupGistUrl.text = Utils.getEditable(subItem.autoGroupGistUrl)
        binding.etBlocklistGistUrl.text = Utils.getEditable(subItem.blocklistGistUrl)
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
        return true
    }

    private fun saveServer(): Boolean {
        val subItem = MmkvManager.decodeSubscription(editSubId) ?: SubscriptionItem()

        subItem.remarks = binding.etRemarks.text.toString()
        subItem.url = binding.etUrl.text.toString()
        subItem.userAgent = binding.etUserAgent.text.toString()
        subItem.filter = binding.etFilter.text.toString()
        subItem.enabled = binding.chkEnable.isChecked
        subItem.autoUpdate = binding.autoUpdateCheck.isChecked

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
        
        val selectedPos = binding.spQsTileTarget.selectedItemPosition
        if (selectedPos >= 0 && selectedPos < qsTargets.size) {
            SettingsManager.setQsTileTargetGuid(qsTargets[selectedPos].first)
        }

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
}

