package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAutoGroupEditBinding
import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils

class AutoGroupEditActivity : BaseActivity() {
    private val binding by lazy { ActivityAutoGroupEditBinding.inflate(layoutInflater) }
    private val subId by lazy { intent.getStringExtra("subId").orEmpty() }
    private val ruleId by lazy { intent.getStringExtra("ruleId") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_auto_group_edit))

        if (!ruleId.isNullOrEmpty()) {
            val subItem = MmkvManager.decodeSubscription(subId)
            val rule = subItem?.autoGroupRules?.find { it.id == ruleId }
            if (rule != null) {
                binding.etRemarks.text = Utils.getEditable(rule.remarks)
                binding.etRegex.text = Utils.getEditable(rule.regex)
                binding.spType.setSelection(rule.type.toIntOrNull() ?: 0)
            }
        }
    }

    private fun saveRule() {
        val remarks = binding.etRemarks.text.toString().trim()
        val regex = binding.etRegex.text.toString().trim()
        val type = binding.spType.selectedItemPosition.toString()

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
                AutoGroupRule(
                    id = Utils.getUuid(),
                    remarks = remarks,
                    regex = regex,
                    type = type
                )
            )
        } else {
            val rule = subItem.autoGroupRules.find { it.id == ruleId }
            if (rule != null) {
                rule.remarks = remarks
                rule.regex = regex
                rule.type = type
            }
        }

        MmkvManager.encodeSubscription(subId, subItem)
        
        // Sync policies immediately so they reflect in the list and UI
        com.v2ray.ang.handler.AutoOutboundBuilder.ensurePolicyGroups(subId)
        
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
                        
                        com.v2ray.ang.handler.AutoOutboundBuilder.ensurePolicyGroups(subId)
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

