package com.example.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.InterceptorViewModel
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
    webViewPool: MutableMap<String, WebView>,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    onNavigateUrl: (String) -> Unit,
    onUpdateTabMetadata: (url: String, title: String?) -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onToggleBookmark: (String, String) -> Unit,
    onToggleStrictMode: () -> Unit,
    onOpenTrafficScreen: () -> Unit,
    onExportCurrentPageSnapshot: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    val existingTabWebView = webViewPool[activeTab.id]
    var addressInput by remember(activeTab.id, activeTab.url) {
        mutableStateOf(existingTabWebView?.url?.takeIf { it.isNotBlank() } ?: activeTab.url)
    }
    var pageProgress by remember { mutableIntStateOf(100) }
    var canGoBack by remember(activeTab.id) { mutableStateOf(existingTabWebView?.canGoBack() ?: false) }
    var canGoForward by remember(activeTab.id) { mutableStateOf(existingTabWebView?.canGoForward() ?: false) }
    var showTabsDialog by remember { mutableStateOf(false) }
    var showBookmarksDialog by remember { mutableStateOf(false) }
    val currentOnShowFileChooser by rememberUpdatedState(onShowFileChooser)

    // Clean up closed tab WebViews when tabs list changes
    DisposableEffect(tabs.map { it.id }) {
        val validIds = tabs.map { it.id }.toSet()
        val removedIds = webViewPool.keys.filter { it !in validIds }
        removedIds.forEach { removedId ->
            webViewPool.remove(removedId)?.destroy()
        }
        onDispose { }
    }

    val activeWebView = webViewPool[activeTab.id]
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

    fun loadUrlInActiveWebView(targetUrl: String) {
        val normalized = normalizeUrlInput(targetUrl)
        addressInput = normalized
        onNavigateUrl(normalized)
        webViewPool[activeTab.id]?.loadUrl(normalized)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .testTag("browser_screen")
    ) {
        // Chrome-Style Flush Top Bar (Sits directly under status bar with zero extra gap)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianSurface)
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Chrome-Style Omnibox Row (Home + Pill Address Bar + Tabs Box + Strict Shield)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Home Button -> www.google.com
                IconButton(
                    onClick = { loadUrlInActiveWebView(InterceptorViewModel.DEFAULT_HOME_URL) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home (Google)",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Chrome-Style Pill Omnibox
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(ObsidianSurfaceElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(19.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier.testTag("security_indicator"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isHttps) "HTTPS Secure" else "HTTP",
                            tint = if (isHttps) EmeraldAllow else AmberPending,
                            modifier = Modifier.size(14.dp)
                        )
                    }

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
                                loadUrlInActiveWebView(addressInput)
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_address_bar"),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (addressInput.isEmpty()) {
                                    Text(
                                        text = "Search Google or type URL",
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
                            contentDescription = "Clear",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { addressInput = "" }
                        )
                    }

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
                                    loadUrlInActiveWebView(normalized)
                                } else {
                                    activeWebView?.reload()
                                }
                            }
                            .testTag("browser_reload_button")
                    )
                }

                // Chrome-Style Tab Counter Box
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showTabsDialog = true }
                        .testTag("open_tabs_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .border(1.6.dp, TextPrimary, RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tabs.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Compact Strict Mode Toggle Pill
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(strictAccentColor.copy(alpha = 0.14f))
                        .border(1.dp, strictAccentColor.copy(alpha = 0.5f), RoundedCornerShape(17.dp))
                        .clickable(onClick = onToggleStrictMode)
                        .padding(horizontal = 10.dp)
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

            // Row 2: Slim Secondary Action Strip (Back, Forward, Bookmark, Save, Saved List, Live Packet Badge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CompactToolbarIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        enabled = canGoBack,
                        tag = "browser_back_button",
                        onClick = { if (activeWebView?.canGoBack() == true) activeWebView.goBack() }
                    )
                    CompactToolbarIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        enabled = canGoForward,
                        tag = "browser_forward_button",
                        onClick = { if (activeWebView?.canGoForward() == true) activeWebView.goForward() }
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
                        contentDescription = "Copy URL Snapshot",
                        tag = "browser_download_button",
                        onClick = { onExportCurrentPageSnapshot(activeTab.url) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val hasPending = pendingCount > 0
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (hasPending) AmberPending.copy(alpha = 0.16f)
                                else ObsidianSurfaceElevated
                            )
                            .border(
                                1.dp,
                                if (hasPending) AmberPending.copy(alpha = 0.6f) else ObsidianBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable(onClick = onOpenTrafficScreen)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("header_pending_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (hasPending) AmberPending else CyberCyan,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = if (hasPending) "$pendingCount Hold" else "$totalCapturedCount Pkts",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasPending) AmberPending else TextSecondary,
                            fontWeight = if (hasPending) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianSurfaceElevated)
                            .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                            .clickable { showBookmarksDialog = true }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("open_bookmarks_button")
                    ) {
                        Text(
                            text = "Bookmarks (${bookmarks.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Smooth Page Progress Indicator
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

        // Persistent WebView Container (Retains WebView instances across tab switches and bottom nav switches!)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val currentTabId = activeTab.id
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("browser_webview"),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { container ->
                    val existingOrNewWebView = webViewPool.getOrPut(currentTabId) {
                        createConfiguredWebView(
                            context = context,
                            initialUrl = activeTab.url,
                            bridge = bridge,
                            onUrlAndTitleChanged = { newUrl, newTitle, back, forward ->
                                addressInput = newUrl
                                canGoBack = back
                                canGoForward = forward
                                onUpdateTabMetadata(newUrl, newTitle)
                            },
                            onProgress = { p -> pageProgress = p },
                            onShowFileChooser = { callback, params ->
                                currentOnShowFileChooser(callback, params)
                            }
                        )
                    }

                    if (container.getChildAt(0) !== existingOrNewWebView) {
                        container.removeAllViews()
                        (existingOrNewWebView.parent as? FrameLayout)?.removeView(existingOrNewWebView)
                        container.addView(
                            existingOrNewWebView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        // Sync toolbar state with newly displayed tab's existing WebView
                        existingOrNewWebView.url?.let { currentUrl ->
                            if (currentUrl.isNotBlank()) {
                                addressInput = currentUrl
                            }
                        }
                        canGoBack = existingOrNewWebView.canGoBack()
                        canGoForward = existingOrNewWebView.canGoForward()
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
                loadUrlInActiveWebView(url)
                showBookmarksDialog = false
            },
            onDeleteBookmark = { url -> onToggleBookmark(url, "") },
            onDismiss = { showBookmarksDialog = false }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredWebView(
    context: android.content.Context,
    initialUrl: String,
    bridge: WebViewInterceptorBridge,
    onUrlAndTitleChanged: (url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgress: (Int) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean
): WebView {
    return WebView(context).apply {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        addJavascriptInterface(bridge, WebViewInterceptorBridge.JS_BRIDGE_NAME)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view?.evaluateJavascript(WebViewInterceptorBridge.JS_INTERCEPTOR_INJECTION, null)
                if (!url.isNullOrBlank()) {
                    onUrlAndTitleChanged(
                        url,
                        view?.title,
                        view?.canGoBack() ?: false,
                        view?.canGoForward() ?: false
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(WebViewInterceptorBridge.JS_INTERCEPTOR_INJECTION, null)
                if (!url.isNullOrBlank()) {
                    onUrlAndTitleChanged(
                        url,
                        view?.title,
                        view?.canGoBack() ?: false,
                        view?.canGoForward() ?: false
                    )
                }
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
                onProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: return
                onUrlAndTitleChanged(
                    currentUrl,
                    title,
                    view.canGoBack(),
                    view.canGoForward()
                )
            }

            // Enables <input type="file"> and window.showOpenFilePicker() file & photo uploads on any website!
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                return onShowFileChooser(filePathCallback, fileChooserParams)
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        loadUrl(initialUrl)
    }
}

@Composable
private fun CompactToolbarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tag: String,
    enabled: Boolean = true,
    tint: Color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.4f),
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(30.dp)
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp)
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
                        modifier = Modifier.testTag("new_tab_button")
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
                Text("BOOKMARKS", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
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
    if (trimmed.isEmpty()) return InterceptorViewModel.DEFAULT_HOME_URL
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    return if (trimmed.contains(".") && !trimmed.contains(" ")) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=${java.net.URLEncoder.encode(trimmed, "UTF-8")}"
    }
}
