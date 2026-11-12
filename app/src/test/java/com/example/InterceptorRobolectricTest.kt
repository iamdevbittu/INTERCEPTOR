package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.database.InterceptorDatabase
import com.example.firewall.DomainFirewallEngine
import com.example.interceptor.RequestInterceptorEngine
import com.example.network.DomainPolicyAction
import com.example.network.InterceptedRequest
import com.example.network.LocalSandboxServer
import com.example.network.OkHttpNetworkExecutor
import com.example.network.RequestState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InterceptorRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var db: InterceptorDatabase
    private lateinit var testServer: LocalSandboxServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InterceptorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testServer = LocalSandboxServer()
        testServer.start(0)
    }

    @After
    fun tearDown() {
        db.close()
        testServer.stop()
    }

    @Test
    fun `test 12 - Request history and domain rules persist in Room SQLite database`() = runBlocking {
        val firewall = DomainFirewallEngine(db.domainRuleDao())
        val host = InterceptedRequest.extractHost(testServer.baseUrl)
        firewall.setDomainPolicy(host, DomainPolicyAction.ALWAYS_ALLOW, priority = 5, notes = "Persisted test rule")

        val engine = RequestInterceptorEngine(
            networkExecutor = OkHttpNetworkExecutor(),
            firewallEngine = firewall,
            trafficDao = db.trafficDao()
        )

        val req = InterceptedRequest(
            url = "${testServer.baseUrl}/api/v1/persisted-check",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"persisted":true}"""
        )
        val processed = engine.interceptAndProcess(req)
        assertEquals(RequestState.COMPLETED, processed.state)

        // Verify persistence in SQLite via Room TrafficDao
        val storedRecord = db.trafficDao().getById(req.id)
        assertNotNull(storedRecord)
        assertEquals("POST", storedRecord?.method)
        assertEquals(200, storedRecord?.responseStatusCode)

        // Verify persistence of Domain Rule in Room DomainRuleDao
        val storedRule = db.domainRuleDao().getRuleForDomain(host.lowercase())
        assertNotNull(storedRule)
        assertEquals(DomainPolicyAction.ALWAYS_ALLOW, storedRule?.action)
        assertEquals(1L, storedRule?.hitCount)
    }

    @Test
    fun `verify main navigation tabs and human-in-the-loop packet trigger UI`() {
        composeTestRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Interceptor", context.getString(R.string.app_name))

        // Verify Browser Screen is displayed initially
        composeTestRule.onNodeWithTag("browser_screen").assertIsDisplayed()

        // Navigate to Live Traffic screen
        composeTestRule.onNodeWithTag("nav_traffic").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_traffic_screen").assertIsDisplayed()

        // Navigate to Domain Firewall Rules screen
        composeTestRule.onNodeWithTag("nav_rules").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("firewall_rules_screen").assertIsDisplayed()

        // Navigate to History screen
        composeTestRule.onNodeWithTag("nav_history").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_screen").assertIsDisplayed()

        // Navigate to Settings & Security screen
        composeTestRule.onNodeWithTag("nav_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }
}
