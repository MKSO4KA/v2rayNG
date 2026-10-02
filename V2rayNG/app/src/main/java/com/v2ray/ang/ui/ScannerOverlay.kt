package com.v2ray.ang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

@Composable
fun ScannerIdlePlaceholder(onStartClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).pointerInput(Unit) { detectTapGestures { onStartClick() } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painter = painterResource(R.drawable.ic_scan_24dp), contentDescription = stringResource(R.string.acc_start_scanner), modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.menu_item_scan_qrcode), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.summary_scan_qrcode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ScannerOverlay() {
    val scanBoxSize = 250.dp
    val cornerLength = 24.dp
    val cornerWidth = 3.dp
    val cornerColor = Color(0xFF4CAF50)
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSizePx = scanBoxSize.toPx()
            val left = (size.width - boxSizePx) / 2f
            val top = (size.height - boxSizePx) / 2f
            drawRect(color = Color(0x80000000))
            drawRect(color = Color.Transparent, topLeft = Offset(left, top), size = Size(boxSizePx, boxSizePx), blendMode = BlendMode.Clear)
        }
        Box(modifier = Modifier.size(scanBoxSize).align(Alignment.Center)) {
            listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd).forEach { align ->
                Box(modifier = Modifier.align(align).width(cornerLength).height(cornerWidth).background(cornerColor))
                Box(modifier = Modifier.align(align).width(cornerWidth).height(cornerLength).background(cornerColor))
            }
        }
        Text(text = stringResource(R.string.menu_item_scan_qrcode), color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center).offset(y = scanBoxSize / 2 + 24.dp))
    }
}
