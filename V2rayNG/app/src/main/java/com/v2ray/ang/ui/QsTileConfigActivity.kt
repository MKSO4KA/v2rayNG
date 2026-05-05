package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityQsTileConfigBinding
import com.v2ray.ang.dto.AutoGroupRule
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.GistRuleProvider
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.AutoOutboundBuilder
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QsTileConfigActivity : BaseActivity() {

    private lateinit var binding: ActivityQsTileConfigBinding
    private val allProxies = mutableListOf<ProfileItem>()
    private val allPolicyGroups = mutableListOf<ProfileItem>()
    private var gistRules = listOf<AutoGroupRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQsTileConfigBinding.inflate(layoutInflater)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.qs_tile_config_title))

        try {
            val serverList = MmkvManager.decodeAllServerList()
            LogUtil.d(AppConfig.TAG, "QsTileConfig: Raw serverList size = ${serverList.size}")

            serverList.forEach { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { config ->
                    if (config.configType == EConfigType.POLICYGROUP) {
                        allPolicyGroups.add(config)
                    } else {
                        allProxies.add(config)
                    }
                }
            }
            LogUtil.d(AppConfig.TAG, "QsTileConfig: Loaded ${allProxies.size} proxies and ${allPolicyGroups.size} policy groups.")

            val modes = listOf("Last Selected (Default)", "Best Ping (Global)", "Specific Policy Group", "Global Regex Match", "Gist-Based Global Targeting")
            binding.spMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val policyNames = allPolicyGroups.map { it.remarks }.ifEmpty { listOf("No Policy Groups Found") }
            binding.spPolicyGroups.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, policyNames).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            binding.spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateUIForMode(position)
                    updatePreview()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.etRegex.addTextChangedListener { updatePreview() }
            binding.etInterval.addTextChangedListener { updatePreview() }
            binding.etTolerance.addTextChangedListener { updatePreview() }

            binding.btnFetchGist.setOnClickListener { fetchGist() }

            binding.spGistRules.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in gistRules.indices) {
                        val rule = gistRules[position]
                        binding.etInterval.setText(rule.interval ?: "3m")
                        binding.etTolerance.setText(rule.tolerance?.toString() ?: "50.0")
                        updatePreview()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            loadCurrentSettings()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error initializing QsTileConfigActivity", e)
        }
    }

    private fun loadCurrentSettings() {
        val currentMode = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_MODE, "0")?.toIntOrNull() ?: 0
        val currentVal = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_VAL, "") ?: ""
        val currentInterval = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_INTERVAL, "3m")
        val currentTolerance = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_TOLERANCE, "50.0")
        val currentGistUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_GIST_URL, "")

        binding.spMode.setSelection(currentMode)
        if (currentMode == 2) {
            val pos = allPolicyGroups.indexOfFirst { it.remarks == currentVal }
            if (pos >= 0) binding.spPolicyGroups.setSelection(pos)
        } else if (currentMode == 3) {
            binding.etRegex.setText(currentVal)
        } else if (currentMode == 4) {
            binding.etGistUrl.setText(currentGistUrl)
            if (!currentGistUrl.isNullOrBlank()) {
                fetchGist(currentVal)
            }
        }

        binding.etInterval.setText(currentInterval)
        binding.etTolerance.setText(currentTolerance)
    }

    private fun updateUIForMode(mode: Int) {
        binding.llPolicyGroup.visibility = if (mode == 2) View.VISIBLE else View.GONE
        binding.llRegex.visibility = if (mode == 3) View.VISIBLE else View.GONE
        binding.llGist.visibility = if (mode == 4) View.VISIBLE else View.GONE
        binding.llIntervalTolerance.visibility = if (mode == 3 || mode == 4) View.VISIBLE else View.GONE
        binding.cvPreview.visibility = if (mode == 3 || mode == 4) View.VISIBLE else View.GONE
    }

    private fun fetchGist(selectRegex: String? = null) {
        val url = binding.etGistUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("Please enter a valid Gist URL")
            return
        }
        
        binding.btnFetchGist.isEnabled = false
        binding.btnFetchGist.text = "Fetching..."

        lifecycleScope.launch(Dispatchers.IO) {
            val (rules, _) = GistRuleProvider.fetchAndParseRules(url)
            withContext(Dispatchers.Main) {
                binding.btnFetchGist.isEnabled = true
                binding.btnFetchGist.text = "Fetch"
                if (rules != null && rules.isNotEmpty()) {
                    gistRules = rules
                    val ruleNames = gistRules.map { it.remarks }
                    binding.spGistRules.adapter = ArrayAdapter(this@QsTileConfigActivity, android.R.layout.simple_spinner_item, ruleNames).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    
                    if (selectRegex != null) {
                        val pos = gistRules.indexOfFirst { it.regex == selectRegex }
                        if (pos >= 0) binding.spGistRules.setSelection(pos)
                    }
                    toast("Loaded ${rules.size} rules")
                } else {
                    toast("Failed to load rules from Gist")
                }
            }
        }
    }

    private fun updatePreview() {
        val mode = binding.spMode.selectedItemPosition
        if (mode != 3 && mode != 4) return

        val regexStr = if (mode == 3) {
            binding.etRegex.text.toString().trim()
        } else {
            val pos = binding.spGistRules.selectedItemPosition
            if (pos in gistRules.indices) gistRules[pos].regex.orEmpty() else ""
        }

        val interval = binding.etInterval.text.toString().trim().ifEmpty { "3m" }
        val tol = binding.etTolerance.text.toString().trim().ifEmpty { "50.0" }
        val intTolInfo = " | Int: $interval | Tol: $tol"

        if (regexStr.isEmpty()) {
            binding.tvMatchedCount.text = "Matched Proxies: ${allProxies.size}$intTolInfo (No filter)"
            binding.tvMatchedList.text = allProxies.joinToString("\n") { it.remarks }
            return
        }

        val processedRegex = AutoOutboundBuilder.expandFlagShorthands(regexStr)
        val regex = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
        
        if (regex == null) {
            binding.tvMatchedCount.text = "Invalid Regex"
            binding.tvMatchedList.text = ""
            return
        }

        val matched = allProxies.filter { config ->
            val searchString = "[${config.configType.name}] ${config.remarks}"
            regex.containsMatchIn(searchString) || searchString.contains(processedRegex, ignoreCase = true)
        }

        binding.tvMatchedCount.text = "Matched Proxies: ${matched.size}$intTolInfo"
        binding.tvMatchedList.text = matched.joinToString("\n") { it.remarks }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        menu.findItem(R.id.del_config)?.isVisible = false
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.save_config -> {
            saveConfig()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun saveConfig() {
        val mode = binding.spMode.selectedItemPosition
        MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_MODE, mode.toString())

        var targetVal = ""
        when (mode) {
            2 -> {
                val pos = binding.spPolicyGroups.selectedItemPosition
                targetVal = if (pos in allPolicyGroups.indices) allPolicyGroups[pos].remarks else ""
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, targetVal)
            }
            3, 4 -> {
                if (mode == 3) {
                    targetVal = binding.etRegex.text.toString().trim()
                } else {
                    val pos = binding.spGistRules.selectedItemPosition
                    targetVal = if (pos in gistRules.indices) gistRules[pos].regex.orEmpty() else ""
                    MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_GIST_URL, binding.etGistUrl.text.toString().trim())
                }
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, targetVal)
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_INTERVAL, binding.etInterval.text.toString().trim())
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_TOLERANCE, binding.etTolerance.text.toString().trim())

                // Immediately materialize the Global QS Target policy group
                val allServers = MmkvManager.decodeAllServerList()
                val globalGroupGuid = allServers.find { guid ->
                    MmkvManager.decodeServerConfig(guid)?.remarks == "Global QS Target"
                } ?: ""
                
                val config = MmkvManager.decodeServerConfig(globalGroupGuid) ?: ProfileItem.create(EConfigType.POLICYGROUP)
                config.remarks = "Global QS Target"
                config.policyGroupType = "0" // Least Ping is best for a quick tile regex match
                config.policyGroupSubscriptionId = "" // Empty means evaluate across ALL subscriptions
                config.policyGroupFilter = targetVal
                config.policyGroupInterval = binding.etInterval.text.toString().trim().ifEmpty { "3m" }
                config.policyGroupTolerance = binding.etTolerance.text.toString().trim().toDoubleOrNull() ?: 50.0
                config.description = "Global Quick Tile Auto-Group"
                config.subscriptionId = "" // Ensure it does not belong to a sub that could be deleted
                
                MmkvManager.encodeServerConfig(globalGroupGuid, config)
            }
            else -> {
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, "")
            }
        }
        toast(R.string.toast_success)
        finish()
    }
}

