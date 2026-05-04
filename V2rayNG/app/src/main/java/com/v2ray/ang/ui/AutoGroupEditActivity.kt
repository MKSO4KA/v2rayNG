package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAutoGroupEditBinding
import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.AutoOutboundBuilder
import com.v2ray.ang.util.Utils

class AutoGroupEditActivity : BaseActivity() {
    private val binding by lazy { ActivityAutoGroupEditBinding.inflate(layoutInflater) }
    private val subId by lazy { intent.getStringExtra("subId").orEmpty() }
    private val ruleId by lazy { intent.getStringExtra("ruleId") }
    
    private var currentProxies = listOf<ProfileItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_auto_group_edit))

        val serverList = MmkvManager.decodeServerList(subId)
        currentProxies = serverList.mapNotNull { MmkvManager.decodeServerConfig(it) }
            .filter { it.configType != EConfigType.POLICYGROUP && it.configType != EConfigType.CUSTOM }

        if (!ruleId.isNullOrEmpty()) {
            val subItem = MmkvManager.decodeSubscription(subId)
            val rule = subItem?.autoGroupRules?.find { it.id == ruleId }
            if (rule != null) {
                binding.etRemarks.text = Utils.getEditable(rule.remarks)
                binding.etRegex.text = Utils.getEditable(rule.regex)
                binding.spType.setSelection(rule.type.toIntOrNull() ?: 0)
                binding.etInterval.text = Utils.getEditable(rule.interval)
                binding.etTolerance.text = Utils.getEditable(rule.tolerance?.toString() ?: "50.0")
            }
        }

        binding.etRegex.addTextChangedListener { updatePreview() }
        binding.etInterval.addTextChangedListener { updatePreview() }
        binding.etTolerance.addTextChangedListener { updatePreview() }
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updatePreview()
    }

    private fun updatePreview() {
        val regexStr = binding.etRegex.text.toString().trim()
        
        val type = binding.spType.selectedItemPosition
        val intTolInfo = if (type == 0 || type == 1) {
            val interval = binding.etInterval.text.toString().trim().ifEmpty { if (type == 0) "3m" else "5m" }
            val tol = binding.etTolerance.text.toString().trim().ifEmpty { "50.0" }
            " | Int: $interval" + (if (type == 0) " | Tol: $tol" else "")
        } else ""

        if (regexStr.isEmpty()) {
            binding.tvMatchedCount.text = "Matched Proxies: ${currentProxies.size}$intTolInfo (Showing All)"
            binding.tvMatchedList.text = currentProxies.joinToString("\n") { "[${it.configType.name}] ${it.remarks}" }
            return
        }

        val processedRegex = AutoOutboundBuilder.expandFlagShorthands(regexStr)
        val regex = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
        
        if (regex == null) {
            binding.tvMatchedCount.text = "Invalid Regex"
            binding.tvMatchedList.text = "Error parsing: $processedRegex"
            return
        }

        val matched = currentProxies.filter { config ->
            val searchString = "[${config.configType.name}] ${config.remarks}"
            regex.containsMatchIn(searchString) || searchString.contains(processedRegex, ignoreCase = true)
        }

        binding.tvMatchedCount.text = "Matched Proxies: ${matched.size}$intTolInfo"
        binding.tvMatchedList.text = matched.joinToString("\n") { "[${it.configType.name}] ${it.remarks}" }
    }

    private fun saveRule() {
        val remarks = binding.etRemarks.text.toString().trim()
        val regex = binding.etRegex.text.toString().trim()
        val type = binding.spType.selectedItemPosition.toString()
        val interval = binding.etInterval.text.toString().trim().ifEmpty { null }
        val tolerance = binding.etTolerance.text.toString().trim().toDoubleOrNull() ?: 50.0

        if (remarks.isEmpty()) {
            toast(R.string.server_lab_remarks)
            return
        }
        if (regex.isEmpty()) {
            toast(R.string.auto_group_regex)
            return
        }

        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        if (ruleId.isNullOrEmpty()) {
            subItem.autoGroupRules.add(
                AutoGroupRule(id = Utils.getUuid(), remarks = remarks, regex = regex, type = type, tolerance = tolerance, interval = interval)
            )
        } else {
            val rule = subItem.autoGroupRules.find { it.id == ruleId }
            if (rule != null) {
                rule.remarks = remarks
                rule.regex = regex
                rule.type = type
                rule.interval = interval
                rule.tolerance = tolerance
            }
        }

        MmkvManager.encodeSubscription(subId, subItem)
        AutoOutboundBuilder.ensurePolicyGroups(subId)
        toastSuccess(R.string.toast_success)
        finish()
    }

    private fun deleteRule() {
        if (!ruleId.isNullOrEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val subItem = MmkvManager.decodeSubscription(subId)
                    if (subItem != null) {
                        subItem.autoGroupRules.removeAll { it.id == ruleId }
                        MmkvManager.encodeSubscription(subId, subItem)
                        AutoOutboundBuilder.ensurePolicyGroups(subId)
                    }
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        if (ruleId.isNullOrEmpty()) {
            menu.findItem(R.id.del_config)?.isVisible = false
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteRule()
            true
        }
        R.id.save_config -> {
            saveRule()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

