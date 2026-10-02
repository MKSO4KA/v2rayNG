package com.v2ray.ang.ui.server

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.horizontalScrollbar
import com.v2ray.ang.ui.compose.verticalScrollbar

class ServerCustomConfigActivity : BaseComponentActivity() {
    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy { intent.getBooleanExtra("isRunning", false) && editGuid.isNotEmpty() && editGuid == MmkvManager.getSelectServer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        val config = MmkvManager.decodeServerConfig(editGuid)
        val initialRemarks = config?.remarks ?: ""
        val initialContent = MmkvManager.decodeServerRaw(editGuid).orEmpty()
        ServerCustomConfigScreen(
            editGuid = editGuid,
            isRunning = isRunning,
            initialRemarks = initialRemarks,
            initialContent = initialContent,
            onBackClick = { finish() },
            onSave = { r, c -> saveServer(r, c) },
            onDelete = { deleteServer() }
        )
    }

    private fun saveServer(remarks: String, content: String): Boolean {
        if (remarks.isBlank()) return false
        val parsedProfile = try { CustomFmt.parse(content) } catch (e: Exception) { toast(getString(R.string.toast_malformed_json)); return false }
        val config = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.CUSTOM)
        config.remarks = remarks.ifEmpty { parsedProfile?.remarks.orEmpty() }
        config.server = parsedProfile?.server
        config.serverPort = parsedProfile?.serverPort
        config.description = AngConfigManager.generateDescription(config)
        val savedGuid = MmkvManager.encodeServerConfig(editGuid, config)
        MmkvManager.encodeServerRaw(savedGuid, content)
        toastSuccess(R.string.toast_success)
        ProfileEditorResult.finishSaved(this, savedGuid, isRunning)
        return true
    }

    private fun deleteServer(): Boolean {
        if (editGuid.isEmpty() || editGuid == MmkvManager.getSelectServer()) { toast(R.string.toast_action_not_allowed); return false }
        MmkvManager.removeServer(editGuid)
        ProfileEditorResult.finishDeleted(this, editGuid)
        return true
    }
}

@Composable
fun ServerCustomConfigScreen(editGuid: String, isRunning: Boolean, initialRemarks: String, initialContent: String, onBackClick: () -> Unit, onSave: (String, String) -> Boolean, onDelete: () -> Unit) {
    var remarks by rememberSaveable { mutableStateOf(initialRemarks) }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    val textFieldState = rememberTextFieldState(initialText = initialContent)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val textMeasurer = rememberTextMeasurer()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = EConfigType.CUSTOM.toString(),
                onBackClick = onBackClick,
                actions = {
                    if (editGuid.isNotEmpty() && !isRunning) {
                        IconButton(onClick = { showDeleteConfirm = true }) { Icon(painterResource(R.drawable.ic_delete_24dp), stringResource(R.string.acc_delete)) }
                    }
                    IconButton(onClick = {
                        if (remarks.isBlank()) isRemarksError = true
                        else onSave(remarks, textFieldState.text.toString())
                    }) { Icon(painterResource(R.drawable.ic_fab_check), stringResource(R.string.acc_save)) }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
            FormTextField(label = stringResource(R.string.server_lab_remarks), value = remarks, onValueChange = { remarks = it }, isError = isRemarksError)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
                    val lineNumColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    val layout = textLayoutResult
                    if (layout != null && layout.lineCount > 0) {
                        Canvas(modifier = Modifier.width(40.dp).height(with(density) { layout.size.height.toDp() })) {
                            drawEditorLineNumbers(layout, textMeasurer, lineNumColor)
                        }
                    }
                    BasicTextField(
                        state = textFieldState,
                        modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll).padding(end = 24.dp, bottom = 36.dp),
                        textStyle = EditorConstants.LINE_NUMBER_STYLE.copy(color = MaterialTheme.colorScheme.onSurface),
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                        onTextLayout = { provider -> textLayoutResult = provider() }
                    )
                }
                Box(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EditorConstants.SCROLLBAR_THICKNESS + 4.dp).verticalScrollbar(verticalScroll))
                Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EditorConstants.SCROLLBAR_THICKNESS + 4.dp).horizontalScrollbar(horizontalScroll))
            }
            NavigationBarsSpacer()
        }
    }
    if (showDeleteConfirm) {
        DeleteConfirmDialog(stringResource(R.string.confirm_delete_profile), itemName = initialRemarks, onConfirm = { showDeleteConfirm = false; onDelete() }, onDismiss = { showDeleteConfirm = false })
    }
}

