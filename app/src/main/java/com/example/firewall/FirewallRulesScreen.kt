package com.example.firewall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.database.DomainRuleEntity
import com.example.database.RuleHistoryEntity
import com.example.network.DomainPolicyAction
import com.example.ui.theme.AmberPending
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldAllow
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletModified
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FirewallRulesScreen(
    rules: List<DomainRuleEntity>,
    ruleHistory: List<RuleHistoryEntity>,
    strictMode: Boolean,
    onSaveRule: (domain: String, action: DomainPolicyAction, priority: Int, notes: String) -> Unit,
    onDeleteRule: (String) -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var editingRule by remember { mutableStateOf<DomainRuleEntity?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredRules by remember(rules, searchQuery) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) rules
            else rules.filter {
                it.domainPattern.lowercase().contains(q) ||
                    it.action.displayName.lowercase().contains(q) ||
                    it.notes.lowercase().contains(q)
            }
        }
    }

    val totalHits = remember(rules) { rules.sumOf { it.hitCount } }
    val totalAllowed = remember(rules) { rules.sumOf { it.allowedCount } }
    val totalBlocked = remember(rules) { rules.sumOf { it.blockedCount } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("firewall_rules_screen")
    ) {
        // Firewall Header & Statistics Banner
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DOMAIN FIREWALL ENGINE",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Default policy for unknown domains: ASK EVERY TIME (${if (strictMode) "Strict Fail-Closed" else "Permissive"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = {
                        editingRule = null
                        showCreateDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    modifier = Modifier.testTag("add_domain_rule_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Rule", style = MaterialTheme.typography.labelMedium, color = Color.Black)
                }
            }

            // Domain Statistics Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FirewallStatChip(
                    label = "Active Rules",
                    value = rules.size.toString(),
                    color = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                FirewallStatChip(
                    label = "Rule Hits",
                    value = totalHits.toString(),
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                FirewallStatChip(
                    label = "Auto Allowed",
                    value = totalAllowed.toString(),
                    color = EmeraldAllow,
                    modifier = Modifier.weight(1f)
                )
                FirewallStatChip(
                    label = "Auto Blocked",
                    value = totalBlocked.toString(),
                    color = CrimsonBlock,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("domain_rule_search_input"),
                singleLine = true,
                placeholder = {
                    Text("Search domain pattern (e.g. *.tracker.net, api.example.com)…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan)
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = ObsidianBorder,
                    focusedContainerColor = ObsidianBg,
                    unfocusedContainerColor = ObsidianBg
                )
            )
        }

        TabRow(
            selectedTabIndex = activeTab,
            containerColor = ObsidianSurface,
            contentColor = CyberCyan
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("Domain Policies (${filteredRules.size})", style = MaterialTheme.typography.labelMedium) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text("Rule History (${ruleHistory.size})", style = MaterialTheme.typography.labelMedium) }
            )
        }

        if (activeTab == 0) {
            if (filteredRules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = CyberCyan.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                        Text("No Custom Domain Rules Configured", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            "All unknown domains default to ASK EVERY TIME in Strict Mode. Tap 'New Rule' above to configure Always Allow, Always Block, Allow Once, or Temporary Allow policies.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredRules, key = { it.domainPattern }) { rule ->
                        DomainRuleCard(
                            rule = rule,
                            onEdit = {
                                editingRule = rule
                                showCreateDialog = true
                            },
                            onDelete = { onDeleteRule(rule.domainPattern) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (ruleHistory.isEmpty()) {
                    item {
                        Text(
                            "No rule modifications recorded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                items(ruleHistory, key = { it.id }) { item ->
                    RuleHistoryRow(item)
                }
            }
        }
    }

    if (showCreateDialog) {
        DomainRuleEditorDialog(
            initialRule = editingRule,
            onDismiss = { showCreateDialog = false },
            onSave = { domain, action, priority, notes ->
                onSaveRule(domain, action, priority, notes)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun FirewallStatChip(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(ObsidianBg, RoundedCornerShape(8.dp))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun DomainRuleCard(
    rule: DomainRuleEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val actionColor = when (rule.action) {
        DomainPolicyAction.ALWAYS_ALLOW -> EmeraldAllow
        DomainPolicyAction.ALWAYS_BLOCK -> CrimsonBlock
        DomainPolicyAction.ASK_EVERY_TIME -> AmberPending
        DomainPolicyAction.ALLOW_ONCE -> CyberCyan
        DomainPolicyAction.TEMPORARY_ALLOW -> VioletModified
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface, RoundedCornerShape(10.dp))
            .border(1.dp, actionColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("rule_card_${rule.domainPattern}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(actionColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .border(1.dp, actionColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = rule.action.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = actionColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Priority #${rule.priority}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }

            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Rule", tint = CyberCyan, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = CrimsonBlock, modifier = Modifier.size(16.dp))
                }
            }
        }

        Text(
            text = rule.domainPattern,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )

        if (rule.notes.isNotBlank()) {
            Text(text = rule.notes, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Hits: ${rule.hitCount}  •  Allowed: ${rule.allowedCount}  •  Blocked: ${rule.blockedCount}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            if (rule.expiresAtMillis != null) {
                val remSec = ((rule.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                Text(
                    text = "Expires in ${remSec}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = VioletModified
                )
            }
        }
    }
}

@Composable
private fun RuleHistoryRow(item: RuleHistoryEntity) {
    val timeStr = remember(item.timestamp) {
        SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(item.timestamp))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface, RoundedCornerShape(8.dp))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.domainPattern,
                style = MaterialTheme.typography.titleSmall,
                color = CyberCyan
            )
            Text(text = timeStr, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Text(
            text = "${item.previousAction ?: "NONE"} ➔ ${item.newAction} (Priority ${item.priority})",
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary
        )
        if (item.reason.isNotBlank()) {
            Text(text = item.reason, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DomainRuleEditorDialog(
    initialRule: DomainRuleEntity?,
    onDismiss: () -> Unit,
    onSave: (domain: String, action: DomainPolicyAction, priority: Int, notes: String) -> Unit
) {
    var domain by remember { mutableStateOf(initialRule?.domainPattern ?: "") }
    var selectedAction by remember { mutableStateOf(initialRule?.action ?: DomainPolicyAction.ALWAYS_BLOCK) }
    var priorityText by remember { mutableStateOf((initialRule?.priority ?: 50).toString()) }
    var notes by remember { mutableStateOf(initialRule?.notes ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .testTag("domain_rule_dialog"),
            shape = RoundedCornerShape(14.dp),
            color = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll( androidx.compose.foundation.rememberScrollState() )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (initialRule == null) "CREATE DOMAIN FIREWALL RULE" else "EDIT DOMAIN FIREWALL RULE",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberCyan
                )

                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain or Wildcard (e.g. *.google.com)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rule_domain_input"),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                )

                Text("POLICY ACTION", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DomainPolicyAction.entries.forEach { action ->
                        FilterChip(
                            selected = selectedAction == action,
                            onClick = { selectedAction = action },
                            label = { Text(action.displayName, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyberCyan.copy(alpha = 0.2f),
                                selectedLabelColor = CyberCyan
                            ),
                            modifier = Modifier.testTag("policy_action_${action.name}")
                        )
                    }
                }

                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Priority (Lower = evaluated first, e.g. 10)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Rule Notes / Reason") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = TextSecondary, maxLines = 1)
                    }
                    Button(
                        onClick = {
                            val prio = priorityText.toIntOrNull() ?: 100
                            if (domain.isNotBlank()) {
                                onSave(domain.trim(), selectedAction, prio, notes.trim())
                            }
                        },
                        enabled = domain.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldAllow),
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("save_domain_rule_button")
                    ) {
                        Text("SAVE POLICY", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}
