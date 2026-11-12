package com.example.network

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

enum class RequestState {
    CREATED,
    PENDING,
    APPROVED,
    BLOCKED,
    MODIFIED,
    SENT,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}

enum class DomainPolicyAction(val displayName: String) {
    ALWAYS_ALLOW("Always Allow"),
    ALWAYS_BLOCK("Always Block"),
    ASK_EVERY_TIME("Ask Every Time"),
    ALLOW_ONCE("Allow Once"),
    TEMPORARY_ALLOW("Temporary Allow (15m)")
}

enum class InterceptionSource(val label: String) {
    WEBVIEW_NATIVE("WebView Interceptor"),
    JS_FETCH_HOOK("JS fetch() Bridge"),
    JS_XHR_HOOK("JS XHR Bridge"),
    MANUAL_REPLAY("Replay / Composer"),
    SANDBOX_CLIENT("Browser Test Bench")
}

enum class PayloadFormat {
    JSON,
    FORM_URL_ENCODED,
    MULTIPART_FORM,
    PLAIN_TEXT,
    BINARY,
    EMPTY
}

data class InterceptedResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val body: String?,
    val rawBytes: ByteArray? = null,
    val isBinary: Boolean = false,
    val contentType: String? = null,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val redirectChain: List<String> = emptyList(),
    val protocol: String = "HTTP/1.1",
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val certificateInfo: String? = null,
    val wasModified: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InterceptedResponse) return false
        return statusCode == other.statusCode &&
            statusMessage == other.statusMessage &&
            headers == other.headers &&
            body == other.body &&
            isBinary == other.isBinary &&
            contentType == other.contentType &&
            sizeBytes == other.sizeBytes &&
            durationMs == other.durationMs &&
            redirectChain == other.redirectChain
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + statusMessage.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        return result
    }
}

data class InterceptedRequest(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val method: String = "GET",
    val host: String = extractHost(url),
    val path: String = extractPath(url),
    val queryParams: Map<String, String> = extractQueryParams(url),
    val headers: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = extractCookies(headers),
    val contentType: String? = findHeaderIgnoreCase(headers, "Content-Type"),
    val body: String? = null,
    val isBinaryBody: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val state: RequestState = RequestState.CREATED,
    val source: InterceptionSource = InterceptionSource.WEBVIEW_NATIVE,
    val tabId: String = "tab-1",
    val isMockPreview: Boolean = false,
    val wasModified: Boolean = false,
    val originalUrl: String = url,
    val originalMethod: String = method,
    val originalHeaders: Map<String, String> = headers,
    val originalBody: String? = body,
    val modificationSummary: List<String> = emptyList(),
    val matchedPolicy: DomainPolicyAction? = null,
    val response: InterceptedResponse? = null,
    val errorMessage: String? = null
) {
    val payloadFormat: PayloadFormat
        get() = classifyPayload(contentType, body, isBinaryBody)

    val bodySizeBytes: Int
        get() = body?.toByteArray(Charsets.UTF_8)?.size ?: 0

    companion object {
        fun extractHost(rawUrl: String): String {
            return try {
                val uri = URI(rawUrl.trim())
                val host = uri.host ?: return rawUrl
                val port = uri.port
                if (port != -1 && port != 80 && port != 443) "$host:$port" else host
            } catch (_: Exception) {
                rawUrl.substringAfter("://").substringBefore("/").substringBefore("?")
            }
        }

        fun extractPath(rawUrl: String): String {
            return try {
                val uri = URI(rawUrl.trim())
                val p = uri.rawPath
                if (p.isNullOrEmpty()) "/" else p
            } catch (_: Exception) {
                "/" + rawUrl.substringAfter("://").substringAfter("/", "").substringBefore("?")
            }
        }

        fun extractQueryParams(rawUrl: String): Map<String, String> {
            return try {
                val query = URI(rawUrl.trim()).rawQuery ?: return emptyMap()
                if (query.isBlank()) return emptyMap()
                query.split("&").mapNotNull { pair ->
                    if (pair.isBlank()) null
                    else {
                        val idx = pair.indexOf('=')
                        if (idx == -1) {
                            URLDecoder.decode(pair, "UTF-8") to ""
                        } else {
                            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                            k to v
                        }
                    }
                }.toMap(LinkedHashMap())
            } catch (_: Exception) {
                emptyMap()
            }
        }

        fun buildUrlWithQueryParams(baseUrl: String, params: Map<String, String>): String {
            val baseWithoutQuery = baseUrl.substringBefore("?").substringBefore("#")
            val fragment = if (baseUrl.contains("#")) "#" + baseUrl.substringAfter("#") else ""
            if (params.isEmpty()) return baseWithoutQuery + fragment
            val queryString = params.entries.joinToString("&") { (k, v) ->
                val encK = URLEncoder.encode(k, "UTF-8")
                val encV = URLEncoder.encode(v, "UTF-8")
                "$encK=$encV"
            }
            return "$baseWithoutQuery?$queryString$fragment"
        }

        fun extractCookies(headers: Map<String, String>): Map<String, String> {
            val cookieHeader = findHeaderIgnoreCase(headers, "Cookie") ?: return emptyMap()
            return parseCookieString(cookieHeader)
        }

        fun parseCookieString(cookieHeader: String): Map<String, String> {
            if (cookieHeader.isBlank()) return emptyMap()
            return cookieHeader.split(";").mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null
                else {
                    val idx = trimmed.indexOf('=')
                    if (idx == -1) trimmed to ""
                    else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
                }
            }.toMap(LinkedHashMap())
        }

        fun formatCookieHeader(cookies: Map<String, String>): String {
            return cookies.entries.joinToString("; ") { (k, v) ->
                if (v.isEmpty()) k else "$k=$v"
            }
        }

        fun findHeaderIgnoreCase(headers: Map<String, String>, name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        fun classifyPayload(contentType: String?, body: String?, isBinary: Boolean): PayloadFormat {
            if (isBinary) return PayloadFormat.BINARY
            if (body.isNullOrEmpty()) return PayloadFormat.EMPTY
            val ct = contentType?.lowercase() ?: ""
            return when {
                ct.contains("application/json") || ct.contains("+json") ||
                    (body.trimStart().startsWith("{") && body.trimEnd().endsWith("}")) ||
                    (body.trimStart().startsWith("[") && body.trimEnd().endsWith("]")) -> PayloadFormat.JSON
                ct.contains("application/x-www-form-urlencoded") -> PayloadFormat.FORM_URL_ENCODED
                ct.contains("multipart/form-data") -> PayloadFormat.MULTIPART_FORM
                ct.contains("octet-stream") || ct.contains("image/") || ct.contains("video/") || ct.contains("audio/") -> PayloadFormat.BINARY
                else -> PayloadFormat.PLAIN_TEXT
            }
        }
    }
}

data class RequestEditDraft(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val queryParams: Map<String, String>,
    val cookies: Map<String, String>,
    val body: String?
)

sealed class UserApprovalDecision {
    data object AllowOriginal : UserApprovalDecision()
    data class Block(val reason: String = "Rejected by user in strict firewall") : UserApprovalDecision()
    data class EditAndSend(val draft: RequestEditDraft) : UserApprovalDecision()
}

interface NetworkExecutor {
    suspend fun execute(request: InterceptedRequest, followRedirects: Boolean = true): Result<InterceptedResponse>
}

interface DomainPolicyEngineContract {
    suspend fun evaluateDomain(host: String): DomainPolicyAction
    suspend fun setDomainPolicy(domain: String, action: DomainPolicyAction, priority: Int = 100, notes: String = "")
}
