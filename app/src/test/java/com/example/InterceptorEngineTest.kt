package com.example

import com.example.firewall.DomainFirewallEngine
import com.example.interceptor.RequestInterceptorEngine
import com.example.network.DomainPolicyAction
import com.example.network.InterceptedRequest
import com.example.network.LocalSandboxServer
import com.example.network.OkHttpNetworkExecutor
import com.example.network.RequestEditDraft
import com.example.network.RequestState
import com.example.security.SecurityValidator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Automated Quality Assurance Suite (Section 14) using a real localhost TCP HTTP test server
 * ([LocalSandboxServer]) to verify actual network transmission behavior.
 */
class InterceptorEngineTest {

    private lateinit var testServer: LocalSandboxServer
    private lateinit var firewallEngine: DomainFirewallEngine
    private lateinit var networkExecutor: OkHttpNetworkExecutor
    private lateinit var interceptorEngine: RequestInterceptorEngine

    @Before
    fun setUp() {
        testServer = LocalSandboxServer()
        testServer.start(0)
        firewallEngine = DomainFirewallEngine(ruleDao = null)
        networkExecutor = OkHttpNetworkExecutor(connectTimeoutSeconds = 5, readTimeoutSeconds = 5)
        interceptorEngine = RequestInterceptorEngine(
            networkExecutor = networkExecutor,
            firewallEngine = firewallEngine,
            trafficDao = null,
            initialApprovalTimeoutMs = 5_000L,
            initialStrictMode = true
        )
    }

    @After
    fun tearDown() {
        testServer.stop()
    }

    @Test
    fun `test 1 - GET request pauses in PENDING and only reaches server after approval`() = runBlocking {
        val targetUrl = "${testServer.baseUrl}/api/v1/check?mode=test"
        val req = InterceptedRequest(url = targetUrl, method = "GET")

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        // Wait until request enters PENDING queue
        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }
        assertEquals(1, interceptorEngine.pendingRequests.value.size)

        // Fundamental Rule: A request marked PENDING must NOT reach the test server
        assertEquals(0, testServer.receivedRequests.size)

        // Now approve the request
        val approvedOk = interceptorEngine.approveRequest(req.id)
        assertTrue(approvedOk)

        val finalReq = deferredResult.await()
        assertEquals(RequestState.COMPLETED, finalReq.state)
        assertNotNull(finalReq.response)
        assertEquals(200, finalReq.response?.statusCode)

        // Verify the request actually reached the test server after approval
        assertEquals(1, testServer.receivedRequests.size)
        assertEquals("GET", testServer.receivedRequests[0].method)
        assertEquals("/api/v1/check?mode=test", testServer.receivedRequests[0].path)
    }

    @Test
    fun `test 2 - POST request approval transmits payload to test server`() = runBlocking {
        val payload = """{"action":"login","user":"alice"}"""
        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/login",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = payload
        )

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }
        assertEquals(0, testServer.receivedRequests.size)

        interceptorEngine.approveRequest(req.id)
        val completed = deferredResult.await()

        assertEquals(RequestState.COMPLETED, completed.state)
        assertEquals(1, testServer.receivedRequests.size)
        assertEquals("POST", testServer.receivedRequests[0].method)
        assertEquals(payload, testServer.receivedRequests[0].body)
    }

    @Test
    fun `test 3 - JSON body modification transmits exact edited JSON to test server`() = runBlocking {
        val originalJson = """{"role":"guest","elevated":false}"""
        val modifiedJson = """{"role":"super_admin","elevated":true}"""

        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/privilege",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = originalJson
        )

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }
        assertEquals(0, testServer.receivedRequests.size)

        val draft = RequestEditDraft(
            url = req.url,
            method = "POST",
            headers = req.headers,
            queryParams = req.queryParams,
            cookies = req.cookies,
            body = modifiedJson
        )
        val editResult = interceptorEngine.editAndSendRequest(req.id, draft)
        assertTrue(editResult.success)

        val completed = deferredResult.await()
        assertEquals(RequestState.COMPLETED, completed.state)
        assertTrue(completed.wasModified)
        assertEquals(modifiedJson, completed.body)

        // Verify server received the MODIFIED JSON body and NOT the original
        assertEquals(1, testServer.receivedRequests.size)
        assertEquals(modifiedJson, testServer.receivedRequests[0].body)
    }

    @Test
    fun `test 4 - Header and Cookie modification arrives at test server with edited values`() = runBlocking {
        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/headers",
            method = "GET",
            headers = mapOf("X-Original-Header" to "initial")
        )

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }

        val draft = RequestEditDraft(
            url = req.url,
            method = "GET",
            headers = mapOf("X-Custom-Firewall-Token" to "verified-999"),
            queryParams = emptyMap(),
            cookies = mapOf("session_mode" to "hardened"),
            body = null
        )
        assertTrue(interceptorEngine.editAndSendRequest(req.id, draft).success)

        val completed = deferredResult.await()
        assertEquals(RequestState.COMPLETED, completed.state)
        assertEquals(1, testServer.receivedRequests.size)

        val serverHeaders = testServer.receivedRequests[0].headers
        assertEquals("verified-999", serverHeaders["X-Custom-Firewall-Token"])
        assertEquals("session_mode=hardened", serverHeaders["Cookie"])
        assertFalse(serverHeaders.containsKey("X-Original-Header"))
    }

    @Test
    fun `test 5 - URL and HTTP Method modification arrives at updated endpoint`() = runBlocking {
        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/old-endpoint?env=dev",
            method = "GET"
        )

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }

        val newUrl = "${testServer.baseUrl}/api/v2/upgraded-endpoint"
        val draft = RequestEditDraft(
            url = newUrl,
            method = "PUT",
            headers = mapOf("Content-Type" to "text/plain"),
            queryParams = mapOf("env" to "production", "verified" to "1"),
            cookies = emptyMap(),
            body = "updated-state"
        )
        assertTrue(interceptorEngine.editAndSendRequest(req.id, draft).success)

        val completed = deferredResult.await()
        assertEquals(RequestState.COMPLETED, completed.state)
        assertEquals(1, testServer.receivedRequests.size)
        assertEquals("PUT", testServer.receivedRequests[0].method)
        assertEquals("/api/v2/upgraded-endpoint?env=production&verified=1", testServer.receivedRequests[0].path)
    }

    @Test
    fun `test 6 - Blocked request NEVER reaches the test server`() = runBlocking {
        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/telemetry-exfil",
            method = "POST",
            body = "secret_data"
        )

        val deferredResult = async {
            interceptorEngine.interceptAndProcess(req)
        }

        withTimeoutWait { interceptorEngine.pendingRequests.value.isNotEmpty() }
        assertTrue(interceptorEngine.blockRequest(req.id, "Explicitly blocked in test"))

        val blocked = deferredResult.await()
        assertEquals(RequestState.BLOCKED, blocked.state)

        // CRITICAL QA RULE: A request marked BLOCKED must never reach the test server
        assertEquals(0, testServer.receivedRequests.size)
    }

    @Test
    fun `test 7 - Approval timeout fails closed (TIMED_OUT) and never reaches test server`() = runBlocking {
        interceptorEngine.approvalTimeoutMs = 150L
        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/unattended-request",
            method = "GET"
        )

        val result = interceptorEngine.interceptAndProcess(req)
        assertEquals(RequestState.TIMED_OUT, result.state)

        // CRITICAL QA RULE: Timed-out request must fail closed and never be transmitted
        assertEquals(0, testServer.receivedRequests.size)
    }

    @Test
    fun `test 8 - Domain allow rules (ALWAYS_ALLOW and ALLOW_ONCE) auto-approve as configured`() = runBlocking {
        val host = InterceptedRequest.extractHost(testServer.baseUrl)
        firewallEngine.setDomainPolicy(host, DomainPolicyAction.ALLOW_ONCE, priority = 10)

        // First request should be automatically allowed by ALLOW_ONCE without blocking on user approval
        val req1 = InterceptedRequest(url = "${testServer.baseUrl}/once-1", method = "GET")
        val res1 = interceptorEngine.interceptAndProcess(req1)
        assertEquals(RequestState.COMPLETED, res1.state)
        assertEquals(1, testServer.receivedRequests.size)

        // Second request to same host should revert to ASK_EVERY_TIME because ALLOW_ONCE was consumed!
        interceptorEngine.approvalTimeoutMs = 120L
        val req2 = InterceptedRequest(url = "${testServer.baseUrl}/once-2", method = "GET")
        val res2 = interceptorEngine.interceptAndProcess(req2)
        assertEquals(RequestState.TIMED_OUT, res2.state)
        assertEquals(1, testServer.receivedRequests.size)

        // Now set ALWAYS_ALLOW
        firewallEngine.setDomainPolicy(host, DomainPolicyAction.ALWAYS_ALLOW, priority = 10)
        val req3 = InterceptedRequest(url = "${testServer.baseUrl}/always-3", method = "GET")
        val res3 = interceptorEngine.interceptAndProcess(req3)
        assertEquals(RequestState.COMPLETED, res3.state)
        assertEquals(2, testServer.receivedRequests.size)
    }

    @Test
    fun `test 9 - Domain block rules (ALWAYS_BLOCK) immediately block and never reach server`() = runBlocking {
        val host = InterceptedRequest.extractHost(testServer.baseUrl)
        firewallEngine.setDomainPolicy(host, DomainPolicyAction.ALWAYS_BLOCK, priority = 1)

        val req = InterceptedRequest(url = "${testServer.baseUrl}/blocked-by-policy", method = "GET")
        val res = interceptorEngine.interceptAndProcess(req)

        assertEquals(RequestState.BLOCKED, res.state)
        assertEquals(0, testServer.receivedRequests.size)
    }

    @Test
    fun `test 10 - Security and input validation enforces HTTPS schemes, header CRLF protection, and JSON syntax`() {
        assertFalse(SecurityValidator.validateUrl("javascript:alert(1)").isValid)
        assertFalse(SecurityValidator.validateUrl("file:///etc/passwd").isValid)
        assertTrue(SecurityValidator.validateUrl("https://example.com/api").isValid)

        val crlfHeaders = mapOf("X-Evil" to "val\r\nInjected-Header: pwn")
        assertFalse(SecurityValidator.validateHeaders(crlfHeaders).isValid)

        assertFalse(SecurityValidator.validateJsonSyntax("{broken_json:").isValid)
        assertTrue(SecurityValidator.validateJsonSyntax("""{"valid": true, "count": 42}""").isValid)
    }

    @Test
    fun `test 11 - Redirect behavior captures 302 redirect chain and reaches final destination`() = runBlocking {
        val host = InterceptedRequest.extractHost(testServer.baseUrl)
        firewallEngine.setDomainPolicy(host, DomainPolicyAction.ALWAYS_ALLOW, priority = 10)

        val req = InterceptedRequest(url = "${testServer.baseUrl}/redirect-step-1", method = "GET")
        val completed = interceptorEngine.interceptAndProcess(req)

        assertEquals(RequestState.COMPLETED, completed.state)
        assertNotNull(completed.response)
        assertEquals(200, completed.response?.statusCode)
        assertTrue(completed.response!!.redirectChain.isNotEmpty())
        assertTrue(completed.response!!.redirectChain.first().contains("/api/v1/redirect-final"))
        assertEquals(2, testServer.receivedRequests.size)
    }

    @Test
    fun `test 13 - Default home screen www_google_com and Google asset domains are pre-whitelisted as ALWAYS_ALLOW`() = runBlocking {
        val defaultFirewall = DomainFirewallEngine(ruleDao = null, seedDefaultGoogleAllowRules = true)
        assertEquals("https://www.google.com", InterceptorViewModel.DEFAULT_HOME_URL)
        assertEquals(DomainPolicyAction.ALWAYS_ALLOW, defaultFirewall.evaluateDomain("www.google.com"))
        assertEquals(DomainPolicyAction.ALWAYS_ALLOW, defaultFirewall.evaluateDomain("apis.google.com"))
        assertEquals(DomainPolicyAction.ALWAYS_ALLOW, defaultFirewall.evaluateDomain("www.gstatic.com"))
    }

    private suspend fun withTimeoutWait(predicate: () -> Boolean) {
        var elapsed = 0L
        while (!predicate() && elapsed < 2500L) {
            delay(20L)
            elapsed += 20L
        }
    }
}

