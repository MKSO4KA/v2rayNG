package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.camera.core.CameraControl
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder

class ScannerActivity : HelperBaseComponentActivity() {
    private val uiState = mutableStateOf(ScannerUiState.IDLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startScan()
    }

    @Composable
    override fun ScreenContent() {
        ScannerScreen(
            uiState = uiState.value,
            onBackClick = { finish() },
            onSelectPhoto = { showFileChooser() },
            onStartScan = { startScan() },
            onStopScan = { stopScan() },
            onScanResult = { text -> finished(text) }
        )
    }

    private fun startScan() {
        checkAndRequestPermission(PermissionType.CAMERA) {
            uiState.value = ScannerUiState.ACTIVE
        }
    }

    private fun stopScan() {
        uiState.value = ScannerUiState.IDLE
    }

    private fun finished(text: String) {
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", text)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun showFileChooser() {
        launchFileChooser("image/*") { uri ->
            if (uri == null) return@launchFileChooser
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                val text = QRCodeDecoder.syncDecodeQRCode(bitmap)
                if (text.isNullOrEmpty()) {
                    toast(R.string.toast_decoding_failed)
                } else {
                    finished(text)
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to decode QR code from file", e)
                toast(R.string.toast_decoding_failed)
            }
        }
    }
}

enum class ScannerUiState {
    IDLE,
    ACTIVE
}

@Composable
fun ScannerScreen(
    uiState: ScannerUiState,
    onBackClick: () -> Unit,
    onSelectPhoto: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onScanResult: (String) -> Unit
) {
    val isScanning = uiState == ScannerUiState.ACTIVE
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var hasTorch by remember { mutableStateOf(false) }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.menu_item_import_config_qrcode),
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = {
                            if (isScanning) {
                                if (torchEnabled) {
                                    torchEnabled = false
                                    cameraControl?.enableTorch(false)
                                }
                                onStopScan()
                            } else {
                                onStartScan()
                            }
                        }
                    ) {
                        Icon(
                            painterResource(if (isScanning) R.drawable.ic_stop_24dp else R.drawable.ic_scan_24dp),
                            contentDescription = stringResource(if (isScanning) R.string.acc_stop_scanner else R.string.acc_start_scanner)
                        )
                    }
                    if (isScanning && hasTorch) {
                        IconButton(
                            onClick = {
                                torchEnabled = !torchEnabled
                                cameraControl?.enableTorch(torchEnabled)
                            }
                        ) {
                            Icon(
                                painterResource(if (torchEnabled) R.drawable.ic_flash_on_24dp else R.drawable.ic_flash_off_24dp),
                                contentDescription = stringResource(if (torchEnabled) R.string.acc_turn_torch_off else R.string.acc_turn_torch_on)
                            )
                        }
                    }
                    IconButton(onClick = onSelectPhoto) {
                        Icon(painterResource(R.drawable.ic_image_24dp), contentDescription = stringResource(R.string.acc_select_image))
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (uiState) {
                ScannerUiState.IDLE -> ScannerIdlePlaceholder(onStartClick = onStartScan)
                ScannerUiState.ACTIVE -> {
                    CameraXPreview(
                        onScanResult = onScanResult,
                        onCameraReady = { control, info ->
                            cameraControl = control
                            hasTorch = info.hasFlashUnit()
                            if (torchEnabled && hasTorch) control.enableTorch(true)
                        }
                    )
                    ScannerOverlay()
                }
            }
        }
    }
}

