package com.example.network

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class RecordedServerRequest(
    val method: String,
    val path: String,
    val rawRequestLine: String,
    val headers: Map<String, String>,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * A real localhost (127.0.0.1) HTTP/1.1 loopback server used both by:
 * 1. Automated JUnit/Robolectric integration tests to verify that PENDING/BLOCKED requests
 *    never reach the server and APPROVED/MODIFIED requests arrive with exact edited fields.
 * 2. The in-app Browser Sandbox Test Bench so users can trigger real live HTTP traffic
 *    (GET, POST JSON, Auth headers, Redirects, Cookies) without relying on external internet uptime.
 *
 * Strictly bound to 127.0.0.1 per security requirement 11 ("Keep proxy/test listeners bound to localhost").
 */
class LocalSandboxServer {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    val receivedRequests = CopyOnWriteArrayList<RecordedServerRequest>()

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        if (running.get() && serverSocket != null && !serverSocket!!.isClosed) {
            return serverSocket!!.localPort
        }
        val socket = ServerSocket(preferredPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running.set(true)
        val pool = Executors.newCachedThreadPool()
        executor = pool
        pool.submit {
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    pool.submit { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
    }

    fun clearRecordedRequests() {
        receivedRequests.clear()
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val input = s.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val fullPath = parts.getOrNull(1) ?: "/"

            val headers = LinkedHashMap<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: break
                if (l.isEmpty()) break
                val colon = l.indexOf(':')
                if (colon > 0) {
                    val k = l.substring(0, colon).trim()
                    val v = l.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            val contentLength = headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.toIntOrNull() ?: 0

            val bodyChars = CharArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val r = reader.read(bodyChars, totalRead, contentLength - totalRead)
                if (r == -1) break
                totalRead += r
            }
            val bodyString = if (totalRead > 0) String(bodyChars, 0, totalRead) else ""

            val recorded = RecordedServerRequest(
                method = method,
                path = fullPath,
                rawRequestLine = requestLine,
                headers = headers,
                body = bodyString
            )
            receivedRequests.add(recorded)

            val output = s.getOutputStream()
            when {
                fullPath.startsWith("/redirect-step-1") -> {
                    val redirectTarget = "$baseUrl/api/v1/redirect-final?hop=completed"
                    val respBody = """{"status":"redirecting","next":"$redirectTarget"}"""
                    val responseBytes = buildHttpResponse(
                        status = "302 Found",
                        contentType = "application/json",
                        extraHeaders = mapOf(
                            "Location" to redirectTarget,
                            "X-Interceptor-Sandbox" to "127.0.0.1"
                        ),
                        body = respBody
                    )
                    output.write(responseBytes)
                }
                fullPath == "/" || fullPath.startsWith("/sandbox") -> {
                    val html = buildSandboxHtml(baseUrl)
                    val responseBytes = buildHttpResponse(
                        status = "200 OK",
                        contentType = "text/html; charset=utf-8",
                        extraHeaders = mapOf("X-Sandbox-Mode" to "Interactive"),
                        body = html
                    )
                    output.write(responseBytes)
                }
                else -> {
                    // Echo back full details of the received request in JSON so user & tests can verify modifications
                    val headersJson = headers.entries.joinToString(",") { (k, v) ->
                        "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
                    }
                    val respJson = """
                        {
                          "server": "INTERCEPTOR-Local-Verify-Node/1.0",
                          "verified_transmission": true,
                          "received_method": "${escapeJson(method)}",
                          "received_path": "${escapeJson(fullPath)}",
                          "received_headers": {$headersJson},
                          "received_body": ${if (bodyString.trim().startsWith("{") && bodyString.trim().endsWith("}")) bodyString else "\"${escapeJson(bodyString)}\""},
                          "timestamp": ${recorded.timestamp}
                        }
                    """.trimIndent()
                    val responseBytes = buildHttpResponse(
                        status = "200 OK",
                        contentType = "application/json; charset=utf-8",
                        extraHeaders = mapOf(
                            "X-Server-Received-Method" to method,
                            "X-Server-Received-Path" to fullPath,
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Headers" to "*",
                            "Access-Control-Allow-Methods" to "GET, POST, PUT, PATCH, DELETE, OPTIONS"
                        ),
                        body = respJson
                    )
                    output.write(responseBytes)
                }
            }
            output.flush()
        }
    }

    private fun buildHttpResponse(
        status: String,
        contentType: String,
        extraHeaders: Map<String, String>,
        body: String
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${bodyBytes.size}\r\n")
        sb.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) ->
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        val headerBytes = sb.toString().toByteArray(Charsets.UTF_8)
        return headerBytes + bodyBytes
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    companion object {
        fun buildSandboxHtml(baseUrl: String): String = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <title>INTERCEPTOR // Live Network Test Bench</title>
              <style>
                * { box-sizing: border-box; }
                body {
                  margin: 0; padding: 16px;
                  background: #080C14; color: #F1F5F9;
                  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace;
                }
                .header {
                  border: 1px solid #00F0FF44;
                  background: linear-gradient(135deg, #0F1623 0%, #172033 100%);
                  padding: 14px; border-radius: 10px; margin-bottom: 16px;
                }
                .badge {
                  display: inline-block; padding: 3px 8px;
                  font-size: 11px; font-weight: bold;
                  color: #00F0FF; background: #00F0FF1A;
                  border: 1px solid #00F0FF55; border-radius: 4px;
                  margin-bottom: 6px;
                }
                h1 { margin: 4px 0 6px 0; font-size: 18px; color: #FFFFFF; }
                p { margin: 0; font-size: 13px; color: #94A3B8; line-height: 1.45; }
                .grid { display: grid; grid-template-columns: 1fr; gap: 10px; margin-bottom: 16px; }
                .btn {
                  display: flex; flex-direction: column; align-items: flex-start;
                  background: #0F1623; border: 1px solid #23304A;
                  color: #F1F5F9; padding: 12px; border-radius: 8px;
                  cursor: pointer; text-align: left; width: 100%;
                }
                .btn:active { border-color: #00F0FF; background: #172033; }
                .btn-top { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; width: 100%; }
                .method {
                  font-size: 11px; font-weight: bold; padding: 2px 6px; border-radius: 4px;
                }
                .get { background: #38BDF822; color: #38BDF8; border: 1px solid #38BDF866; }
                .post { background: #34D39922; color: #34D399; border: 1px solid #34D39966; }
                .put { background: #FBBF2422; color: #FBBF24; border: 1px solid #FBBF2466; }
                .del { background: #F8717122; color: #F87171; border: 1px solid #F8717166; }
                .endpoint { font-size: 13px; font-weight: bold; color: #E2E8F0; }
                .desc { font-size: 11px; color: #94A3B8; }
                .console {
                  background: #05080E; border: 1px solid #23304A;
                  border-radius: 8px; padding: 12px; font-size: 12px;
                  white-space: pre-wrap; word-break: break-all;
                  color: #10B981; min-height: 140px;
                }
                .console-title {
                  font-size: 11px; color: #64748B; text-transform: uppercase;
                  margin-bottom: 6px; font-weight: bold;
                }
              </style>
            </head>
            <body>
              <div class="header">
                <span class="badge">LIVE LOOPBACK TARGET // $baseUrl</span>
                <h1>INTERCEPTOR Verification Target</h1>
                <p>Trigger real HTTP requests from this page. In Strict Mode, every request below is paused by INTERCEPTOR until you Approve, Edit &amp; Send, or Block it.</p>
              </div>
              <div class="grid">
                <button class="btn" onclick="sendGetTelemetry()">
                  <div class="btn-top"><span class="method get">GET</span><span class="endpoint">/api/v1/profile?env=prod&amp;debug=false</span></div>
                  <span class="desc">Test query parameter &amp; header inspection/modification via fetch()</span>
                </button>
                <button class="btn" onclick="sendPostJson()">
                  <div class="btn-top"><span class="method post">POST</span><span class="endpoint">/api/v1/auth/token (JSON Payload)</span></div>
                  <span class="desc">Sends JSON credentials &amp; session cookie. Try editing role='user' to 'admin'!</span>
                </button>
                <button class="btn" onclick="sendPutWebhook()">
                  <div class="btn-top"><span class="method put">PUT</span><span class="endpoint">/api/v1/FuzzConfig (XMLHttpRequest)</span></div>
                  <span class="desc">Dispatches XHR PUT with custom X-Api-Signature header</span>
                </button>
                <button class="btn" onclick="sendRedirectHop()">
                  <div class="btn-top"><span class="method get">GET</span><span class="endpoint">/redirect-step-1 (302 Redirect Chain)</span></div>
                  <span class="desc">Tests HTTP 302 redirect chain tracking and final destination capture</span>
                </button>
              </div>
              <div class="console-title">Page Network Response Log</div>
              <div id="output" class="console">Ready. Tap any action above to fire a real network request...</div>

              <script>
                const out = document.getElementById('output');
                function logStatus(msg) {
                  out.textContent = '[' + new Date().toLocaleTimeString() + '] ' + msg;
                }
                async function sendGetTelemetry() {
                  logStatus('Awaiting INTERCEPTOR human-in-the-loop approval for GET /api/v1/profile...');
                  try {
                    const res = await fetch('$baseUrl/api/v1/profile?env=prod&debug=false', {
                      method: 'GET',
                      headers: {
                        'X-Client-Version': 'Interceptor-Browser/1.0',
                        'X-Trace-Id': 'tr-' + Math.floor(Math.random() * 9000 + 1000)
                      }
                    });
                    const text = await res.text();
                    logStatus('HTTP ' + res.status + ' Received:\n' + text);
                  } catch (err) {
                    logStatus('BLOCKED / ERROR: ' + err.message);
                  }
                }
                async function sendPostJson() {
                  logStatus('Awaiting INTERCEPTOR approval for POST /api/v1/auth/token...');
                  try {
                    const res = await fetch('$baseUrl/api/v1/auth/token', {
                      method: 'POST',
                      headers: {
                        'Content-Type': 'application/json',
                        'X-Session-Scope': 'standard',
                        'Cookie': 'session_id=sec_98123a; theme=obsidian'
                      },
                      body: JSON.stringify({
                        username: 'operator_01',
                        role: 'viewer',
                        telemetry_opt_in: true,
                        timestamp: Date.now()
                      }, null, 2)
                    });
                    const text = await res.text();
                    logStatus('HTTP ' + res.status + ' Received:\n' + text);
                  } catch (err) {
                    logStatus('BLOCKED / ERROR: ' + err.message);
                  }
                }
                function sendPutWebhook() {
                  logStatus('Awaiting INTERCEPTOR approval for XHR PUT /api/v1/FuzzConfig...');
                  const xhr = new XMLHttpRequest();
                  xhr.open('PUT', '$baseUrl/api/v1/FuzzConfig?mode=strict');
                  xhr.setRequestHeader('Content-Type', 'application/json');
                  xhr.setRequestHeader('X-Api-Signature', 'sha256=8f9d2a1b4c');
                  xhr.onload = function() {
                    logStatus('XHR HTTP ' + xhr.status + ' Received:\n' + xhr.responseText);
                  };
                  xhr.onerror = function() {
                    logStatus('XHR BLOCKED / NETWORK ERROR');
                  };
                  xhr.send(JSON.stringify({ firewall_level: 'maximum', audit_log: true }));
                }
                async function sendRedirectHop() {
                  logStatus('Awaiting INTERCEPTOR approval for GET /redirect-step-1...');
                  try {
                    const res = await fetch('$baseUrl/redirect-step-1', {
                      method: 'GET',
                      headers: { 'X-Follow-Hops': 'true' }
                    });
                    const text = await res.text();
                    logStatus('HTTP ' + res.status + ' (After Redirect) Received:\n' + text);
                  } catch (err) {
                    logStatus('BLOCKED / ERROR: ' + err.message);
                  }
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
