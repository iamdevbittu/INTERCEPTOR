package com.example.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.database.BookmarkEntity
import com.example.proxy.WebViewInterceptorBridge
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

data class BrowserTabState(
    val id: String,
    val title: String,
    val url: String,
    val isSecure: Boolean = url.startsWith("https://")
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    tabs: List<BrowserTabState>,
    activeTabId: String,
    bookmarks: List<BookmarkEntity>,
    strictMode: Boolean,
    pendingCount: Int,
    totalCapturedCount: Int,
    bridge: WebViewInterceptorBridge,
    onNavigateUrl: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onToggleBookmark: (String, String) -> Unit,
    onToggleStrictMode: () -> Unit,
    onOpenTrafficScreen: () -> Unit,
    onExportCurrentPageSnapshot: (String) -> Unit
) {
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressInput by remember(activeTab.url) { mutableStateOf(activeTab.url) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showTabsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val isBookmarked = bookmarks.any { it.url == activeTab.url }
    val isHttps = activeTab.url.startsWith("https://", ignoreCase = true)

    val strictAccentColor by animateColorAsState(
        targetValue = if (strictMode) EmeraldAllow else AmberPending,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "strictAccent"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = (pageProgress / 100f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pageProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("browser_screen")
    ) {
        // Unified Single-Block Premium Top Bar (Safe Status Bar Padding + Omnibox + Compact Action/Toggle Row)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF141722), ObsidianSurface)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Sleek Pill Omnibox + Strict Firewall Toggle Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pill Omnibox containing Security Icon + URL Field + Reload/Go
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(ObsidianBg)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(21.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Security Indicator
                    Box(
                        modifier = Modifier.testTag("security_indicator"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isHttps) "HTTPS Encrypted" else "Unencrypted HTTP",
                            tint = if (isHttps) EmeraldAllow else AmberPending,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Clean Single-Line URL Input
                    BasicTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextPrimary
                        ),
                        cursorBrush = SolidColor(CyberCyan),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                focusManager.clearFocus()
                                val normalized = normalizeUrlInput(addressInput)
                                addressInput = normalized
                                onNavigateUrl(normalized)
                                webViewRef?.loadUrl(normalized)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_address_bar"),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (addressInput.isEmpty()) {
                                    Text(
                                        text = "Search or enter domain…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (addressInput.isNotEmpty() && addressInput != activeTab.url) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear URL",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { addressInput = "" }
                        )
                    }

                    // Inline Reload / Go Button
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable {
                                focusManager.clearFocus()
                                val normalized = normalizeUrlInput(addressInput)
                                if (normalized != activeTab.url) {
                                    addressInput = normalized
                                    onNavigateUrl(normalized)
                                    webViewRef?.loadUrl(normalized)
                                } else {
                                    webViewRef?.reload()
                                }
                            }
                            .testTag("browser_reload_button")
                    )
                }

                // Compact Animated Strict Mode Toggle Pill
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(strictAccentColor.copy(alpha = 0.12f))
                        .border(1.dp, strictAccentColor.copy(alpha = 0.45f), RoundedCornerShape(21.dp))
                        .clickable(onClick = onToggleStrictMode)
                        .padding(horizontal = 12.dp)
                        .testTag("header_strict_mode_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
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

            // Row 2: Compact Single-Line Control & Telemetry Strip (Zero Stretching / Zero Wrapping)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Navigation & Page Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CompactToolbarIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        enabled = canGoBack,
                        tag = "browser_back_button",
                        onClick = { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() }
                    )
                    CompactToolbarIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        enabled = canGoForward,
                        tag = "browser_forward_button",
                        onClick = { if (webViewRef?.canGoForward() == true) webViewRef?.goForward() }
                    )
                    CompactToolbarIcon(
                        icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) CyberCyan else TextSecondary,
                        tag = "browser_bookmark_button",
                        onClick = { onToggleBookmark(activeTab.url, activeTab.title) }
                    )
                    CompactToolbarIcon(
                        icon = Icons.Default.Download,
                        contentDescription = "Save Snapshot",
                        tag = "browser_download_button",
                        onClick = { onExportCurrentPageSnapshot(activeTab.url) }
                    )
                }

                // Right Toggles: Live Packet Counter Badge + Bookmarks Pill + Tabs Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Live Traffic / Pending Pill
                    val hasPending = pendingCount > 0
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (hasPending) AmberPending.copy(alpha = 0.16f)
                                else ObsidianSurfaceElevated
                            )
                            .border(
                                1.dp,
                                if (hasPending) AmberPending.copy(alpha = 0.6f) else ObsidianBorder,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable(onClick = onOpenTrafficScreen)
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("header_pending_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (hasPending) AmberPending else CyberCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = if (hasPending) "$pendingCount Hold" else "$totalCapturedCount Pkts",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasPending) AmberPending else TextSecondary,
                            fontWeight = if (hasPending) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    // Saved Bookmarks Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                            .clickable { showBookmarksDialog = true }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("open_bookmarks_button")
                    ) {
                        Text(
                            text = "Saved (${bookmarks.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }

                    // Tabs Switcher Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { showTabsDialog = true }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("open_tabs_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .border(1.2.dp, CyberCyan, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabs.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Tabs",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Smooth Animated Progress Bar
        AnimatedVisibility(
            visible = pageProgress in 1..99,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = CyberCyan,
                trackColor = ObsidianSurface
            )
        }
        HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.7f), thickness = 0.5.dp)

        // Full-Bleed Chromium WebView
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("browser_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = "${settings.userAgentString} InterceptorBrowser/1.0"

                        addJavascriptInterface(bridge, WebViewInterceptorBridge.JS_BRIDGE_NAME)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                if (!url.isNullOrBlank() && url != addressInput) {
                                    addressInput = url
                                }
                                view?.evaluateJavascript(WebViewInterceptorBridge.JS_INTERCEPTOR_INJECTION, null)
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript(WebViewInterceptorBridge.JS_INTERCEPTOR_INJECTION, null)
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null) return null
                                val urlStr = request.url?.toString() ?: return null
                                if (urlStr.endsWith("/sandbox")) {
                                    return super.shouldInterceptRequest(view, request)
                                }
                                return bridge.interceptNativeWebResource(request)
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                pageProgress = newProgress
                            }
                        }

                        webViewRef = this
                        loadUrl(activeTab.url)
                    }
                },
                update = { view ->
                    if (view.url != activeTab.url && activeTab.url.isNotBlank()) {
                        view.loadUrl(activeTab.url)
                    }
                }
            )
        }
    }

    if (showTabsDialog) {
        TabsSwitcherDialog(
            tabs = tabs,
            activeTabId = activeTabId,
            onSelectTab = { id ->
                onSelectTab(id)
                showTabsDialog = false
            },
            onNewTab = {
                onNewTab()
                showTabsDialog = false
            },
            onCloseTab = onCloseTab,
            onDismiss = { showTabsDialog = false }
        )
    }

    if (showBookmarksDialog) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onSelectBookmark = { url ->
                addressInput = url
                onNavigateUrl(url)
                webViewRef?.loadUrl(url)
                showBookmarksDialog = false
            },
            onDeleteBookmark = { url -> onToggleBookmark(url, "") },
            onDismiss = { showBookmarksDialog = false }
        )
    }
}

@Composable
private fun CompactToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tag: String,
    enabled: Boolean = true,
    tint: Color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.45f),
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(34.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun TabsSwitcherDialog(
    tabs: List<BrowserTabState>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = ObsidianSurface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "OPEN TABS (${tabs.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Button(
                        onClick = onNewTab,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("new_tab_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Tab", style = MaterialTheme.typography.labelSmall, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                tabs.forEach { tab ->
                    val isSelected = tab.id == activeTabId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) CyberCyan.copy(alpha = 0.12f) else ObsidianBg)
                            .border(
                                1.dp,
                                if (isSelected) CyberCyan.copy(alpha = 0.6f) else ObsidianBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectTab(tab.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tab.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (tabs.size > 1) {
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Tab", tint = CrimsonBlock, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<BookmarkEntity>,
    onSelectBookmark: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = ObsidianSurface
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("SAVED BOOKMARKS", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                if (bookmarks.isEmpty()) {
                    Text("No saved bookmarks yet.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                bookmarks.forEach { bm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianBg)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                            .clickable { onSelectBookmark(bm.url) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bm.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1)
                            Text(bm.url, style = MaterialTheme.typography.bodySmall, color = CyberCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(
                            onClick = { onDeleteBookmark(bm.url) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove bookmark", tint = CrimsonBlock, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close", color = TextSecondary)
                }
            }
        }
    }
}

private fun normalizeUrlInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "https://example.com"
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    return if (trimmed.contains(".") && !trimmed.contains(" ")) {
        "https://$trimmed"
    } else {
        "https://duckduckgo.com/?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
}
