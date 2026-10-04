package com.v2ray.ang.smartpool

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils

@Composable
fun SmartRadarDialog(
    onDismiss: () -> Unit,
    onProfileCaptured: ((MimicryProfile) -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val localUrl = "http://127.0.0.1:${SmartPoolConstants.RADAR_PORT}/"
    val lanIp = remember { Utils.getLanIpAddress() }
    val lanUrl = lanIp?.let { "http://$it:${SmartPoolConstants.RADAR_PORT}/" }

    val count by SmartPoolManager.radarCountState.collectAsState()
    val isRunning by SmartPoolManager.radarRunningState.collectAsState()
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scrollState = rememberScrollState()

    DisposableEffect(Unit) {
        SmartPoolManager.startRadar { captured ->
            onProfileCaptured?.invoke(captured)
        }
        onDispose {
            SmartPoolManager.stopRadar()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "📡 Радар мимикрии",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(4.dp)
            ) {
                Text(
                    text = "Перехват сетевого отпечатка клиента для обхода блокировок подписок.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Local URL (same device)
                Text(
                    text = stringResource(R.string.radar_local_url),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = localUrl,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(localUrl)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.radar_copy_local_url))
                }

                // LAN URL (other devices in Wi-Fi)
                if (lanUrl != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.radar_lan_url),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = lanUrl,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { clipboardManager.setText(AnnotatedString(lanUrl)) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.radar_copy_lan_url))
                        }
                        OutlinedButton(
                            onClick = { qrCodeBitmap = QRCodeDecoder.createQRCode(lanUrl) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_scan_24dp),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("QR-код")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Инструкция:\n1. Вставьте этот URL в клиент (v2rayN, Happ и т.д.) или отсканируйте QR-код.\n2. Нажмите «Обновить подписку» 3 раза.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                val statusText = if (count >= 3) {
                    "✅ Профиль успешно захвачен и сохранен!"
                } else if (isRunning) {
                    "⏳ Ожидание запросов: $count из 3 перехвачено"
                } else {
                    "Сервер остановлен"
                }
                Text(
                    text = statusText,
                    fontWeight = FontWeight.Bold,
                    color = if (count >= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )

    if (qrCodeBitmap != null) {
        QRCodeDialog(
            bitmap = qrCodeBitmap,
            onDismiss = { qrCodeBitmap = null }
        )
    }
}
