package com.example.interceptor

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.network.DomainPolicyAction
import com.example.network.InterceptedRequest
import com.example.network.RequestState
import com.example.responseinspector.MethodBadge
import com.example.responseinspector.StateBadge
import com.example.responseinspector.copyToClipboard
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TrafficStatusFilter(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    ALLOWED("Allowed"),
    BLOCKED("Blocked"),
    MODIFIED("Modified")
}

@Composable
fun LiveTrafficScreen(
    allTraffic: List<InterceptedRequest>,
    pendingRequests: List<InterceptedRequest>,
    onAllowRequest: (String) -> Unit,
    onBlockRequest: (String) -> Unit,
    onEditRequest: (InterceptedRequest) -> Unit,
    onInspectRequest: (InterceptedRequest) -> Unit,
    onAllowAllPending: () -> Unit,
    onBlockAllPending: () -> Unit,
    onQuickDomainRule: (String, DomainPolicyAction) -> Unit
) {
    var statusFilter by remember { mutableStateOf(TrafficStatusFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDomainFilter by remember { mutableStateOf<String?>(null) }

    val availableDomains by remember(allTraffic) {
        derivedStateOf {
            allTraffic.map { it.host }.distinct().sorted()
        }
    }

    val filteredTraffic by remember(allTraffic, statusFilter, searchQuery, selectedDomainFilter) {
        derivedStateOf {
            allTraffic.filter { req ->
                val matchesStatus = when (statusFilter) {
                    TrafficStatusFilter.ALL -> true
                    TrafficStatusFilter.PENDING -> req.state == RequestState.PENDING
                    TrafficStatusFilter.ALLOWED -> req.state == RequestState.COMPLETED ||
                        req.state == RequestState.APPROVED || req.state == RequestState.SENT
                    TrafficStatusFilter.BLOCKED -> req.state == RequestState.BLOCKED ||
                        req.state == RequestState.TIMED_OUT || req.state == RequestState.CANCELLED
                    TrafficStatusFilter.MODIFIED -> req.wasModified
                }
                val matchesDomain = selectedDomainFilter == null || req.host.equals(selectedDomainFilter, ignoreCase = true)
                val q = searchQuery.trim().lowercase()
                val matchesQuery = q.isEmpty() ||
                    req.url.lowercase().contains(q) ||
                    req.host.lowercase().contains(q) ||
                    req.method.lowercase().contains(q) ||
                    (req.body?.lowercase()?.contains(q) == true) ||
                    req.headers.any { (k, v) -> k.lowercase().contains(q) || v.lowercase().contains(q) }

                matchesStatus && matchesDomain && matchesQuery
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("live_traffic_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("traffic_search_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text("Filter by URL, domain, method, header, or body…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrafficStatusFilter.entries.forEach { filter ->
                    val count = when (filter) {
                        TrafficStatusFilter.ALL -> allTraffic.size
                        TrafficStatusFilter.PENDING -> pendingRequests.size
                        TrafficStatusFilter.ALLOWED -> allTraffic.count {
                            it.state == RequestState.COMPLETED || it.state == RequestState.APPROVED || it.state == RequestState.SENT
                        }
                        TrafficStatusFilter.BLOCKED -> allTraffic.count {
                            it.state == RequestState.BLOCKED || it.state == RequestState.TIMED_OUT
                        }
                        TrafficStatusFilter.MODIFIED -> allTraffic.count { it.wasModified }
                    }
                    FilterChip(
                        selected = statusFilter == filter,
                        onClick = { statusFilter = filter },
                        label = {
                            Text("${filter.label} ($count)", style = MaterialTheme.typography.labelSmall)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberCyan.copy(alpha = 0.16f),
                            selectedLabelColor = CyberCyan
                        ),
                        modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}")
                    )
                }
            }

            if (availableDomains.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    DomainChip(
                        label = "All Hosts",
                        selected = selectedDomainFilter == null,
                        onClick = { selectedDomainFilter = null }
                    )
                    availableDomains.forEach { domain ->
                        DomainChip(
                            label = domain,
                            selected = selectedDomainFilter == domain,
                            onClick = {
                                selectedDomainFilter = if (selectedDomainFilter == domain) null else domain
                            }
                        )
                    }
                }
            }
        }

        if (pendingRequests.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AmberPending.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pendingRequests.size} Pending Approval",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberPending,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EmeraldAllow)
                            .clickable(onClick = onAllowAllPending)
                            .padding(horizontal = 12.dp)
                            .testTag("allow_all_pending_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Allow All", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CrimsonBlock)
                            .clickable(onClick = onBlockAllPending)
                            .padding(horizontal = 12.dp)
                            .testTag("block_all_pending_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Block All", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 0.5.dp)

        if (filteredTraffic.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Radar,
                        contentDescription = null,
                        tint = CyberCyan.copy(alpha = 0.45f),
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "No Network Packets Matching Filter",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Browse any website in the Browser tab to inspect, copy, modify, or block live HTTP and HTTPS requests in real time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("traffic_lazy_column"),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTraffic, key = { it.id }) { req ->
                    TrafficRequestCard(
                        request = req,
                        onAllow = { onAllowRequest(req.id) },
                        onBlock = { onBlockRequest(req.id) },
                        onEdit = { onEditRequest(req) },
                        onInspect = { onInspectRequest(req) },
                        onQuickDomainRule = onQuickDomainRule
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) CyberCyan.copy(alpha = 0.16f) else ObsidianBg)
            .border(1.dp, if (selected) CyberCyan else ObsidianBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) CyberCyan else TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
fun TrafficRequestCard(
    request: InterceptedRequest,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
    onEdit: () -> Unit,
    onInspect: () -> Unit,
    onQuickDomainRule: (String, DomainPolicyAction) -> Unit
) {
    val context = LocalContext.current
    val isPending = request.state == RequestState.PENDING
    val borderColor = when {
        isPending -> AmberPending.copy(alpha = 0.8f)
        request.state == RequestState.BLOCKED || request.state == RequestState.TIMED_OUT -> CrimsonBlock.copy(alpha = 0.55f)
        request.wasModified -> VioletModified.copy(alpha = 0.65f)
        else -> ObsidianBorder
    }

    val timeStr = remember(request.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(request.timestamp))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPending) Color(0xFF18140D) else ObsidianSurface)
            .border(if (isPending) 1.2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onInspect() }
            .padding(12.dp)
            .testTag("traffic_card_${request.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MethodBadge(request.method)
                StateBadge(request.state, request.wasModified)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tap-to-copy URL & response directly from card
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianSurfaceElevated)
                        .clickable {
                            val textToCopy = if (!request.response?.body.isNullOrBlank()) {
                                "${request.method} ${request.url}\n\nResponse (${request.response?.statusCode}):\n${request.response?.body}"
                            } else if (!request.body.isNullOrBlank()) {
                                "${request.method} ${request.url}\n\nRequest Body:\n${request.body}"
                            } else {
                                request.url
                            }
                            copyToClipboard(context, "Packet Data", textToCopy)
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy packet data",
                        tint = CyberCyan,
                        modifier = Modifier.size(11.dp)
                    )
                    Text("Copy", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = request.host,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = request.url,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${request.headers.size} hdrs • ${request.bodySizeBytes}B (${request.payloadFormat.name})",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            request.response?.let { resp ->
                val statusColor = if (resp.statusCode in 200..299) EmeraldAllow else AmberPending
                Text(
                    text = "HTTP ${resp.statusCode} • ${resp.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (request.wasModified && request.modificationSummary.isNotEmpty()) {
            Text(
                text = "Modified: ${request.modificationSummary.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = VioletModified
            )
        }

        if (request.errorMessage != null) {
            Text(
                text = request.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = CrimsonBlock
            )
        }

        if (isPending) {
            HorizontalDivider(color = AmberPending.copy(alpha = 0.25f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrafficActionButton(
                    label = "ALLOW",
                    icon = Icons.Default.CheckCircle,
                    bgColor = EmeraldAllow,
                    textColor = Color.Black,
                    tag = "card_allow_${request.id}",
                    modifier = Modifier.weight(1f),
                    onClick = onAllow
                )
                TrafficActionButton(
                    label = "EDIT",
                    icon = Icons.Default.Edit,
                    bgColor = AmberPending,
                    textColor = Color.Black,
                    tag = "card_edit_${request.id}",
                    modifier = Modifier.weight(1f),
                    onClick = onEdit
                )
                TrafficActionButton(
                    label = "BLOCK",
                    icon = Icons.Default.Block,
                    bgColor = CrimsonBlock,
                    textColor = Color.White,
                    tag = "card_block_${request.id}",
                    modifier = Modifier.weight(1f),
                    onClick = onBlock
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, EmeraldAllow.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .clickable { onQuickDomainRule(request.host, DomainPolicyAction.ALWAYS_ALLOW) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Trust Host", style = MaterialTheme.typography.labelSmall, color = EmeraldAllow)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, CrimsonBlock.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .clickable { onQuickDomainRule(request.host, DomainPolicyAction.ALWAYS_BLOCK) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Block Host", style = MaterialTheme.typography.labelSmall, color = CrimsonBlock)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onInspect() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Inspect", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
                }
            }
        }
    }
}

@Composable
private fun TrafficActionButton(
    label: String,
    icon: ImageVector,
    bgColor: Color,
    textColor: Color,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = textColor, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
