package com.v2ray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityServerGroupBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

class ServerGroupActivity : BaseActivity() {
    private val binding by lazy { ActivityServerGroupBinding.inflate(layoutInflater) }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }
    private val subscriptionId by lazy {
        intent.getStringExtra("subscriptionId")
    }
    private val subIds = mutableListOf<String>()
    
    private var allProxies = listOf<ProfileItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = EConfigType.POLICYGROUP.toString())

        val serverList = MmkvManager.decodeAllServerList()
        allProxies = serverList.mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { it.configType != EConfigType.POLICYGROUP && it.configType != EConfigType.CUSTOM }

        val config = MmkvManager.decodeServerConfig(editGuid)
        populateSubscriptionSpinner()

        binding.etPolicyGroupFilter.addTextChangedListener { updatePreview() }
        binding.etPolicyGroupInterval.addTextChangedListener { updatePreview() }
        binding.etPolicyGroupTolerance.addTextChangedListener { updatePreview() }
        binding.spPolicyGroupSubId.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.spPolicyGroupType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (config != null) {
            bindingServer(config)
        } else {
            clearServer()
        }
    }

    private fun updatePreview() {
        val regexStr = binding.etPolicyGroupFilter.text.toString().trim()
        val selPos = binding.spPolicyGroupSubId.selectedItemPosition
        val currentSubId = if (selPos >= 0 && selPos < subIds.size) subIds[selPos] else null

        val currentProxies = allProxies.filter { currentSubId.isNullOrEmpty() || it.subscriptionId == currentSubId }

        val type = binding.spPolicyGroupType.selectedItemPosition
        val intTolInfo = if (type == 0 || type == 1) {
            val interval = binding.etPolicyGroupInterval.text.toString().trim().ifEmpty { if (type == 0) "3m" else "5m" }
            val tol = binding.etPolicyGroupTolerance.text.toString().trim().ifEmpty { "50.0" }
            " | Int: $interval" + (if (type == 0) " | Tol: $tol" else "")
        } else ""

        if (regexStr.isEmpty()) {
            binding.tvMatchedCount.text = "Matched Proxies: ${currentProxies.size}$intTolInfo (No filter)"
            binding.tvMatchedList.text = currentProxies.joinToString("\n") { it.remarks }
            return
        }

        val processedRegex = com.v2ray.ang.handler.AutoOutboundBuilder.expandFlagShorthands(regexStr)
        val regex = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
        
        if (regex == null) {
            binding.tvMatchedCount.text = "Invalid Regex"
            binding.tvMatchedList.text = ""
            return
        }

        val matched = currentProxies.filter { config ->
            val searchString = "[${config.configType.name}] ${config.remarks}"
            regex.containsMatchIn(searchString) || searchString.contains(processedRegex, ignoreCase = true)
        }

        binding.tvMatchedCount.text = "Matched Proxies: ${matched.size}$intTolInfo"
        binding.tvMatchedList.text = matched.joinToString("\n") { it.remarks }
    }

    private fun bindingServer(config: ProfileItem): Boolean {
        binding.etRemarks.text = Utils.getEditable(config.remarks)
        binding.etPolicyGroupFilter.text = Utils.getEditable(config.policyGroupFilter)
        binding.etPolicyGroupInterval.text = Utils.getEditable(config.policyGroupInterval)
        binding.etPolicyGroupTolerance.text = Utils.getEditable(config.policyGroupTolerance?.toString())

        val type = config.policyGroupType?.toInt() ?: 0
        binding.spPolicyGroupType.setSelection(type)

        val pos = subIds.indexOf(config.policyGroupSubscriptionId ?: "").let { if (it >= 0) it else 0 }
        binding.spPolicyGroupSubId.setSelection(pos)
        
        updatePreview()
        return true
    }

    private fun clearServer(): Boolean {
        binding.etRemarks.text = null
        binding.etPolicyGroupFilter.text = null
        binding.etPolicyGroupInterval.text = null
        binding.etPolicyGroupTolerance.text = null

        if (subscriptionId.isNotNullEmpty()) {
            val pos = subIds.indexOf(subscriptionId).let { if (it >= 0) it else 0 }
            binding.spPolicyGroupSubId.setSelection(pos)
        }
        
        updatePreview()
        return true
    }

    private fun saveServer(): Boolean {
        if (TextUtils.isEmpty(binding.etRemarks.text.toString())) {
            toast(R.string.server_lab_remarks)
            return false
        }

        val config = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.POLICYGROUP)
        config.remarks = binding.etRemarks.text.toString().trim()
        config.policyGroupFilter = binding.etPolicyGroupFilter.text.toString().trim()
        config.policyGroupInterval = binding.etPolicyGroupInterval.text.toString().trim().ifEmpty { null }
        config.policyGroupTolerance = binding.etPolicyGroupTolerance.text.toString().trim().toDoubleOrNull()

        config.policyGroupType = binding.spPolicyGroupType.selectedItemPosition.toString()

        val selPos = binding.spPolicyGroupSubId.selectedItemPosition
        config.policyGroupSubscriptionId = if (selPos >= 0 && selPos < subIds.size) subIds[selPos] else null

        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }

        config.description = "${binding.spPolicyGroupType.selectedItem} - ${binding.spPolicyGroupSubId.selectedItem} - ${config.policyGroupFilter}"

        MmkvManager.encodeServerConfig(editGuid, config)
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    private fun deleteServer(): Boolean {
        if (editGuid.isNotEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    MmkvManager.removeServer(editGuid)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                }
                .show()
        }
        return true
    }

    private fun populateSubscriptionSpinner() {
        val subs = MmkvManager.decodeSubscriptions()
        val displayList = mutableListOf(getString(R.string.filter_config_all))
        subIds.clear()
        subIds.add("")
        subs.forEach { sub ->
            val name = when {
                sub.subscription.remarks.isNotBlank() -> sub.subscription.remarks
                else -> sub.guid
            }
            displayList.add(name)
            subIds.add(sub.guid)
        }
        val subAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayList)
        subAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spPolicyGroupSubId.adapter = subAdapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        val delButton = menu.findItem(R.id.del_config)
        val saveButton = menu.findItem(R.id.save_config)

        if (editGuid.isNotEmpty()) {
            if (isRunning) {
                delButton?.isVisible = false
                saveButton?.isVisible = false
            }
        } else {
            delButton?.isVisible = false
        }

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

