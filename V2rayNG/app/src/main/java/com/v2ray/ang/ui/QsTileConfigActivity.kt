package com.v2ray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
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

    private var spMode: Spinner? = null
    private var llPolicyGroup: LinearLayout? = null
    private var spPolicyGroups: Spinner? = null
    private var llRegex: LinearLayout? = null
    private var etRegex: EditText? = null
    private var llGist: LinearLayout? = null
    private var etGistUrl: EditText? = null
    private var btnFetchGist: Button? = null
    private var spGistRules: Spinner? = null
    private var llIntervalTolerance: LinearLayout? = null
    private var etInterval: EditText? = null
    private var etTolerance: EditText? = null
    private var cvPreview: CardView? = null
    private var tvMatchedCount: TextView? = null
    private var tvMatchedList: TextView? = null

    private val allProxies = mutableListOf<ProfileItem>()
    private val allPolicyGroups = mutableListOf<ProfileItem>()
    private var gistRules = listOf<AutoGroupRule>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qs_tile_config)
        setupToolbar(findViewById(R.id.toolbar), showHomeAsUp = true, title = getString(R.string.qs_tile_config_title))

        try {
            spMode = findViewById(R.id.sp_mode)
            llPolicyGroup = findViewById(R.id.ll_policy_group)
            spPolicyGroups = findViewById(R.id.sp_policy_groups)
            llRegex = findViewById(R.id.ll_regex)
            etRegex = findViewById(R.id.et_regex)
            llGist = findViewById(R.id.ll_gist)
            etGistUrl = findViewById(R.id.et_gist_url)
            btnFetchGist = findViewById(R.id.btn_fetch_gist)
            spGistRules = findViewById(R.id.sp_gist_rules)
            llIntervalTolerance = findViewById(R.id.ll_interval_tolerance)
            etInterval = findViewById(R.id.et_interval)
            etTolerance = findViewById(R.id.et_tolerance)
            cvPreview = findViewById(R.id.cv_preview)
            tvMatchedCount = findViewById(R.id.tv_matched_count)
            tvMatchedList = findViewById(R.id.tv_matched_list)

            val serverList = MmkvManager.decodeAllServerList()
            LogUtil.i(AppConfig.TAG, "QsTileConfig: Raw serverList size from MMKV = ${serverList.size}")

            serverList.forEach { guid ->
                MmkvManager.decodeServerConfig(guid)?.let { config ->
                    if (config.configType == EConfigType.POLICYGROUP) {
                        allPolicyGroups.add(config)
                    } else {
                        // Keep all types of proxies including CUSTOM so they can be matched
                        allProxies.add(config)
                    }
                }
            }
            LogUtil.i(AppConfig.TAG, "QsTileConfig: Loaded ${allProxies.size} proxies and ${allPolicyGroups.size} policy groups.")

            val modes = listOf("Last Selected (Default)", "Best Ping (Global)", "Specific Policy Group", "Global Regex Match", "Gist-Based Global Targeting")
            spMode?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val policyNames = allPolicyGroups.map { it.remarks }.ifEmpty { listOf("No Policy Groups Found") }
            spPolicyGroups?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, policyNames).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            spMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateUIForMode(position)
                    updatePreview()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            etRegex?.addTextChangedListener { updatePreview() }
            etInterval?.addTextChangedListener { updatePreview() }
            etTolerance?.addTextChangedListener { updatePreview() }

            btnFetchGist?.setOnClickListener { fetchGist() }

            spGistRules?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position >= 0 && position < gistRules.size) {
                        val rule = gistRules[position]
                        etInterval?.setText(rule.interval ?: "3m")
                        etTolerance?.setText(rule.tolerance?.toString() ?: "50.0")
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
        val currentVal = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_VAL, "")
        val currentInterval = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_INTERVAL, "3m")
        val currentTolerance = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_TOLERANCE, "50.0")
        val currentGistUrl = MmkvManager.decodeSettingsString(AppConfig.PREF_QS_TILE_GIST_URL, "")

        spMode?.setSelection(currentMode)
        if (currentMode == 2) {
            val pos = allPolicyGroups.indexOfFirst { it.remarks == currentVal }
            if (pos >= 0) spPolicyGroups?.setSelection(pos)
        } else if (currentMode == 3) {
            etRegex?.setText(currentVal)
        } else if (currentMode == 4) {
            etGistUrl?.setText(currentGistUrl)
            if (!currentGistUrl.isNullOrBlank()) {
                fetchGist(currentVal)
            }
        }

        etInterval?.setText(currentInterval)
        etTolerance?.setText(currentTolerance)
    }

    private fun updateUIForMode(mode: Int) {
        llPolicyGroup?.visibility = if (mode == 2) View.VISIBLE else View.GONE
        llRegex?.visibility = if (mode == 3) View.VISIBLE else View.GONE
        llGist?.visibility = if (mode == 4) View.VISIBLE else View.GONE
        llIntervalTolerance?.visibility = if (mode == 3 || mode == 4) View.VISIBLE else View.GONE
        cvPreview?.visibility = if (mode == 3 || mode == 4) View.VISIBLE else View.GONE
    }

    private fun fetchGist(selectRegex: String? = null) {
        val url = etGistUrl?.text.toString().trim()
        if (url.isEmpty()) {
            toast("Please enter a valid Gist URL")
            return
        }
        
        btnFetchGist?.isEnabled = false
        btnFetchGist?.text = "Fetching..."

        lifecycleScope.launch(Dispatchers.IO) {
            val (rules, _) = GistRuleProvider.fetchAndParseRules(url)
            withContext(Dispatchers.Main) {
                btnFetchGist?.isEnabled = true
                btnFetchGist?.text = "Fetch"
                if (rules != null && rules.isNotEmpty()) {
                    gistRules = rules
                    val ruleNames = gistRules.map { it.remarks }
                    spGistRules?.adapter = ArrayAdapter(this@QsTileConfigActivity, android.R.layout.simple_spinner_item, ruleNames).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    
                    if (selectRegex != null) {
                        val pos = gistRules.indexOfFirst { it.regex == selectRegex }
                        if (pos >= 0) spGistRules?.setSelection(pos)
                    }
                    toast("Loaded ${rules.size} rules")
                } else {
                    toast("Failed to load rules from Gist")
                }
            }
        }
    }

    private fun updatePreview() {
        val mode = spMode?.selectedItemPosition ?: return
        if (mode != 3 && mode != 4) return

        val regexStr = if (mode == 3) {
            etRegex?.text.toString().trim()
        } else {
            val pos = spGistRules?.selectedItemPosition ?: -1
            if (pos >= 0 && pos < gistRules.size) gistRules[pos].regex else ""
        }

        val interval = etInterval?.text.toString().trim().ifEmpty { "3m" }
        val tol = etTolerance?.text.toString().trim().ifEmpty { "50.0" }
        val intTolInfo = " | Int: $interval | Tol: $tol"

        if (regexStr.isNullOrEmpty()) {
            tvMatchedCount?.text = "Matched Proxies: ${allProxies.size}$intTolInfo (No filter)"
            tvMatchedList?.text = allProxies.joinToString("\n") { it.remarks }
            return
        }

        val processedRegex = AutoOutboundBuilder.expandFlagShorthands(regexStr)
        val regex = try { Regex(processedRegex, RegexOption.IGNORE_CASE) } catch(e: Exception) { null }
        
        if (regex == null) {
            tvMatchedCount?.text = "Invalid Regex"
            tvMatchedList?.text = ""
            return
        }

        val matched = allProxies.filter { config ->
            val searchString = "[${config.configType.name}] ${config.remarks}"
            regex.containsMatchIn(searchString) || searchString.contains(processedRegex, ignoreCase = true)
        }

        tvMatchedCount?.text = "Matched Proxies: ${matched.size}$intTolInfo"
        tvMatchedList?.text = matched.joinToString("\n") { it.remarks }
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
        val mode = spMode?.selectedItemPosition ?: 0
        MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_MODE, mode.toString())

        when (mode) {
            2 -> {
                val pos = spPolicyGroups?.selectedItemPosition ?: -1
                val valStr = if (pos >= 0 && pos < allPolicyGroups.size) allPolicyGroups[pos].remarks else ""
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, valStr)
            }
            3 -> {
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, etRegex?.text.toString().trim())
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_INTERVAL, etInterval?.text.toString().trim())
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_TOLERANCE, etTolerance?.text.toString().trim())
            }
            4 -> {
                val pos = spGistRules?.selectedItemPosition ?: -1
                val valStr = if (pos >= 0 && pos < gistRules.size) gistRules[pos].regex else ""
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, valStr)
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_GIST_URL, etGistUrl?.text.toString().trim())
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_INTERVAL, etInterval?.text.toString().trim())
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_TOLERANCE, etTolerance?.text.toString().trim())
            }
            else -> {
                MmkvManager.encodeSettings(AppConfig.PREF_QS_TILE_VAL, "")
            }
        }
        toast(R.string.toast_success)
        finish()
    }
}

