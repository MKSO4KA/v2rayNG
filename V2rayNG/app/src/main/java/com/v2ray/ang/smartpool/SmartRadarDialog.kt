package com.v2ray.ang.smartpool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SmartRadarDialog(
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val radarUrl = "http://127.0.0.1:29999/"
    val count by SmartPoolManager.radarCountState.collectAsState()
    val isRunning by SmartPoolManager.radarRunningState.collectAsState()

    DisposableEffect(Unit) {
        SmartPoolManager.startRadar {}
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
            Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                Text(
                    text = "Перехват сетевого отпечатка клиента для обхода блокировок подписок.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Локальный URL подписки:",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = radarUrl,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(radarUrl)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Скопировать URL подписки")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Инструкция:\n1. Вставьте этот URL в клиент (Happ, v2rayN и т.д.)\n2. Нажмите «Обновить подписку» 3 раза.",
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
}
