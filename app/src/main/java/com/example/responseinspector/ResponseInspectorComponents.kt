package com.example.responseinspector

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.network.InterceptedRequest
import com.example.network.PayloadFormat
import com.example.network.RequestState
import com.example.security.SecurityValidator
import com.example.ui.theme.AmberPending
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldAllow
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MethodDeleteColor
import com.example.ui.theme.MethodGetColor
import com.example.ui.theme.MethodPatchColor
import com.example.ui.theme.MethodPostColor
import com.example.ui.theme.MethodPutColor
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletModified
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    val preview = if (text.length > 36) text.take(36) + "…" else text
    Toast.makeText(context, "Copied $label ($preview)", Toast.LENGTH_SHORT).show()
}

object JsonSyntaxHighlighter {
    private val keyRegex = Regex(""""([^"\\]*(\\.[^"\\]*)*)"\s*:""")
    private val stringValRegex = Regex(""":\s*"([^"\\]*(\\.[^"\\]*)*)"""")
    private val numberRegex = Regex(""":\s*(-?\d+(\.\d+)?([eE][+-]?\d+)?)""")
    private val boolNullRegex = Regex(""":\s*(true|false|null)\b""")

    fun highlight(rawJson: String): AnnotatedString {
        val formatted = SecurityValidator.formatPrettyJson(rawJson)
        return buildAnnotatedString {
            append(formatted)
            keyRegex.findAll(formatted).forEach { match ->
                addStyle(
                    SpanStyle(color = CyberCyan, fontWeight = FontWeight.SemiBold),
                    match.range.first,
                    match.range.last
                )
            }
            stringValRegex.findAll(formatted).forEach { match ->
                val colonIndex = formatted.indexOf(':', match.range.first)
                if (colonIndex in match.range) {
                    addStyle(
                        SpanStyle(color = Color(0xFF6EE7B7)),
                        colonIndex + 1,
                        match.range.last + 1
                    )
                }
            }
            numberRegex.findAll(formatted).forEach { match ->
                val colonIndex = formatted.indexOf(':', match.range.first)
                if (colonIndex in match.range) {
                    addStyle(
                        SpanStyle(color = AmberPending),
                        colonIndex + 1,
                        match.range.last + 1
                    )
                }
            }
            boolNullRegex.findAll(formatted).forEach { match ->
                val colonIndex = formatted.indexOf(':', match.range.first)
                if (colonIndex in match.range) {
                    addStyle(
                        SpanStyle(color = VioletModified, fontWeight = FontWeight.Bold),
                        colonIndex + 1,
                        match.range.last + 1
                    )
                }
            }
        }
    }

    fun toCurlCommand(request: InterceptedRequest, redactCredentials: Boolean = false): String {
        val sb = StringBuilder("curl -i -X ${request.method}")
        val safeHeaders = SecurityValidator.redactSensitiveHeaders(request.headers, redactCredentials)
        safeHeaders.forEach { (k, v) ->
            val escaped = v.replace("'", "'\\''")
            sb.append(" \\\n  -H '$k: $escaped'")
        }
        if (!request.body.isNullOrEmpty() && !request.isBinaryBody) {
            val escapedBody = request.body.replace("'", "'\\''")
            sb.append(" \\\n  --data-raw '$escapedBody'")
        }
        sb.append(" \\\n  '${request.url}'")
        return sb.toString()
    }
}

@Composable
fun MethodBadge(method: String, modifier: Modifier = Modifier) {
    val color = when (method.uppercase()) {
        "GET" -> MethodGetColor
        "POST" -> MethodPostColor
        "PUT" -> MethodPutColor
        "DELETE" -> MethodDeleteColor
        "PATCH" -> MethodPatchColor
        else -> CyberCyan
    }
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = method.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun StateBadge(state: RequestState, wasModified: Boolean = false, modifier: Modifier = Modifier) {
    val (label, color) = when {
        state == RequestState.PENDING -> "PENDING" to AmberPending
        state == RequestState.BLOCKED -> "BLOCKED" to CrimsonBlock
        state == RequestState.TIMED_OUT -> "TIMED OUT" to CrimsonBlock
        state == RequestState.FAILED -> "FAILED" to CrimsonBlock
        state == RequestState.CANCELLED -> "CANCELLED" to TextMuted
        wasModified -> "MODIFIED" to VioletModified
        state == RequestState.COMPLETED -> "ALLOWED" to EmeraldAllow
        state == RequestState.APPROVED || state == RequestState.SENT -> "APPROVED" to EmeraldAllow
        else -> state.name to CyberCyan
    }
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun RequestResponseInspectorDialog(
    request: InterceptedRequest,
    onDismiss: () -> Unit,
    onEditPending: ((InterceptedRequest) -> Unit)? = null,
    onAllowPending: ((String) -> Unit)? = null,
    onBlockPending: ((String) -> Unit)? = null,
    onReplayRequest: ((InterceptedRequest) -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Request", "Response", "Security & cURL")
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

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
                .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                .testTag("inspector_dialog"),
            shape = RoundedCornerShape(14.dp),
            color = ObsidianBg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        MethodBadge(request.method)
                        StateBadge(request.state, request.wasModified)
                        Text(
                            text = request.host,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_inspector_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Inspector",
                            tint = TextSecondary
                        )
                    }
                }

                // Subtle hint banner: Tap any row or code block to copy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurfaceElevated)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Tap any field, header, or payload block below to copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Copy All",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val fullDump = buildFullPacketDump(request)
                                copyToClipboard(context, "Full Packet Dump", fullDump)
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (request.state == RequestState.PENDING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AmberPending.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactActionPill(
                            label = "ALLOW",
                            bgColor = EmeraldAllow,
                            textColor = Color.Black,
                            tag = "inspector_allow_button",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onAllowPending?.invoke(request.id)
                                onDismiss()
                            }
                        )
                        CompactActionPill(
                            label = "EDIT & SEND",
                            bgColor = AmberPending,
                            textColor = Color.Black,
                            tag = "inspector_edit_button",
                            modifier = Modifier.weight(1.2f),
                            onClick = { onEditPending?.invoke(request) }
                        )
                        CompactActionPill(
                            label = "BLOCK",
                            bgColor = CrimsonBlock,
                            textColor = Color.White,
                            tag = "inspector_block_button",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onBlockPending?.invoke(request.id)
                                onDismiss()
                            }
                        )
                    }
                }

                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ObsidianSurface,
                    contentColor = CyberCyan,
                    edgePadding = 12.dp
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selectedTab == idx) CyberCyan else TextSecondary,
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
                    when (selectedTab) {
                        0 -> InspectorOverviewTab(request, timeFormatter.format(Date(request.timestamp)))
                        1 -> InspectorRequestTab(request)
                        2 -> InspectorResponseTab(request)
                        3 -> InspectorSecurityCurlTab(request, context, onReplayRequest)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactActionPill(
    label: String,
    bgColor: Color,
    textColor: Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InspectorOverviewTab(request: InterceptedRequest, formattedTime: String) {
    val context = LocalContext.current
    InspectorSectionCard(
        title = "REQUEST METADATA",
        onCopySection = {
            copyToClipboard(
                context,
                "Request Metadata",
                "URL: ${request.url}\nMethod: ${request.method}\nHost: ${request.host}\nPath: ${request.path}\nID: ${request.id}"
            )
        }
    ) {
        KeyValueRow("Full URL", request.url)
        KeyValueRow("HTTP Method", request.method)
        KeyValueRow("Host", request.host)
        KeyValueRow("Path", request.path)
        KeyValueRow("Request ID", request.id)
        KeyValueRow("Timestamp", formattedTime)
        KeyValueRow("Capture Source", request.source.label)
        KeyValueRow("State", request.state.name)
        KeyValueRow("Firewall Policy", request.matchedPolicy?.displayName ?: "Default (Ask Every Time)")
        KeyValueRow("Content-Type", request.contentType ?: "None")
        KeyValueRow("Payload Format", "${request.payloadFormat.name} (${request.bodySizeBytes} bytes)")
        if (request.errorMessage != null) {
            KeyValueRow("Status / Error", request.errorMessage, valueColor = CrimsonBlock)
        }
    }

    if (request.wasModified && request.modificationSummary.isNotEmpty()) {
        InspectorSectionCard(
            title = "MODIFICATIONS APPLIED",
            onCopySection = {
                copyToClipboard(context, "Modifications", request.modificationSummary.joinToString("\n"))
            }
        ) {
            request.modificationSummary.forEach { diff ->
                KeyValueRow("Modification", diff, valueColor = VioletModified)
            }
            if (request.originalUrl != request.url) {
                KeyValueRow("Original URL", request.originalUrl)
            }
            if (request.originalMethod != request.method) {
                KeyValueRow("Original Method", request.originalMethod)
            }
        }
    }

    val resp = request.response
    if (resp != null) {
        InspectorSectionCard(
            title = "RESPONSE SUMMARY",
            onCopySection = {
                copyToClipboard(
                    context,
                    "Response Summary",
                    "HTTP ${resp.statusCode} ${resp.statusMessage}\nContent-Type: ${resp.contentType}\nSize: ${resp.sizeBytes}B\nDuration: ${resp.durationMs}ms"
                )
            }
        ) {
            val statusColor = when (resp.statusCode) {
                in 200..299 -> EmeraldAllow
                in 300..399 -> AmberPending
                else -> CrimsonBlock
            }
            KeyValueRow("HTTP Status", "${resp.statusCode} ${resp.statusMessage}", valueColor = statusColor)
            KeyValueRow("Protocol", resp.protocol)
            KeyValueRow("Content-Type", resp.contentType ?: "unknown")
            KeyValueRow("Payload Size", "${resp.sizeBytes} bytes")
            KeyValueRow("Roundtrip Duration", "${resp.durationMs} ms")
            if (resp.redirectChain.isNotEmpty()) {
                KeyValueRow("Redirect Chain", resp.redirectChain.joinToString("  ➔  "), valueColor = AmberPending)
            }
        }
    }
}

@Composable
private fun InspectorRequestTab(request: InterceptedRequest) {
    val context = LocalContext.current

    InspectorSectionCard(
        title = "QUERY PARAMETERS (${request.queryParams.size})",
        onCopySection = if (request.queryParams.isNotEmpty()) {
            {
                val text = request.queryParams.entries.joinToString("\n") { "${it.key}=${it.value}" }
                copyToClipboard(context, "Query Parameters", text)
            }
        } else null
    ) {
        if (request.queryParams.isEmpty()) {
            Text("No query parameters in URL", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.queryParams.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(
        title = "HTTP HEADERS (${request.headers.size})",
        onCopySection = if (request.headers.isNotEmpty()) {
            {
                val text = request.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                copyToClipboard(context, "Request Headers", text)
            }
        } else null
    ) {
        if (request.headers.isEmpty()) {
            Text("No custom headers attached", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.headers.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(
        title = "COOKIES (${request.cookies.size})",
        onCopySection = if (request.cookies.isNotEmpty()) {
            {
                val text = request.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                copyToClipboard(context, "Cookies", text)
            }
        } else null
    ) {
        if (request.cookies.isEmpty()) {
            Text("No cookies transmitted", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.cookies.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(
        title = "REQUEST PAYLOAD (${request.payloadFormat.name})",
        onCopySection = if (!request.body.isNullOrEmpty() && !request.isBinaryBody) {
            {
                copyToClipboard(context, "Request Payload", request.body)
            }
        } else null
    ) {
        when (request.payloadFormat) {
            PayloadFormat.EMPTY -> {
                Text("Empty request body (0 bytes)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            PayloadFormat.BINARY -> {
                Text(
                    "Binary payload (${request.bodySizeBytes} bytes). Arbitrary binary rendering as text is disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberPending
                )
            }
            PayloadFormat.JSON -> {
                val rawBody = request.body ?: ""
                CodeBlockBox(
                    annotatedText = JsonSyntaxHighlighter.highlight(rawBody),
                    copyLabel = "Request JSON Body",
                    rawCopyText = SecurityValidator.formatPrettyJson(rawBody)
                )
            }
            PayloadFormat.FORM_URL_ENCODED -> {
                val raw = request.body ?: ""
                Text("Decoded Form Fields:", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                Spacer(modifier = Modifier.height(4.dp))
                raw.split("&").forEach { pair ->
                    val idx = pair.indexOf('=')
                    if (idx != -1) {
                        val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                        val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                        KeyValueRow(k, v)
                    } else if (pair.isNotBlank()) {
                        KeyValueRow(pair, "")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Raw URL-Encoded Body (Tap to copy):", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                CodeBlockBox(plainText = raw, copyLabel = "Raw Form Body")
            }
            PayloadFormat.MULTIPART_FORM -> {
                val boundary = request.contentType?.substringAfter("boundary=", "unknown") ?: "unknown"
                KeyValueRow("Multipart Boundary", boundary)
                CodeBlockBox(plainText = request.body ?: "", copyLabel = "Multipart Body")
            }
            PayloadFormat.PLAIN_TEXT -> {
                CodeBlockBox(plainText = request.body ?: "", copyLabel = "Request Body")
            }
        }
    }
}

@Composable
private fun InspectorResponseTab(request: InterceptedRequest) {
    val context = LocalContext.current
    val resp = request.response
    if (resp == null) {
        InspectorSectionCard(title = "NO SERVER RESPONSE") {
            Text(
                text = when (request.state) {
                    RequestState.PENDING -> "Request is paused awaiting your approval. No packet has been sent to the server."
                    RequestState.BLOCKED -> "Request was blocked by INTERCEPTOR before reaching the network."
                    RequestState.TIMED_OUT -> "Approval timed out. Request failed closed without transmission."
                    else -> request.errorMessage ?: "No response captured."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AmberPending
            )
        }
        return
    }

    if (resp.redirectChain.isNotEmpty()) {
        InspectorSectionCard(
            title = "REDIRECT HOPS (${resp.redirectChain.size})",
            onCopySection = {
                copyToClipboard(context, "Redirect Chain", resp.redirectChain.joinToString("\n"))
            }
        ) {
            resp.redirectChain.forEachIndexed { idx, hop ->
                KeyValueRow("Hop #${idx + 1}", hop, valueColor = AmberPending)
            }
        }
    }

    InspectorSectionCard(
        title = "RESPONSE HEADERS (${resp.headers.size})",
        onCopySection = if (resp.headers.isNotEmpty()) {
            {
                val text = resp.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                copyToClipboard(context, "Response Headers", text)
            }
        } else null
    ) {
        if (resp.headers.isEmpty()) {
            Text("No response headers", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            resp.headers.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(
        title = "RESPONSE BODY (${resp.sizeBytes} bytes)",
        onCopySection = if (!resp.isBinary && !resp.body.isNullOrEmpty()) {
            {
                copyToClipboard(context, "Response Body", resp.body)
            }
        } else null
    ) {
        if (resp.isBinary) {
            Text(
                text = resp.body ?: "[Binary Response Stream - ${resp.sizeBytes} bytes]",
                style = MaterialTheme.typography.bodySmall,
                color = AmberPending
            )
        } else {
            val bodyText = resp.body ?: ""
            val isJson = (resp.contentType?.contains("json", ignoreCase = true) == true) ||
                (bodyText.trimStart().startsWith("{") && bodyText.trimEnd().endsWith("}")) ||
                (bodyText.trimStart().startsWith("[") && bodyText.trimEnd().endsWith("]"))

            if (isJson && bodyText.isNotBlank()) {
                CodeBlockBox(
                    annotatedText = JsonSyntaxHighlighter.highlight(bodyText),
                    copyLabel = "Response JSON Body",
                    rawCopyText = SecurityValidator.formatPrettyJson(bodyText)
                )
            } else {
                CodeBlockBox(
                    plainText = bodyText.ifEmpty { "[Empty Response Body]" },
                    copyLabel = "Response Body",
                    rawCopyText = bodyText
                )
            }
        }
    }
}

@Composable
private fun InspectorSecurityCurlTab(
    request: InterceptedRequest,
    context: Context,
    onReplayRequest: ((InterceptedRequest) -> Unit)?
) {
    val resp = request.response
    InspectorSectionCard(title = "TRANSPORT & TLS SECURITY") {
        val isHttps = request.url.startsWith("https://", ignoreCase = true)
        KeyValueRow(
            "Transport Encryption",
            if (isHttps) "HTTPS (TLS Verified)" else "Cleartext HTTP",
            valueColor = if (isHttps) EmeraldAllow else AmberPending
        )
        KeyValueRow("TLS Protocol", resp?.tlsVersion ?: if (isHttps) "System Default TLS 1.3/1.2" else "None (Plaintext)")
        KeyValueRow("Cipher Suite", resp?.cipherSuite ?: "N/A")
        if (resp?.certificateInfo != null) {
            KeyValueRow("Peer Certificate", resp.certificateInfo)
        }
    }

    val redactedCurl = remember(request) { JsonSyntaxHighlighter.toCurlCommand(request, redactCredentials = true) }
    val fullCurl = remember(request) { JsonSyntaxHighlighter.toCurlCommand(request, redactCredentials = false) }

    InspectorSectionCard(
        title = "EXPORT AS cURL",
        onCopySection = { copyToClipboard(context, "Safe cURL", redactedCurl) }
    ) {
        CodeBlockBox(plainText = redactedCurl, copyLabel = "Safe cURL")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactActionPill(
                label = "Copy Safe cURL",
                bgColor = ObsidianSurfaceElevated,
                textColor = CyberCyan,
                tag = "copy_safe_curl_button",
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                onClick = { copyToClipboard(context, "Safe cURL", redactedCurl) }
            )
            CompactActionPill(
                label = "Copy Raw cURL",
                bgColor = ObsidianSurfaceElevated,
                textColor = TextPrimary,
                tag = "copy_raw_curl_button",
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp)),
                onClick = { copyToClipboard(context, "Raw cURL", fullCurl) }
            )
        }
    }

    if (onReplayRequest != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CyberCyan)
                .clickable {
                    onReplayRequest(request)
                    Toast.makeText(context, "Replaying request through pipeline…", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp)
                .testTag("replay_request_button"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Replay, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "REPLAY REQUEST",
                style = MaterialTheme.typography.labelLarge,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun InspectorSectionCard(
    title: String,
    onCopySection: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface, RoundedCornerShape(10.dp))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = CyberCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onCopySection != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianSurfaceElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(6.dp))
                        .clickable(onClick = onCopySection)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy section",
                        tint = CyberCyan,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "Copy Section",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyan
                    )
                }
            }
        }
        content()
    }
}

@Composable
fun KeyValueRow(
    key: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                copyToClipboard(context, key, value)
            }
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Tap to copy $key",
                tint = TextMuted.copy(alpha = 0.65f),
                modifier = Modifier.size(11.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 5.dp),
            color = ObsidianBorder.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun CodeBlockBox(
    plainText: String? = null,
    annotatedText: AnnotatedString? = null,
    copyLabel: String = "Payload",
    rawCopyText: String? = null
) {
    val context = LocalContext.current
    val textToCopy = rawCopyText ?: annotatedText?.text ?: plainText ?: ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF06080D))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
            .clickable {
                if (textToCopy.isNotEmpty()) {
                    copyToClipboard(context, copyLabel, textToCopy)
                }
            }
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TAP BLOCK TO COPY",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy code block",
                tint = CyberCyan,
                modifier = Modifier.size(13.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            if (annotatedText != null) {
                Text(
                    text = annotatedText,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            } else {
                Text(
                    text = plainText ?: "",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
            }
        }
    }
}

private fun buildFullPacketDump(req: InterceptedRequest): String {
    val sb = StringBuilder()
    sb.appendLine("=== INTERCEPTOR PACKET DUMP ===")
    sb.appendLine("${req.method} ${req.url}")
    sb.appendLine("Host: ${req.host}")
    sb.appendLine("State: ${req.state.name}")
    if (req.headers.isNotEmpty()) {
        sb.appendLine("\n--- Request Headers ---")
        req.headers.forEach { (k, v) -> sb.appendLine("$k: $v") }
    }
    if (!req.body.isNullOrEmpty()) {
        sb.appendLine("\n--- Request Body ---")
        sb.appendLine(req.body)
    }
    req.response?.let { resp ->
        sb.appendLine("\n--- Response (${resp.statusCode} ${resp.statusMessage}, ${resp.durationMs}ms) ---")
        resp.headers.forEach { (k, v) -> sb.appendLine("$k: $v") }
        if (!resp.body.isNullOrEmpty()) {
            sb.appendLine("\n--- Response Body ---")
            sb.appendLine(resp.body)
        }
    }
    return sb.toString()
}
