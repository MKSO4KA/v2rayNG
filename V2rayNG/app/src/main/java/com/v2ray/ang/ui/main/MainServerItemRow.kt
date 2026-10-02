package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.colorConfigType
import com.v2ray.ang.ui.compose.colorPing
import com.v2ray.ang.ui.compose.colorPingRed

internal class ServerRowActions(
    val select: (String) -> Unit,
    val edit: (String, ProfileItem) -> Unit,
    val share: (String, ProfileItem) -> Unit,
    val more: (String, ProfileItem) -> Unit,
    val remove: (String, String) -> Unit,
)

@Composable
internal fun ServerItemRow(row: ServerRowUiModel, isSelected: Boolean, actions: ServerRowActions) {
    ServerListItem(row = row, isSelected = isSelected, doubleColumnDisplay = false, actions = actions)
}

@Composable
internal fun ServerItemColumn(row: ServerRowUiModel, isSelected: Boolean, doubleColumnDisplay: Boolean, actions: ServerRowActions) {
    Column {
        ServerListItem(row = row, isSelected = isSelected, doubleColumnDisplay = doubleColumnDisplay, actions = actions)
        ItemDivider()
    }
}

@Composable
internal fun ServerListItem(row: ServerRowUiModel, isSelected: Boolean, doubleColumnDisplay: Boolean, actions: ServerRowActions) {
    val testResult = if (row.testDelayMillis == 0L) "" else stringResource(R.string.server_test_delay_value, row.testDelayMillis)
    val selectedDesc = if (isSelected) stringResource(R.string.acc_selected_server) else null
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selectedDesc?.let { stateDescription = it } }
            .clickable { actions.select(row.guid) }
            .then(
                if (isSelected) {
                    Modifier.drawBehind {
                        val barWidth = 4.dp.toPx()
                        val left = 6.dp.toPx()
                        val top = 10.dp.toPx()
                        val bottom = size.height - 10.dp.toPx()
                        if (bottom > top) {
                            drawRoundRect(
                                color = primaryColor,
                                topLeft = Offset(left, top),
                                size = Size(barWidth, bottom - top),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                    }
                } else Modifier
            )
    ) {
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.remarks, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (doubleColumnDisplay) {
                    IconButton(onClick = { actions.more(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_more_vert_24dp), stringResource(R.string.acc_more), Modifier.size(24.dp))
                    }
                } else {
                    IconButton(onClick = { actions.share(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_share_24dp), stringResource(R.string.title_configuration_share), Modifier.size(24.dp))
                    }
                    IconButton(onClick = { actions.edit(row.guid, row.profile) }, Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_edit_24dp), stringResource(R.string.acc_edit), Modifier.size(24.dp))
                    }
                    IconButton(onClick = { actions.remove(row.guid, row.remarks) }, Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.acc_delete), Modifier.size(24.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (row.subscriptionBadge.isNotBlank()) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), Alignment.Center) {
                        Text(row.subscriptionBadge.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(row.statistics, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.typeDescription, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = colorConfigType, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.width(8.dp))
                Text(testResult, style = MaterialTheme.typography.bodySmall, color = if (row.testDelayMillis < 0L) colorPingRed else colorPing, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
