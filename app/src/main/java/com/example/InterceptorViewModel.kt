package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.BrowserTabState
import com.example.database.BookmarkEntity
import com.example.database.DomainRuleEntity
import com.example.database.InterceptorDatabase
import com.example.database.RuleHistoryEntity
import com.example.firewall.DomainFirewallEngine
import com.example.interceptor.RequestInterceptorEngine
import com.example.network.DomainPolicyAction
import com.example.network.InterceptedRequest
import com.example.network.InterceptionSource
import com.example.network.LocalSandboxServer
import com.example.network.OkHttpNetworkExecutor
import com.example.network.RequestEditDraft
import com.example.proxy.WebViewInterceptorBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class InterceptorViewModel(application: Application) : AndroidViewModel(application) {

    private val database = InterceptorDatabase.getInstance(application)
    val sandboxServer = LocalSandboxServer()
    val sandboxBaseUrl: String

    val firewallEngine = DomainFirewallEngine(database.domainRuleDao())
    private val networkExecutor = OkHttpNetworkExecutor()

    val interceptorEngine = RequestInterceptorEngine(
        networkExecutor = networkExecutor,
        firewallEngine = firewallEngine,
        trafficDao = database.trafficDao(),
        initialApprovalTimeoutMs = 30_000L,
        initialStrictMode = true
    )

    private val _tabs = MutableStateFlow<List<BrowserTabState>>(emptyList())
    val tabs: StateFlow<List<BrowserTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow("tab-1")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val webViewBridge = WebViewInterceptorBridge(
        engine = interceptorEngine,
        currentTabIdProvider = { _activeTabId.value }
    )

    val liveTraffic: StateFlow<List<InterceptedRequest>> = interceptorEngine.liveTraffic
    val pendingRequests: StateFlow<List<InterceptedRequest>> = interceptorEngine.pendingRequests
    val domainRules: StateFlow<List<DomainRuleEntity>> = firewallEngine.rulesState

    val ruleHistory: StateFlow<List<RuleHistoryEntity>> =
        database.domainRuleDao().observeRuleHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        database.bookmarkDao().observeBookmarks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _strictMode = MutableStateFlow(true)
    val strictMode: StateFlow<Boolean> = _strictMode.asStateFlow()

    private val _approvalTimeoutMs = MutableStateFlow(30_000L)
    val approvalTimeoutMs: StateFlow<Long> = _approvalTimeoutMs.asStateFlow()

    private val _followRedirects = MutableStateFlow(true)
    val followRedirects: StateFlow<Boolean> = _followRedirects.asStateFlow()

    private val _inspectedRequest = MutableStateFlow<InterceptedRequest?>(null)
    val inspectedRequest: StateFlow<InterceptedRequest?> = _inspectedRequest.asStateFlow()

    private val _editingRequest = MutableStateFlow<InterceptedRequest?>(null)
    val editingRequest: StateFlow<InterceptedRequest?> = _editingRequest.asStateFlow()

    init {
        val port = sandboxServer.start(0)
        sandboxBaseUrl = "http://127.0.0.1:$port"

        val initialTab = BrowserTabState(
            id = "tab-1",
            title = "Example Domain",
            url = "https://example.com"
        )
        _tabs.value = listOf(initialTab)

        viewModelScope.launch(Dispatchers.IO) {
            firewallEngine.loadFromDatabase()
            interceptorEngine.loadPersistedHistory()

            database.bookmarkDao().upsertBookmark(
                BookmarkEntity(
                    url = "https://example.com",
                    title = "Example Domain"
                )
            )
            database.bookmarkDao().upsertBookmark(
                BookmarkEntity(
                    url = "https://httpbin.org/get",
                    title = "HTTPBin Inspector"
                )
            )
        }
    }

    fun navigateActiveTab(url: String) {
        val currentId = _activeTabId.value
        val hostTitle = InterceptedRequest.extractHost(url).ifBlank { "Browser Tab" }
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == currentId) tab.copy(url = url, title = hostTitle) else tab
        }
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun createNewTab() {
        val newId = "tab-${UUID.randomUUID().toString().take(6)}"
        val newTab = BrowserTabState(
            id = newId,
            title = "New Tab #${_tabs.value.size + 1}",
            url = "https://example.com"
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newId
    }

    fun closeTab(tabId: String) {
        val remaining = _tabs.value.filterNot { it.id == tabId }
        if (remaining.isNotEmpty()) {
            _tabs.value = remaining
            if (_activeTabId.value == tabId) {
                _activeTabId.value = remaining.first().id
            }
        }
    }

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val exists = bookmarks.value.any { it.url == url }
            if (exists) {
                database.bookmarkDao().deleteBookmark(url)
            } else {
                database.bookmarkDao().upsertBookmark(
                    BookmarkEntity(
                        url = url,
                        title = title.ifBlank { InterceptedRequest.extractHost(url) }
                    )
                )
            }
        }
    }

    fun allowPendingRequest(requestId: String) {
        interceptorEngine.approveRequest(requestId)
    }

    fun blockPendingRequest(requestId: String) {
        interceptorEngine.blockRequest(requestId, "Rejected by user in human-in-the-loop firewall")
    }

    fun openEditorForRequest(request: InterceptedRequest) {
        _editingRequest.value = request
    }

    fun closeEditor() {
        _editingRequest.value = null
    }

    fun submitEditedRequest(requestId: String, draft: RequestEditDraft) {
        val result = interceptorEngine.editAndSendRequest(requestId, draft)
        if (result.success) {
            _editingRequest.value = null
        }
    }

    fun inspectRequest(request: InterceptedRequest?) {
        _inspectedRequest.value = request
    }

    fun allowAllPending() {
        interceptorEngine.approveAllPending()
    }

    fun blockAllPending() {
        interceptorEngine.blockAllPending()
    }

    fun replayRequest(request: InterceptedRequest) {
        interceptorEngine.replayRequestAsync(request)
    }

    fun saveDomainPolicy(
        domain: String,
        action: DomainPolicyAction,
        priority: Int = 50,
        notes: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            firewallEngine.setDomainPolicy(domain, action, priority, notes)
        }
    }

    fun deleteDomainPolicy(domain: String) {
        viewModelScope.launch(Dispatchers.IO) {
            firewallEngine.removeRule(domain)
        }
    }

    fun setStrictMode(enabled: Boolean) {
        _strictMode.value = enabled
        interceptorEngine.strictInterceptionMode = enabled
    }

    fun setApprovalTimeoutMs(timeoutMs: Long) {
        _approvalTimeoutMs.value = timeoutMs
        interceptorEngine.approvalTimeoutMs = timeoutMs
    }

    fun setFollowRedirects(enabled: Boolean) {
        _followRedirects.value = enabled
        interceptorEngine.followRedirects = enabled
    }

    fun clearTrafficHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            interceptorEngine.clearAllHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        sandboxServer.stop()
    }
}
