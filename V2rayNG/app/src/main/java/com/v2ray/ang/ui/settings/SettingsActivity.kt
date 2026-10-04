package com.v2ray.ang.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch

class SettingsActivity : BaseComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshSystemVpnSettingsAvailability()
            }
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            LogUtil.e(AppConfig.TAG, "Cannot open system VPN settings", error)
            toastError(R.string.toast_system_vpn_settings_unavailable)
        } catch (error: SecurityException) {
            LogUtil.e(AppConfig.TAG, "Cannot open system VPN settings", error)
            toastError(R.string.toast_system_vpn_settings_unavailable)
        }
    }

    @Composable
    override fun ScreenContent() {
        SettingsScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onModeHelpClicked = { Utils.openUri(this, AppConfig.APP_WIKI_MODE) },
            onSystemVpnSettingsClicked = ::openSystemVpnSettings
        )
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onModeHelpClicked: () -> Unit,
    onSystemVpnSettingsClicked: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val systemVpnSettingsAvailable by viewModel.systemVpnSettingsAvailable.collectAsStateWithLifecycle()
    var uiSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var vpnSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var coreSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var advancedSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var modeSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var gistSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    val mode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE, VPN)
    val isVpn = mode == VPN

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(title = stringResource(R.string.title_settings), onBackClick = onBackClick, isLoading = isLoading)
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScrollbar(scrollState).verticalScroll(scrollState)) {
            UiSettingsGroup(uiSettingsExpanded) { uiSettingsExpanded = it }
            VpnSettingsGroup(vpnSettingsExpanded, { vpnSettingsExpanded = it }, isVpn)
            CoreSettingsGroup(coreSettingsExpanded) { coreSettingsExpanded = it }
            AdvancedSettingsGroup(advancedSettingsExpanded, { advancedSettingsExpanded = it }, systemVpnSettingsAvailable, onSystemVpnSettingsClicked)
            GistSettingsGroup(gistSettingsExpanded) { gistSettingsExpanded = it }
            ModeSettingsGroup(modeSettingsExpanded, { modeSettingsExpanded = it }, onModeHelpClicked)
            Spacer(modifier = Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }
}

