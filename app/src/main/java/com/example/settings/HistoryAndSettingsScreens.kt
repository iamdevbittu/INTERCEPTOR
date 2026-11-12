package com.example.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.network.InterceptedRequest
import com.example.network.RequestState
import com.example.responseinspector.CodeBlockBox
import com.example.responseinspector.InspectorSectionCard
import com.example.responseinspector.KeyValueRow
import com.example.responseinspector.MethodBadge
import com.example.responseinspector.StateBadge
import com.example.security.SecurityValidator
import com.example.ui.theme.AmberPending
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldAllow
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletModified
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun HistoryAnalyticsScreen(
    allTraffic: List<InterceptedRequest>,
    onInspectRequest: (InterceptedRequest) -> Unit,
    onReplayRequest: (InterceptedRequest) -> Unit,
    onClearHistory: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    var compareLeftId by remember { mutableStateOf<String?>(null) }
    var compareRightId by remember { mutableStateOf<String?>(null) }

    val completedTraffic = remember(allTraffic) {
        allTraffic.filter { it.state != RequestState.PENDING }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("history_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TRAFFIC ARCHIVE & ANALYTICS",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${completedTraffic.size} persisted records in SQLite/Room storage",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val exportJson = buildTrafficExportJson(completedTraffic)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Interceptor Traffic Export", exportJson))
                        Toast.makeText(context, "Exported ${completedTraffic.size} records (credentials redacted) to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export JSON", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onClearHistory,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = CrimsonBlock, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear", style = MaterialTheme.typography.labelSmall, color = CrimsonBlock)
                }
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = ObsidianSurface,
            contentColor = CyberCyan
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Persisted Log (${completedTraffic.size})", style = MaterialTheme.typography.labelMedium) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Analytics & Compare", style = MaterialTheme.typography.labelMedium) }
            )
        }

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (completedTraffic.isEmpty()) {
                    item {
                        Text(
                            "No historical requests recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(completedTraffic, key = { it.id }) { req ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                            .clickable { onInspectRequest(req) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MethodBadge(req.method)
                                StateBadge(req.state, req.wasModified)
                                req.response?.let { resp ->
                                    Text(
                                        text = "${resp.statusCode} (${resp.durationMs}ms)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (resp.statusCode in 200..299) EmeraldAllow else AmberPending
                                    )
                                }
                            }
                            Text(
                                text = req.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { onReplayRequest(req) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(Icons.Default.Replay, contentDescription = "Replay", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Replay", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val allowedCount = completedTraffic.count { it.state == RequestState.COMPLETED }
                val blockedCount = completedTraffic.count { it.state == RequestState.BLOCKED || it.state == RequestState.TIMED_OUT }
                val modifiedCount = completedTraffic.count { it.wasModified }
                val totalBytes = completedTraffic.sumOf { it.response?.sizeBytes ?: 0L }
                val avgLatency = completedTraffic.mapNotNull { it.response?.durationMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L

                InspectorSectionCard(title = "SESSION TELEMETRY BREAKDOWN") {
                    KeyValueRow("Total Completed / Transmitted", allowedCount.toString(), EmeraldAllow)
                    KeyValueRow("Total Blocked / Timed Out (Fail-Closed)", blockedCount.toString(), CrimsonBlock)
                    KeyValueRow("Total Modified Before Transmission", modifiedCount.toString(), VioletModified)
                    KeyValueRow("Total Response Volume", "$totalBytes bytes")
                    KeyValueRow("Mean Roundtrip Latency", "$avgLatency ms")
                }

                InspectorSectionCard(title = "RESPONSE COMPARISON ENGINE") {
                    Text(
                        "Select any two captured requests below to diff their HTTP status, headers, and response payloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val candidates = completedTraffic.take(10)
                    if (candidates.size < 2) {
                        Text(
                            "Capture at least 2 requests to compare responses.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberPending
                        )
                    } else {
                        val left = candidates.firstOrNull { it.id == compareLeftId } ?: candidates.getOrNull(0)
                        val right = candidates.firstOrNull { it.id == compareRightId } ?: candidates.getOrNull(1)
                        if (left != null && right != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PACKET A: ${left.method} ${left.path}", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                                    Text("Status: ${left.response?.statusCode ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                                    CodeBlockBox(plainText = (left.response?.body ?: left.errorMessage ?: "Empty").take(400))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("PACKET B: ${right.method} ${right.path}", style = MaterialTheme.typography.labelSmall, color = VioletModified)
                                    Text("Status: ${right.response?.statusCode ?: "N/A"}", style = MaterialTheme.typography.bodySmall)
                                    CodeBlockBox(plainText = (right.response?.body ?: right.errorMessage ?: "Empty").take(400))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsAndSecurityScreen(
    strictMode: Boolean,
    approvalTimeoutMs: Long,
    followRedirects: Boolean,
    sandboxBaseUrl: String,
    onToggleStrictMode: (Boolean) -> Unit,
    onChangeTimeoutMs: (Long) -> Unit,
    onToggleFollowRedirects: (Boolean) -> Unit
) {
    val disclosures = remember { SecurityValidator.getCapabilityDisclosures() }
    val timeoutOptions = listOf(10_000L to "10s", 30_000L to "30s", 60_000L to "60s", 120_000L to "120s")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "FIREWALL & SECURITY CONFIGURATION",
            style = MaterialTheme.typography.titleMedium,
            color = CyberCyan,
            fontWeight = FontWeight.Bold
        )

        InspectorSectionCard(title = "HUMAN-IN-THE-LOOP INTERCEPTION CONTROLS") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Strict Interception Mode (Zero Silent Transmission)",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        "When enabled, every request to an unknown domain is held in PENDING state until explicitly approved or edited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = strictMode,
                    onCheckedChange = onToggleStrictMode,
                    colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan),
                    modifier = Modifier.testTag("strict_mode_switch")
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = ObsidianBorder)

            Text(
                "Approval Timeout (Fail-Closed Policy)",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                "If a PENDING request is not approved within this window, it transitions to TIMED_OUT and is never transmitted.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeoutOptions.forEach { (ms, label) ->
                    FilterChip(
                        selected = approvalTimeoutMs == ms,
                        onClick = { onChangeTimeoutMs(ms) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                            selectedLabelColor = CyberCyan
                        ),
                        modifier = Modifier.testTag("timeout_chip_$label")
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = ObsidianBorder)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Follow HTTP 3xx Redirects Automatically",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        "Captures and logs intermediate 301/302/307/308 redirect hops inside the Response Inspector.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = followRedirects,
                    onCheckedChange = onToggleFollowRedirects,
                    colors = SwitchDefaults.colors(checkedThumbColor = CyberCyan)
                )
            }
        }

        InspectorSectionCard(title = "HTTPS & CERTIFICATE SECURITY ARCHITECTURE") {
            KeyValueRow(
                "Local Verification Node",
                "$sandboxBaseUrl (Bound strictly to 127.0.0.1 loopback)",
                valueColor = EmeraldAllow
            )
            KeyValueRow(
                "Upstream TLS Verification",
                "Enforced via Android System X509TrustManager (Never globally disabled)",
                valueColor = EmeraldAllow
            )
            KeyValueRow(
                "Certificate Installation Policy",
                "Zero silent CA installation. User-installed CAs require explicit Android Settings > Encryption & Credentials consent.",
                valueColor = CyberCyan
            )
        }

        InspectorSectionCard(title = "HONEST CAPABILITY & LIMITATION MATRIX") {
            disclosures.forEach { item ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.feature,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = item.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.isSupported) EmeraldAllow else AmberPending,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = item.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 6.dp), color = ObsidianBorder.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun buildTrafficExportJson(records: List<InterceptedRequest>): String {
    val arr = JSONArray()
    records.forEach { req ->
        val obj = JSONObject()
        obj.put("id", req.id)
        obj.put("url", req.url)
        obj.put("method", req.method)
        obj.put("state", req.state.name)
        obj.put("wasModified", req.wasModified)
        val safeHeaders = SecurityValidator.redactSensitiveHeaders(req.headers, redact = true)
        obj.put("headers", JSONObject(safeHeaders))
        obj.put("body", req.body ?: "")
        req.response?.let { resp ->
            val rObj = JSONObject()
            rObj.put("statusCode", resp.statusCode)
            rObj.put("durationMs", resp.durationMs)
            rObj.put("sizeBytes", resp.sizeBytes)
            obj.put("response", rObj)
        }
        arr.put(obj)
    }
    return arr.toString(2)
}
