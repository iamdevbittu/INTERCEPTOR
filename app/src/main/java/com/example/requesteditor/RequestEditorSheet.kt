package com.example.requesteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.network.InterceptedRequest
import com.example.network.RequestEditDraft
import com.example.security.SecurityValidator
import com.example.ui.theme.AmberPending
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldAllow
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletModified

data class EditablePair(val key: String, val value: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestEditorDialog(
    originalRequest: InterceptedRequest,
    onDismiss: () -> Unit,
    onTransmitEdited: (RequestEditDraft) -> Unit
) {
    var editedUrl by remember(originalRequest.id) { mutableStateOf(originalRequest.url) }
    var editedMethod by remember(originalRequest.id) { mutableStateOf(originalRequest.method.uppercase()) }
    var editedBody by remember(originalRequest.id) { mutableStateOf(originalRequest.body ?: "") }

    val queryPairs = remember(originalRequest.id) {
        mutableStateListOf<EditablePair>().apply {
            originalRequest.queryParams.forEach { (k, v) -> add(EditablePair(k, v)) }
        }
    }

    val headerPairs = remember(originalRequest.id) {
        mutableStateListOf<EditablePair>().apply {
            originalRequest.headers
                .filterKeys { !it.equals("Cookie", ignoreCase = true) }
                .forEach { (k, v) -> add(EditablePair(k, v)) }
        }
    }

    val cookiePairs = remember(originalRequest.id) {
        mutableStateListOf<EditablePair>().apply {
            originalRequest.cookies.forEach { (k, v) -> add(EditablePair(k, v)) }
        }
    }

    var activeSectionTab by remember { mutableIntStateOf(0) }
    val sectionTitles = listOf("Endpoint", "Headers (${headerPairs.size})", "Query & Cookies", "Body / JSON")

    val currentDraft by remember {
        derivedStateOf {
            val qMap = LinkedHashMap<String, String>()
            queryPairs.filter { it.key.isNotBlank() }.forEach { qMap[it.key.trim()] = it.value }

            val hMap = LinkedHashMap<String, String>()
            headerPairs.filter { it.key.isNotBlank() }.forEach { hMap[it.key.trim()] = it.value }

            val cMap = LinkedHashMap<String, String>()
            cookiePairs.filter { it.key.isNotBlank() }.forEach { cMap[it.key.trim()] = it.value }

            RequestEditDraft(
                url = editedUrl,
                method = editedMethod,
                headers = hMap,
                queryParams = qMap,
                cookies = cMap,
                body = editedBody.ifEmpty { null }
            )
        }
    }

    val validation by remember {
        derivedStateOf { SecurityValidator.validateDraft(currentDraft) }
    }

    val diffSummary by remember {
        derivedStateOf { SecurityValidator.computeModificationSummary(originalRequest, currentDraft) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(8.dp)
                .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .testTag("request_editor_dialog"),
            shape = RoundedCornerShape(14.dp),
            color = ObsidianBg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "REQUEST EDITOR",
                            style = MaterialTheme.typography.labelLarge,
                            color = CyberCyan
                        )
                        Text(
                            text = "Modify & validate packet before transmission",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Edit", tint = TextSecondary)
                    }
                }

                ScrollableTabRow(
                    selectedTabIndex = activeSectionTab,
                    containerColor = ObsidianSurface,
                    contentColor = CyberCyan,
                    edgePadding = 12.dp
                ) {
                    sectionTitles.forEachIndexed { idx, title ->
                        Tab(
                            selected = activeSectionTab == idx,
                            onClick = { activeSectionTab = idx },
                            text = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (activeSectionTab == idx) CyberCyan else TextSecondary,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (activeSectionTab) {
                        0 -> {
                            Text("HTTP METHOD", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                            val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                methods.forEach { m ->
                                    FilterChip(
                                        selected = editedMethod == m,
                                        onClick = { editedMethod = m },
                                        label = { Text(m, style = MaterialTheme.typography.labelMedium) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                                            selectedLabelColor = CyberCyan
                                        ),
                                        modifier = Modifier.testTag("method_chip_$m")
                                    )
                                }
                            }

                            Text("TARGET URL", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                            OutlinedTextField(
                                value = editedUrl,
                                onValueChange = { newUrl ->
                                    editedUrl = newUrl
                                    val extracted = InterceptedRequest.extractQueryParams(newUrl)
                                    queryPairs.clear()
                                    extracted.forEach { (k, v) -> queryPairs.add(EditablePair(k, v)) }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("editor_url_input"),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                singleLine = false,
                                maxLines = 3,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = ObsidianBorder
                                )
                            )
                        }

                        1 -> {
                            KeyValueEditorList(
                                title = "HTTP REQUEST HEADERS",
                                pairs = headerPairs,
                                keyHint = "Header Name",
                                valueHint = "Header Value",
                                addTag = "add_header_button"
                            )
                        }

                        2 -> {
                            KeyValueEditorList(
                                title = "URL QUERY PARAMETERS",
                                pairs = queryPairs,
                                keyHint = "Param Key",
                                valueHint = "Param Value",
                                addTag = "add_query_param_button",
                                onPairsChanged = {
                                    val qMap = LinkedHashMap<String, String>()
                                    queryPairs.filter { it.key.isNotBlank() }.forEach { qMap[it.key.trim()] = it.value }
                                    editedUrl = InterceptedRequest.buildUrlWithQueryParams(editedUrl, qMap)
                                }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            KeyValueEditorList(
                                title = "REQUEST COOKIES",
                                pairs = cookiePairs,
                                keyHint = "Cookie Name",
                                valueHint = "Cookie Value",
                                addTag = "add_cookie_button"
                            )
                        }

                        3 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "PAYLOAD (JSON / FORM / TEXT)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyberCyan
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ObsidianSurfaceElevated)
                                        .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            editedBody = SecurityValidator.formatPrettyJson(editedBody)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Format JSON", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                }
                            }
                            OutlinedTextField(
                                value = editedBody,
                                onValueChange = { editedBody = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .testTag("editor_body_input"),
                                placeholder = { Text("{\"key\": \"value\"}", color = TextMuted) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = CyberCyan,
                                    unfocusedBorderColor = ObsidianBorder
                                )
                            )
                        }
                    }

                    // Validation Status & Modification Summary Panel
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurface, RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (validation.isValid) ObsidianBorder else CrimsonBlock,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (validation.isValid) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (validation.isValid) EmeraldAllow else CrimsonBlock,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (validation.isValid) "PRE-FLIGHT VALIDATION PASSED" else "VALIDATION ERRORS DETECTED",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (validation.isValid) EmeraldAllow else CrimsonBlock
                            )
                        }

                        validation.errors.forEach { err ->
                            Text(
                                text = "✗ $err",
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonBlock,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        validation.warnings.forEach { warn ->
                            Text(
                                text = "⚠ $warn",
                                style = MaterialTheme.typography.bodySmall,
                                color = AmberPending,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (diffSummary.isEmpty()) "No modifications made yet (will send original values)"
                            else "MODIFICATIONS TO TRANSMIT (${diffSummary.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (diffSummary.isEmpty()) TextMuted else VioletModified
                        )
                        diffSummary.forEach { item ->
                            Text(
                                text = "• $item",
                                style = MaterialTheme.typography.bodySmall,
                                color = VioletModified,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                // Bottom Action Bar (Non-clipping custom buttons)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "CANCEL",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1.8f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (validation.isValid) EmeraldAllow else ObsidianBorder)
                            .clickable(enabled = validation.isValid) {
                                onTransmitEdited(currentDraft)
                            }
                            .padding(horizontal = 12.dp)
                            .testTag("confirm_edit_send_button"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = if (validation.isValid) Color.Black else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SEND EDITED PACKET",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (validation.isValid) Color.Black else TextMuted,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueEditorList(
    title: String,
    pairs: MutableList<EditablePair>,
    keyHint: String,
    valueHint: String,
    addTag: String,
    onPairsChanged: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = CyberCyan)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianSurfaceElevated)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                    .clickable {
                        pairs.add(EditablePair("", ""))
                        onPairsChanged()
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag(addTag),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Row", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
            }
        }

        if (pairs.isEmpty()) {
            Text("None configured. Tap 'Add Row' to insert.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }

        pairs.forEachIndexed { index, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pair.key,
                    onValueChange = { newK ->
                        pairs[index] = pair.copy(key = newK)
                        onPairsChanged()
                    },
                    placeholder = { Text(keyHint, style = MaterialTheme.typography.labelSmall, color = TextMuted) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.42f)
                )
                OutlinedTextField(
                    value = pair.value,
                    onValueChange = { newV ->
                        pairs[index] = pair.copy(value = newV)
                        onPairsChanged()
                    },
                    placeholder = { Text(valueHint, style = MaterialTheme.typography.labelSmall, color = TextMuted) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.48f)
                )
                IconButton(
                    onClick = {
                        pairs.removeAt(index)
                        onPairsChanged()
                    },
                    modifier = Modifier.weight(0.1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove row", tint = CrimsonBlock)
                }
            }
        }
    }
}
