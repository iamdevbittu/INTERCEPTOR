package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.BrowserScreen
import com.example.firewall.FirewallRulesScreen
import com.example.interceptor.LiveTrafficScreen
import com.example.network.InterceptedRequest
import com.example.requesteditor.RequestEditorDialog
import com.example.responseinspector.MethodBadge
import com.example.responseinspector.RequestResponseInspectorDialog
import com.example.settings.HistoryAnalyticsScreen
import com.example.settings.SettingsAndSecurityScreen
import com.example.ui.theme.AmberPending
import com.example.ui.theme.CrimsonBlock
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.EmeraldAllow
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

enum class MainNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
) {
    BROWSER("browser", "Browser", Icons.Default.Language, "nav_browser"),
    TRAFFIC("traffic", "Live", Icons.Default.Radar, "nav_traffic"),
    RULES("rules", "Rules", Icons.Default.Shield, "nav_rules"),
    HISTORY("history", "History", Icons.Default.History, "nav_history"),
    SETTINGS("settings", "Settings", Icons.Default.Settings, "nav_settings")
}

class MainActivity : ComponentActivity() {
    private val viewModel: InterceptorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                InterceptorApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun InterceptorApp(viewModel: InterceptorViewModel) {
    var currentDest by remember { mutableStateOf(MainNavDestination.BROWSER) }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val liveTraffic by viewModel.liveTraffic.collectAsStateWithLifecycle()
    val pendingRequests by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val domainRules by viewModel.domainRules.collectAsStateWithLifecycle()
    val ruleHistory by viewModel.ruleHistory.collectAsStateWithLifecycle()
    val strictMode by viewModel.strictMode.collectAsStateWithLifecycle()
    val approvalTimeoutMs by viewModel.approvalTimeoutMs.collectAsStateWithLifecycle()
    val followRedirects by viewModel.followRedirects.collectAsStateWithLifecycle()
    val inspectedRequest by viewModel.inspectedRequest.collectAsStateWithLifecycle()
    val editingRequest by viewModel.editingRequest.collectAsStateWithLifecycle()

    val context = LocalContext.current

    if (currentDest != MainNavDestination.BROWSER) {
        BackHandler {
            currentDest = MainNavDestination.BROWSER
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = ObsidianBg,
        topBar = {
            // BrowserScreen has its own integrated single-block top bar;
            // only show the compact secondary header on non-Browser destinations.
            if (currentDest != MainNavDestination.BROWSER) {
                SecondaryScreenTopBar(
                    title = currentDest.label,
                    strictMode = strictMode,
                    pendingCount = pendingRequests.size,
                    totalCapturedCount = liveTraffic.size,
                    onToggleStrictMode = { viewModel.setStrictMode(!strictMode) },
                    onClickPendingBadge = { currentDest = MainNavDestination.TRAFFIC }
                )
            }
        },
        bottomBar = {
            Column {
                val headPending = pendingRequests.firstOrNull()
                AnimatedVisibility(
                    visible = headPending != null,
                    enter = slideInVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    if (headPending != null) {
                        GlobalPendingApprovalOverlay(
                            pendingRequest = headPending,
                            totalPendingCount = pendingRequests.size,
                            onAllow = { viewModel.allowPendingRequest(headPending.id) },
                            onBlock = { viewModel.blockPendingRequest(headPending.id) },
                            onEditAndSend = { viewModel.openEditorForRequest(headPending) },
                            onInspect = { viewModel.inspectRequest(headPending) }
                        )
                    }
                }

                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 0.5.dp)

                NavigationBar(
                    containerColor = ObsidianSurface,
                    contentColor = CyberCyan,
                    tonalElevation = 0.dp
                ) {
                    MainNavDestination.entries.forEach { dest ->
                        val selected = currentDest == dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentDest = dest },
                            modifier = Modifier.testTag(dest.testTag),
                            icon = {
                                if (dest == MainNavDestination.TRAFFIC && pendingRequests.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = AmberPending,
                                                contentColor = Color.Black
                                            ) {
                                                Text(
                                                    text = pendingRequests.size.toString(),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(dest.icon, contentDescription = dest.label)
                                    }
                                } else {
                                    Icon(dest.icon, contentDescription = dest.label)
                                }
                            },
                            label = {
                                Text(
                                    text = dest.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberCyan,
                                selectedTextColor = CyberCyan,
                                indicatorColor = CyberCyan.copy(alpha = 0.14f),
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentDest,
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
                },
                label = "mainNavTransition"
            ) { targetDest ->
                when (targetDest) {
                    MainNavDestination.BROWSER -> BrowserScreen(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        bookmarks = bookmarks,
                        strictMode = strictMode,
                        pendingCount = pendingRequests.size,
                        totalCapturedCount = liveTraffic.size,
                        bridge = viewModel.webViewBridge,
                        onNavigateUrl = viewModel::navigateActiveTab,
                        onSelectTab = viewModel::selectTab,
                        onNewTab = viewModel::createNewTab,
                        onCloseTab = viewModel::closeTab,
                        onToggleBookmark = viewModel::toggleBookmark,
                        onToggleStrictMode = { viewModel.setStrictMode(!strictMode) },
                        onOpenTrafficScreen = { currentDest = MainNavDestination.TRAFFIC },
                        onExportCurrentPageSnapshot = { url ->
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Interceptor Target URL", url))
                            Toast.makeText(context, "Copied URL snapshot for $url", Toast.LENGTH_SHORT).show()
                        }
                    )

                    MainNavDestination.TRAFFIC -> LiveTrafficScreen(
                        allTraffic = liveTraffic,
                        pendingRequests = pendingRequests,
                        onAllowRequest = viewModel::allowPendingRequest,
                        onBlockRequest = viewModel::blockPendingRequest,
                        onEditRequest = viewModel::openEditorForRequest,
                        onInspectRequest = { viewModel.inspectRequest(it) },
                        onAllowAllPending = viewModel::allowAllPending,
                        onBlockAllPending = viewModel::blockAllPending,
                        onQuickDomainRule = { host, action ->
                            viewModel.saveDomainPolicy(host, action, notes = "Quick rule from Live Traffic")
                            Toast.makeText(context, "Rule applied: $host -> ${action.displayName}", Toast.LENGTH_SHORT).show()
                        }
                    )

                    MainNavDestination.RULES -> FirewallRulesScreen(
                        rules = domainRules,
                        ruleHistory = ruleHistory,
                        strictMode = strictMode,
                        onSaveRule = viewModel::saveDomainPolicy,
                        onDeleteRule = viewModel::deleteDomainPolicy
                    )

                    MainNavDestination.HISTORY -> HistoryAnalyticsScreen(
                        allTraffic = liveTraffic,
                        onInspectRequest = { viewModel.inspectRequest(it) },
                        onReplayRequest = viewModel::replayRequest,
                        onClearHistory = viewModel::clearTrafficHistory
                    )

                    MainNavDestination.SETTINGS -> SettingsAndSecurityScreen(
                        strictMode = strictMode,
                        approvalTimeoutMs = approvalTimeoutMs,
                        followRedirects = followRedirects,
                        sandboxBaseUrl = viewModel.sandboxBaseUrl,
                        onToggleStrictMode = viewModel::setStrictMode,
                        onChangeTimeoutMs = viewModel::setApprovalTimeoutMs,
                        onToggleFollowRedirects = viewModel::setFollowRedirects
                    )
                }
            }
        }
    }

    val currentInspected = inspectedRequest
    if (currentInspected != null) {
        val upToDateReq = liveTraffic.firstOrNull { it.id == currentInspected.id } ?: currentInspected
        RequestResponseInspectorDialog(
            request = upToDateReq,
            onDismiss = { viewModel.inspectRequest(null) },
            onEditPending = { req ->
                viewModel.inspectRequest(null)
                viewModel.openEditorForRequest(req)
            },
            onAllowPending = { id -> viewModel.allowPendingRequest(id) },
            onBlockPending = { id -> viewModel.blockPendingRequest(id) },
            onReplayRequest = { req -> viewModel.replayRequest(req) }
        )
    }

    val currentEditing = editingRequest
    if (currentEditing != null) {
        RequestEditorDialog(
            originalRequest = currentEditing,
            onDismiss = { viewModel.closeEditor() },
            onTransmitEdited = { draft ->
                viewModel.submitEditedRequest(currentEditing.id, draft)
            }
        )
    }
}

@Composable
private fun SecondaryScreenTopBar(
    title: String,
    strictMode: Boolean,
    pendingCount: Int,
    totalCapturedCount: Int,
    onToggleStrictMode: () -> Unit,
    onClickPendingBadge: () -> Unit
) {
    val strictAccentColor by animateColorAsState(
        targetValue = if (strictMode) EmeraldAllow else AmberPending,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "secondaryStrictAccent"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurface)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(strictAccentColor, CircleShape)
                )
                Text(
                    text = "INTERCEPTOR // ${title.uppercase()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pendingCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(AmberPending.copy(alpha = 0.16f))
                            .border(1.dp, AmberPending, RoundedCornerShape(14.dp))
                            .clickable(onClick = onClickPendingBadge)
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                            .testTag("header_pending_badge")
                    ) {
                        Text(
                            text = "$pendingCount Hold",
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberPending,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                } else {
                    Text(
                        text = "$totalCapturedCount Pkts",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(strictAccentColor.copy(alpha = 0.12f))
                        .border(1.dp, strictAccentColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .clickable(onClick = onToggleStrictMode)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("header_strict_mode_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(strictAccentColor, CircleShape)
                    )
                    Text(
                        text = if (strictMode) "STRICT" else "BYPASS",
                        style = MaterialTheme.typography.labelSmall,
                        color = strictAccentColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 0.5.dp)
    }
}

@Composable
private fun GlobalPendingApprovalOverlay(
    pendingRequest: InterceptedRequest,
    totalPendingCount: Int,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
    onEditAndSend: () -> Unit,
    onInspect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A1610), Color(0xFF12131A))
                )
            )
            .border(1.dp, AmberPending.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("global_pending_approval_bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                MethodBadge(pendingRequest.method)
                Text(
                    text = pendingRequest.host,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (totalPendingCount > 1) {
                    Text(
                        text = "+${totalPendingCount - 1} queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberPending,
                        maxLines = 1
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onInspect)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("global_inspect_pending_button")
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Inspect", style = MaterialTheme.typography.labelSmall, color = CyberCyan)
            }
        }

        Text(
            text = pendingRequest.url,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldAllow),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag("global_allow_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("ALLOW", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onEditAndSend,
                colors = ButtonDefaults.buttonColors(containerColor = AmberPending),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1.15f)
                    .height(34.dp)
                    .testTag("global_edit_send_button")
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("EDIT", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onBlock,
                colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlock),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .testTag("global_block_button")
            ) {
                Icon(Icons.Default.Block, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("BLOCK", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
