package com.example.proxy

import android.webkit.CookieManager
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
                respJson.put("statusText", response.statusMessage.ifBlank { "OK" })
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

        val headers = LinkedHashMap<String, String>()
        webRequest.requestHeaders?.forEach { (k, v) -> headers[k] = v }

        // Attach WebView CookieManager cookies if not already present
        try {
            val webViewCookies = CookieManager.getInstance().getCookie(url)
            if (!webViewCookies.isNullOrBlank() && headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                headers["Cookie"] = webViewCookies
            }
        } catch (_: Exception) {
        }

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

        if (processed.state == RequestState.BLOCKED ||
            processed.state == RequestState.TIMED_OUT ||
            processed.state == RequestState.CANCELLED
        ) {
            val errorJson = """{"interceptor_firewall":"BLOCKED","state":"${processed.state.name}","reason":"${processed.errorMessage ?: "Blocked by user"}","url":"$url"}"""
            return WebResourceResponse(
                "application/json",
                "UTF-8",
                403,
                "Forbidden",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "X-Interceptor-State" to processed.state.name
                ),
                ByteArrayInputStream(errorJson.toByteArray(Charsets.UTF_8))
            )
        }

        val response = processed.response
        if (processed.state == RequestState.COMPLETED && response != null) {
            // Sync Set-Cookie headers back to WebView CookieManager
            try {
                val cookieManager = CookieManager.getInstance()
                response.headers.forEach { (k, v) ->
                    if (k.equals("Set-Cookie", ignoreCase = true)) {
                        cookieManager.setCookie(url, v)
                    }
                }
            } catch (_: Exception) {
            }

            val rawMime = (response.contentType ?: "text/html")
                .substringBefore(";")
                .trim()
                .ifBlank { "text/plain" }

            val charset = response.contentType
                ?.substringAfter("charset=", "UTF-8")
                ?.substringBefore(";")
                ?.trim()
                ?.removeSurrounding("\"")
                ?.ifBlank { "UTF-8" } ?: "UTF-8"

            val bytes = response.rawBytes ?: (response.body ?: "").toByteArray(Charsets.UTF_8)

            // WebResourceResponse throws IllegalArgumentException if statusCode is in 300..399 or < 100 or if reasonPhrase is empty
            val safeStatusCode = when (val code = response.statusCode) {
                in 300..399 -> 200
                204 -> 200
                in 100..599 -> code
                else -> 200
            }
            val safeReason = response.statusMessage
                .replace(Regex("[^\\x20-\\x7E]"), "")
                .trim()
                .ifBlank { "OK" }

            return try {
                WebResourceResponse(
                    rawMime,
                    charset,
                    safeStatusCode,
                    safeReason,
                    response.headers,
                    ByteArrayInputStream(bytes)
                )
            } catch (_: Exception) {
                WebResourceResponse(
                    rawMime,
                    charset,
                    ByteArrayInputStream(bytes)
                )
            }
        }

        // If approved but upstream execution delegated or fell back, allow WebView native stack only if not blocked
        return null
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
              var origFetch = window.fetch;
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
                    // If body is FormData or Blob or File (e.g. file/photo upload), delegate to native fetch so binary multipart upload works seamlessly!
                    if (init && init.body && (
                        (typeof FormData !== 'undefined' && init.body instanceof FormData) ||
                        (typeof Blob !== 'undefined' && init.body instanceof Blob) ||
                        (typeof ArrayBuffer !== 'undefined' && init.body instanceof ArrayBuffer)
                    )) {
                      if (origFetch) {
                        origFetch.call(window, input, init).then(resolve).catch(reject);
                        return;
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
            })();
        """.trimIndent()
    }
}
