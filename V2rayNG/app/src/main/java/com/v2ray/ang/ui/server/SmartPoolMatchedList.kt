package com.v2ray.ang.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.dto.entities.ProfileItem

@Composable
fun SmartPoolMatchedList(
    matchedNodes: List<ProfileItem>,
    interval: String,
    tolerance: String,
    modifier: Modifier = Modifier
) {
    val cardBg = Color(0xFFFBECE1)
    val borderColor = Color(0xFFE9CDB7)
    val textColor = Color(0xFF2E221B)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val headerText = "Matched Proxies: ${matchedNodes.size} | Int: ${interval.ifBlank { "20s" }} | Tol: ${tolerance.ifBlank { "30.0" }}"
            Text(
                text = headerText,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (matchedNodes.isEmpty()) {
                Text(
                    text = "Нет подходящих узлов по текущему фильтру",
                    fontSize = 12.sp,
                    color = textColor.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(matchedNodes) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "[${item.configType.name}]",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.remarks,
                                fontSize = 12.sp,
                                color = textColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
