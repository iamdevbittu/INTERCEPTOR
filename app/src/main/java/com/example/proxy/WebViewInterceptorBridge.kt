package com.example.proxy

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.example.interceptor.RequestInterceptorEngine
import com.example.network.InterceptedRequest
import com.example.network.InterceptionSource
import com.example.network.RequestState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Bridges Android Chromium WebView networking to the human-in-the-loop [RequestInterceptorEngine].
 *
 * Uses two complementary rootless interception mechanisms:
 * 1. JavaScript `fetch()` and `XMLHttpRequest` hook injected at document start (`JS_HOOK_SCRIPT`)
 *    combined with `@JavascriptInterface` (`InterceptorJsBridge`). This captures full HTTP payloads
 *    (POST/PUT/PATCH/DELETE JSON bodies, form data, custom headers, and cookies) BEFORE transmission
 *    and suspends the JS Promise/XHR until the user approves, edits, or blocks the request.
 * 2. `WebViewClient.shouldInterceptRequest` (`interceptWebViewResource`) for standard navigation,
 *    document, and subresource requests not routed through the JS bridge.
 */
class WebViewInterceptorBridge(
    private val engine: RequestInterceptorEngine,
    private val currentTabIdProvider: () -> String = { "tab-1" }
) {

    @JavascriptInterface
    fun interceptSync(requestJson: String): String {
        return try {
            val obj = JSONObject(requestJson)
            val rawUrl = obj.optString("url", "")
            val method = obj.optString("method", "GET").uppercase()
            val body = if (obj.isNull("body")) null else obj.optString("body")
            val sourceType = obj.optString("source", "FETCH")

            val headersMap = LinkedHashMap<String, String>()
            val headersObj = obj.optJSONObject("headers")
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    headersMap[k] = headersObj.optString(k, "")
                }
            }

            val cookieStr = obj.optString("cookies", "")
            if (cookieStr.isNotBlank() && headersMap.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                headersMap["Cookie"] = cookieStr
            }

            val interceptedReq = InterceptedRequest(
                url = rawUrl,
                method = method,
                headers = headersMap,
                body = body,
                source = if (sourceType == "XHR") InterceptionSource.JS_XHR_HOOK else InterceptionSource.JS_FETCH_HOOK,
                tabId = currentTabIdProvider()
            )

            val processed = runBlocking {
                engine.interceptAndProcess(interceptedReq)
            }

            val respJson = JSONObject()
            respJson.put("requestId", processed.id)
            respJson.put("state", processed.state.name)
            respJson.put("wasModified", processed.wasModified)

            val response = processed.response
            if (processed.state == RequestState.COMPLETED && response != null) {
                respJson.put("allowed", true)
                respJson.put("status", response.statusCode)
                respJson.put("statusText", response.statusMessage)
                respJson.put("body", response.body ?: "")
                val respHeadersJson = JSONObject()
                response.headers.forEach { (k, v) -> respHeadersJson.put(k, v) }
                respJson.put("headers", respHeadersJson)
            } else {
                respJson.put("allowed", false)
                respJson.put("status", 0)
                respJson.put("error", processed.errorMessage ?: "Request blocked by INTERCEPTOR (${processed.state.name})")
            }
            respJson.toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("allowed", false)
                put("status", 0)
                put("error", "Bridge error: ${e.message}")
            }.toString()
        }
    }

    /**
     * Handles native WebView `shouldInterceptRequest` calls on WebView's background worker thread.
     */
    fun interceptNativeWebResource(webRequest: WebResourceRequest): WebResourceResponse? {
        val url = webRequest.url?.toString() ?: return null
        val scheme = webRequest.url?.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        // Skip requests that already carry our internal bypass marker from JS bridge if any
        val headers = LinkedHashMap<String, String>()
        webRequest.requestHeaders?.forEach { (k, v) -> headers[k] = v }

        val req = InterceptedRequest(
            url = url,
            method = webRequest.method ?: "GET",
            headers = headers,
            source = InterceptionSource.WEBVIEW_NATIVE,
            tabId = currentTabIdProvider()
        )

        val processed = runBlocking {
            engine.interceptAndProcess(req)
        }

        val response = processed.response
        return if (processed.state == RequestState.COMPLETED && response != null) {
            val rawMime = (response.contentType ?: "text/html").substringBefore(";").trim()
            val charset = response.contentType
                ?.substringAfter("charset=", "UTF-8")
                ?.substringBefore(";")
                ?.trim() ?: "UTF-8"

            val bytes = response.rawBytes ?: (response.body ?: "").toByteArray(Charsets.UTF_8)
            val statusReason = response.statusMessage.ifBlank { "OK" }

            WebResourceResponse(
                rawMime.ifBlank { "text/plain" },
                charset,
                response.statusCode.coerceIn(100, 599),
                statusReason,
                response.headers,
                ByteArrayInputStream(bytes)
            )
        } else {
            // Return controlled network error response when blocked or timed out
            val errorJson = """{"interceptor_firewall":"BLOCKED","state":"${processed.state.name}","reason":"${processed.errorMessage ?: "Blocked by user"}","url":"$url"}"""
            WebResourceResponse(
                "application/json",
                "UTF-8",
                403,
                "Blocked by Interceptor Firewall",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "X-Interceptor-State" to processed.state.name
                ),
                ByteArrayInputStream(errorJson.toByteArray(Charsets.UTF_8))
            )
        }
    }

    companion object {
        const val JS_BRIDGE_NAME = "InterceptorNativeBridge"

        /**
         * Injected into WebView pages to hook window.fetch and XMLHttpRequest so that
         * POST/PUT/PATCH/DELETE payloads, JSON bodies, and custom headers are intercepted
         * with full body fidelity before leaving the browser.
         */
        val JS_INTERCEPTOR_INJECTION = """
            (function() {
              if (window.__interceptorHookInstalled) return;
              window.__interceptorHookInstalled = true;

              function resolveFullUrl(inputUrl) {
                try {
                  return new URL(inputUrl, window.location.href).href;
                } catch (e) {
                  return String(inputUrl);
                }
              }

              // Hook window.fetch
              window.fetch = function(input, init) {
                return new Promise(function(resolve, reject) {
                  try {
                    var rawUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : String(input));
                    var fullUrl = resolveFullUrl(rawUrl);
                    var method = (init && init.method) ? init.method : ((typeof input === 'object' && input.method) ? input.method : 'GET');
                    var headersObj = {};
                    if (init && init.headers) {
                      if (typeof init.headers.forEach === 'function') {
                        init.headers.forEach(function(v, k) { headersObj[k] = v; });
                      } else if (typeof init.headers === 'object') {
                        Object.keys(init.headers).forEach(function(k) { headersObj[k] = init.headers[k]; });
                      }
                    }
                    var bodyStr = null;
                    if (init && init.body !== undefined && init.body !== null) {
                      bodyStr = (typeof init.body === 'string') ? init.body : JSON.stringify(init.body);
                    }
                    var payload = JSON.stringify({
                      url: fullUrl,
                      method: String(method).toUpperCase(),
                      headers: headersObj,
                      cookies: document.cookie || '',
                      body: bodyStr,
                      source: 'FETCH'
                    });

                    setTimeout(function() {
                      try {
                        var rawResult = window.$JS_BRIDGE_NAME.interceptSync(payload);
                        var parsed = JSON.parse(rawResult);
                        if (!parsed.allowed) {
                          reject(new TypeError(parsed.error || 'Blocked by INTERCEPTOR Firewall'));
                          return;
                        }
                        var respHeaders = new Headers(parsed.headers || {});
                        var response = new Response(parsed.body || '', {
                          status: parsed.status || 200,
                          statusText: parsed.statusText || 'OK',
                          headers: respHeaders
                        });
                        resolve(response);
                      } catch (err) {
                        reject(err);
                      }
                    }, 10);
                  } catch (outerErr) {
                    reject(outerErr);
                  }
                });
              };

              // Hook XMLHttpRequest
              var OrigXHR = window.XMLHttpRequest;
              function InterceptedXHR() {
                var self = this;
                this._method = 'GET';
                this._url = '';
                this._headers = {};
                this.readyState = 0;
                this.status = 0;
                this.statusText = '';
                this.responseText = '';
                this.response = '';
                this.onreadystatechange = null;
                this.onload = null;
                this.onerror = null;
              }
              InterceptedXHR.prototype.open = function(method, url) {
                this._method = method ? String(method).toUpperCase() : 'GET';
                this._url = resolveFullUrl(url);
                this.readyState = 1;
              };
              InterceptedXHR.prototype.setRequestHeader = function(k, v) {
                this._headers[k] = v;
              };
              InterceptedXHR.prototype.getAllResponseHeaders = function() {
                if (!this._respHeaders) return '';
                var out = '';
                var rh = this._respHeaders;
                Object.keys(rh).forEach(function(k) { out += k + ': ' + rh[k] + '\r\n'; });
                return out;
              };
              InterceptedXHR.prototype.getResponseHeader = function(name) {
                if (!this._respHeaders) return null;
                var lower = String(name).toLowerCase();
                var found = null;
                var rh = this._respHeaders;
                Object.keys(rh).forEach(function(k) {
                  if (k.toLowerCase() === lower) found = rh[k];
                });
                return found;
              };
              InterceptedXHR.prototype.send = function(body) {
                var self = this;
                var bodyStr = (body !== undefined && body !== null) ? String(body) : null;
                var payload = JSON.stringify({
                  url: self._url,
                  method: self._method,
                  headers: self._headers,
                  cookies: document.cookie || '',
                  body: bodyStr,
                  source: 'XHR'
                });
                setTimeout(function() {
                  try {
                    var rawResult = window.$JS_BRIDGE_NAME.interceptSync(payload);
                    var parsed = JSON.parse(rawResult);
                    if (!parsed.allowed) {
                      self.readyState = 4;
                      self.status = 0;
                      if (typeof self.onerror === 'function') self.onerror(new Error(parsed.error));
                      return;
                    }
                    self.readyState = 4;
                    self.status = parsed.status || 200;
                    self.statusText = parsed.statusText || 'OK';
                    self.responseText = parsed.body || '';
                    self.response = parsed.body || '';
                    self._respHeaders = parsed.headers || {};
                    if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
                    if (typeof self.onload === 'function') self.onload();
                  } catch (e) {
                    if (typeof self.onerror === 'function') self.onerror(e);
                  }
                }, 10);
              };
              window.XMLHttpRequest = InterceptedXHR;
            })();
        """.trimIndent()
    }
}
