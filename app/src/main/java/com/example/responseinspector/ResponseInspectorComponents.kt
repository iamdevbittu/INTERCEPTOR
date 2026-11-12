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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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

object JsonSyntaxHighlighter {
    private val keyRegex = Regex(""""([^"\\]*(\\.[^"\\]*)*)"\s*:""")
    private val stringValRegex = Regex(""":\s*"([^"\\]*(\\.[^"\\]*)*)"""")
    private val numberRegex = Regex(""":\s*(-?\d+(\.\d+)?([eE][+-]?\d+)?)""")
    private val boolNullRegex = Regex(""":\s*(true|false|null)\b""")

    fun highlight(rawJson: String): AnnotatedString {
        val formatted = SecurityValidator.formatPrettyJson(rawJson)
        return buildAnnotatedString {
            append(formatted)
            // Keys in CyberCyan
            keyRegex.findAll(formatted).forEach { match ->
                addStyle(
                    SpanStyle(color = CyberCyan, fontWeight = FontWeight.SemiBold),
                    match.range.first,
                    match.range.last
                )
            }
            // String values in EmeraldAllow
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
            // Numbers in Amber
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
            // Booleans / null in Violet
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
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StateBadge(state: RequestState, wasModified: Boolean = false, modifier: Modifier = Modifier) {
    val (label, color) = when {
        state == RequestState.PENDING -> "PENDING APPROVAL" to AmberPending
        state == RequestState.BLOCKED -> "BLOCKED" to CrimsonBlock
        state == RequestState.TIMED_OUT -> "TIMED OUT (CLOSED)" to CrimsonBlock
        state == RequestState.FAILED -> "FAILED" to CrimsonBlock
        state == RequestState.CANCELLED -> "CANCELLED" to TextMuted
        wasModified -> "MODIFIED & SENT" to VioletModified
        state == RequestState.COMPLETED -> "APPROVED / 200+" to EmeraldAllow
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
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val tabs = listOf("Overview", "Request", "Response", "Security / cURL")
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                .testTag("inspector_dialog"),
            shape = RoundedCornerShape(12.dp),
            color = ObsidianBg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_inspector_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Inspector",
                            tint = TextSecondary
                        )
                    }
                }

                // If request is currently PENDING, show prominent action bar right inside inspector too!
                if (request.state == RequestState.PENDING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AmberPending.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                onAllowPending?.invoke(request.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldAllow),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_allow_button")
                        ) {
                            Text("ALLOW", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                        }
                        Button(
                            onClick = {
                                onEditPending?.invoke(request)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberPending),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_edit_button")
                        ) {
                            Text("EDIT & SEND", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                        }
                        Button(
                            onClick = {
                                onBlockPending?.invoke(request.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlock),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("inspector_block_button")
                        ) {
                            Text("BLOCK", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        }
                    }
                }

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ObsidianSurface,
                    contentColor = CyberCyan
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selectedTab == idx) CyberCyan else TextSecondary
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
                        .padding(14.dp),
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
private fun InspectorOverviewTab(request: InterceptedRequest, formattedTime: String) {
    InspectorSectionCard(title = "REQUEST METADATA") {
        KeyValueRow("Request ID", request.id)
        KeyValueRow("Full URL", request.url)
        KeyValueRow("HTTP Method", request.method)
        KeyValueRow("Host", request.host)
        KeyValueRow("Path", request.path)
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
        InspectorSectionCard(title = "HUMAN-IN-THE-LOOP MODIFICATIONS APPLIED") {
            request.modificationSummary.forEach { diff ->
                Text(
                    text = "• $diff",
                    style = MaterialTheme.typography.bodySmall,
                    color = VioletModified,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
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
        InspectorSectionCard(title = "RESPONSE SUMMARY") {
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
    InspectorSectionCard(title = "QUERY PARAMETERS (${request.queryParams.size})") {
        if (request.queryParams.isEmpty()) {
            Text("No query parameters in URL", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.queryParams.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(title = "HTTP HEADERS (${request.headers.size})") {
        if (request.headers.isEmpty()) {
            Text("No custom headers attached", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.headers.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(title = "COOKIES (${request.cookies.size})") {
        if (request.cookies.isEmpty()) {
            Text("No cookies transmitted", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            request.cookies.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(title = "REQUEST PAYLOAD (${request.payloadFormat.name})") {
        when (request.payloadFormat) {
            PayloadFormat.EMPTY -> {
                Text("Empty request body (0 bytes)", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            PayloadFormat.BINARY -> {
                Text(
                    "Binary payload (${request.bodySizeBytes} bytes). Arbitrary binary rendering as text is disabled to prevent corruption.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberPending
                )
            }
            PayloadFormat.JSON -> {
                CodeBlockBox(annotatedText = JsonSyntaxHighlighter.highlight(request.body ?: ""))
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
                Text("Raw URL-Encoded Body:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                CodeBlockBox(plainText = raw)
            }
            PayloadFormat.MULTIPART_FORM -> {
                val boundary = request.contentType?.substringAfter("boundary=", "unknown") ?: "unknown"
                KeyValueRow("Multipart Boundary", boundary)
                CodeBlockBox(plainText = request.body ?: "")
            }
            PayloadFormat.PLAIN_TEXT -> {
                CodeBlockBox(plainText = request.body ?: "")
            }
        }
    }
}

@Composable
private fun InspectorResponseTab(request: InterceptedRequest) {
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
        InspectorSectionCard(title = "REDIRECT HOPS (${resp.redirectChain.size})") {
            resp.redirectChain.forEachIndexed { idx, hop ->
                KeyValueRow("Hop #${idx + 1}", hop, valueColor = AmberPending)
            }
        }
    }

    InspectorSectionCard(title = "RESPONSE HEADERS (${resp.headers.size})") {
        if (resp.headers.isEmpty()) {
            Text("No response headers", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        } else {
            resp.headers.forEach { (k, v) ->
                KeyValueRow(k, v)
            }
        }
    }

    InspectorSectionCard(title = "RESPONSE BODY (${resp.sizeBytes} bytes)") {
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
                CodeBlockBox(annotatedText = JsonSyntaxHighlighter.highlight(bodyText))
            } else {
                CodeBlockBox(plainText = bodyText.ifEmpty { "[Empty Response Body]" })
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

    InspectorSectionCard(title = "EXPORT AS cURL (CREDENTIAL-SAFE)") {
        CodeBlockBox(plainText = redactedCurl)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cURL (Redacted)", redactedCurl))
                    Toast.makeText(context, "Copied redacted cURL to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy Safe cURL", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("cURL (Full)", fullCurl))
                    Toast.makeText(context, "Copied full cURL to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy Raw cURL", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    if (onReplayRequest != null) {
        Button(
            onClick = {
                onReplayRequest(request)
                Toast.makeText(context, "Replaying request through interception pipeline...", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("replay_request_button")
        ) {
            Icon(Icons.Default.Replay, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("REPLAY REQUEST THROUGH PIPELINE", style = MaterialTheme.typography.labelLarge, color = Color.Black)
        }
    }
}

@Composable
fun InspectorSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface, RoundedCornerShape(8.dp))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = CyberCyan,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun KeyValueRow(
    key: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = ObsidianBorder.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun CodeBlockBox(
    plainText: String? = null,
    annotatedText: AnnotatedString? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF05080E), RoundedCornerShape(6.dp))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
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
