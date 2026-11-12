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
     * Handles native WebView `shouldInterceptRequest` calls on WebView's background IO thread.
     * Evaluates Domain Firewall block rules in <1ms without blocking the Chromium IO thread on
     * human-in-the-loop waits (ensuring `WebChromeClient.onShowFileChooser` and page rendering never stall).
     */
    fun interceptNativeWebResource(webRequest: WebResourceRequest): WebResourceResponse? {
        val url = webRequest.url?.toString() ?: return null
        val scheme = webRequest.url?.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val methodUpper = (webRequest.method ?: "GET").uppercase()
        val headers = LinkedHashMap<String, String>()
        webRequest.requestHeaders?.forEach { (k, v) -> headers[k] = v }

        try {
            val webViewCookies = CookieManager.getInstance().getCookie(url)
            if (!webViewCookies.isNullOrBlank() && headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                headers["Cookie"] = webViewCookies
            }
        } catch (_: Exception) {
        }

        val req = InterceptedRequest(
            url = url,
            method = methodUpper,
            headers = headers,
            source = InterceptionSource.WEBVIEW_NATIVE,
            tabId = currentTabIdProvider()
        )

        val blockedRequest = runBlocking {
            engine.evaluateNativeWebResource(req)
        }

        if (blockedRequest != null && blockedRequest.state == RequestState.BLOCKED) {
            val errorJson = """{"interceptor_firewall":"BLOCKED","state":"${blockedRequest.state.name}","reason":"${blockedRequest.errorMessage ?: "Blocked by user"}","url":"$url"}"""
            return WebResourceResponse(
                "application/json",
                "UTF-8",
                403,
                "Forbidden",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "X-Interceptor-State" to blockedRequest.state.name
                ),
                ByteArrayInputStream(errorJson.toByteArray(Charsets.UTF_8))
            )
        }

        // Return null so Chromium WebView streams the approved resource natively with full HTTP/2,
        // cookies, multipart upload streams, and unblocked Mojo IPC for onShowFileChooser.
        return null
    }

    companion object {
        const val JS_BRIDGE_NAME = "InterceptorNativeBridge"

        /**
         * Injected into WebView pages to:
         * 1. Ensure detached `<input type="file">` elements created via JS (`document.createElement('input')`)
         *    are attached to `document.body` before `.click()` and remove restrictive `capture` attributes
         *    so Android WebView ALWAYS triggers `WebChromeClient.onShowFileChooser` to open the File Manager.
         * 2. Polyfill `window.showOpenFilePicker` so modern sites using the File System Access API open the Android File Manager.
         * 3. Hook `window.fetch` and `XMLHttpRequest` for JSON/text API inspection while delegating binary
         *    `FormData`/`Blob`/`File` uploads to native fetch/XHR.
         */
        val JS_INTERCEPTOR_INJECTION = """
            (function() {
              if (window.__interceptorHookInstalled) return;
              window.__interceptorHookInstalled = true;

              function prepareFileInputForAndroid(inputEl) {
                try {
                  if (!inputEl || !inputEl.type || inputEl.type.toLowerCase() !== 'file') return;
                  if (inputEl.hasAttribute('capture')) {
                    inputEl.removeAttribute('capture');
                  }
                  if (!inputEl.isConnected && document.body) {
                    inputEl.style.position = 'fixed';
                    inputEl.style.top = '-9999px';
                    inputEl.style.left = '-9999px';
                    inputEl.style.width = '1px';
                    inputEl.style.height = '1px';
                    inputEl.style.opacity = '0';
                    inputEl.style.display = 'block';
                    inputEl.style.visibility = 'visible';
                    inputEl.style.pointerEvents = 'none';
                    document.body.appendChild(inputEl);
                  }
                } catch (e) {}
              }

              // 1a. Fix programmatic .click() on detached <input type="file">
              var origInputClick = HTMLInputElement.prototype.click;
              HTMLInputElement.prototype.click = function() {
                prepareFileInputForAndroid(this);
                return origInputClick.apply(this, arguments);
              };

              var origElemClick = HTMLElement.prototype.click;
              HTMLElement.prototype.click = function() {
                if (this instanceof HTMLInputElement) {
                  prepareFileInputForAndroid(this);
                }
                return origElemClick.apply(this, arguments);
              };

              // 1b. Also sanitize <input type="file"> on direct user tap before browser default action
              document.addEventListener('click', function(ev) {
                try {
                  var target = ev.target;
                  if (target && target.tagName === 'INPUT' && target.type && target.type.toLowerCase() === 'file') {
                    prepareFileInputForAndroid(target);
                  } else if (target && target.closest) {
                    var label = target.closest('label');
                    if (label) {
                      var fileInput = label.querySelector('input[type="file"]') ||
                        (label.htmlFor ? document.getElementById(label.htmlFor) : null);
                      if (fileInput) prepareFileInputForAndroid(fileInput);
                    }
                  }
                } catch (e) {}
              }, true);

              // 2. Polyfill window.showOpenFilePicker for Android WebView
              if (typeof window.showOpenFilePicker !== 'function') {
                window.showOpenFilePicker = function(options) {
                  return new Promise(function(resolve, reject) {
                    var input = document.createElement('input');
                    input.type = 'file';
                    if (options && options.multiple) {
                      input.multiple = true;
                    }
                    prepareFileInputForAndroid(input);
                    input.addEventListener('change', function() {
                      var files = Array.from(input.files || []);
                      if (files.length === 0) {
                        reject(new DOMException('The user aborted a request.', 'AbortError'));
                        return;
                      }
                      var handles = files.map(function(file) {
                        return {
                          kind: 'file',
                          name: file.name,
                          getFile: function() { return Promise.resolve(file); }
                        };
                      });
                      resolve(handles);
                    });
                    origInputClick.call(input);
                  });
                };
              }

              function resolveFullUrl(inputUrl) {
                try {
                  return new URL(inputUrl, window.location.href).href;
                } catch (e) {
                  return String(inputUrl);
                }
              }

              function isBinaryUploadBody(body) {
                if (!body) return false;
                return (typeof FormData !== 'undefined' && body instanceof FormData) ||
                  (typeof Blob !== 'undefined' && body instanceof Blob) ||
                  (typeof File !== 'undefined' && body instanceof File) ||
                  (typeof ArrayBuffer !== 'undefined' && body instanceof ArrayBuffer) ||
                  (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams);
              }

              // 3. Hook window.fetch (delegating FormData/Blob/File uploads to native fetch)
              var origFetch = window.fetch;
              window.fetch = function(input, init) {
                if (init && isBinaryUploadBody(init.body)) {
                  return origFetch.call(window, input, init);
                }
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
                    }, 1);
                  } catch (outerErr) {
                    reject(outerErr);
                  }
                });
              };
            })();
        """.trimIndent()
    }
}
