package com.example.security

import com.example.network.InterceptedRequest
import com.example.network.RequestEditDraft
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class CapabilityDisclosure(
    val feature: String,
    val status: String,
    val details: String,
    val isSupported: Boolean
)

object SecurityValidator {
    private val SUPPORTED_METHODS = setOf(
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    )

    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"
    )

    fun validateUrl(rawUrl: String): ValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult(false, listOf("URL cannot be empty."))
        }
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return ValidationResult(
                    false,
                    listOf("Unsupported URL scheme '${scheme ?: "none"}'. Only http:// and https:// are permitted.")
                )
            }
            if (uri.host.isNullOrBlank()) {
                return ValidationResult(false, listOf("URL must contain a valid hostname."))
            }
            val warnings = mutableListOf<String>()
            if (scheme == "http" && uri.host != "127.0.0.1" && uri.host != "localhost") {
                warnings.add("Unencrypted HTTP transport: traffic is transmitted in cleartext.")
            }
            ValidationResult(true, warnings = warnings)
        } catch (e: Exception) {
            ValidationResult(false, listOf("Malformed URL syntax: ${e.message ?: "Invalid URI"}"))
        }
    }

    fun validateHttpMethod(method: String): ValidationResult {
        val upper = method.trim().uppercase()
        if (upper.isEmpty()) {
            return ValidationResult(false, listOf("HTTP method cannot be empty."))
        }
        if (upper !in SUPPORTED_METHODS) {
            return ValidationResult(
                false,
                listOf("Unsupported HTTP method '$upper'. Allowed: ${SUPPORTED_METHODS.joinToString(", ")}.")
            )
        }
        return ValidationResult(true)
    }

    fun validateHeaders(headers: Map<String, String>): ValidationResult {
        val errors = mutableListOf<String>()
        headers.forEach { (key, value) ->
            val trimmedKey = key.trim()
            if (trimmedKey.isEmpty()) {
                errors.add("Header name cannot be blank.")
            } else if (trimmedKey.any { it.isWhitespace() || it == ':' || it == '\r' || it == '\n' }) {
                errors.add("Invalid characters in header name '$trimmedKey' (CRLF / spaces prohibited).")
            }
            if (value.contains('\r') || value.contains('\n')) {
                errors.add("CRLF injection detected in value for header '$trimmedKey'.")
            }
        }
        return ValidationResult(errors.isEmpty(), errors)
    }

    fun validateJsonSyntax(payload: String): ValidationResult {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return ValidationResult(true)
        if (!((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]")))
        ) {
            return ValidationResult(
                false,
                listOf("JSON payload must be a complete Object {...} or Array [...].")
            )
        }
        val strictCheck = StrictJsonSyntaxChecker(trimmed).validate()
        if (!strictCheck.first) {
            return ValidationResult(false, listOf("Invalid JSON syntax: ${strictCheck.second}"))
        }
        return ValidationResult(true)
    }

    fun formatPrettyJson(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    fun validateDraft(
        draft: RequestEditDraft,
        requireValidJsonWhenJsonContentType: Boolean = true
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val urlRes = validateUrl(draft.url)
        errors.addAll(urlRes.errors)
        warnings.addAll(urlRes.warnings)

        val methodRes = validateHttpMethod(draft.method)
        errors.addAll(methodRes.errors)

        val headerRes = validateHeaders(draft.headers)
        errors.addAll(headerRes.errors)

        val contentType = draft.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            ?: ""

        if (requireValidJsonWhenJsonContentType &&
            !draft.body.isNullOrBlank() &&
            (contentType.contains("json", ignoreCase = true) ||
                draft.body.trimStart().startsWith("{") ||
                draft.body.trimStart().startsWith("["))
        ) {
            val jsonRes = validateJsonSyntax(draft.body)
            errors.addAll(jsonRes.errors)
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    fun computeModificationSummary(
        original: InterceptedRequest,
        draft: RequestEditDraft
    ): List<String> {
        val diffs = mutableListOf<String>()
        val finalUrl = if (draft.queryParams != InterceptedRequest.extractQueryParams(draft.url)) {
            InterceptedRequest.buildUrlWithQueryParams(draft.url, draft.queryParams)
        } else {
            draft.url.trim()
        }

        if (finalUrl != original.url) {
            diffs.add("URL changed: ${original.url} -> $finalUrl")
        }
        if (!draft.method.trim().equals(original.method, ignoreCase = true)) {
            diffs.add("Method changed: ${original.method} -> ${draft.method.trim().uppercase()}")
        }
        if (draft.queryParams != original.queryParams) {
            diffs.add("Query parameters modified (${original.queryParams.size} -> ${draft.queryParams.size} params)")
        }
        if (draft.cookies != original.cookies) {
            diffs.add("Cookies modified (${original.cookies.size} -> ${draft.cookies.size} entries)")
        }
        val origHeadersWithoutCookie = original.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        val newHeadersWithoutCookie = draft.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        if (origHeadersWithoutCookie != newHeadersWithoutCookie) {
            diffs.add("HTTP headers modified (${origHeadersWithoutCookie.size} -> ${newHeadersWithoutCookie.size} headers)")
        }
        if ((draft.body ?: "") != (original.body ?: "")) {
            val oldLen = original.body?.length ?: 0
            val newLen = draft.body?.length ?: 0
            diffs.add("Payload body modified ($oldLen chars -> $newLen chars)")
        }
        return diffs
    }

    /**
     * Redacts sensitive credentials when exporting logs/cURL commands if requested.
     */
    fun redactSensitiveHeaders(headers: Map<String, String>, redact: Boolean): Map<String, String> {
        if (!redact) return headers
        return headers.mapValues { (k, v) ->
            if (k.lowercase() in SENSITIVE_HEADERS) "[REDACTED_CREDENTIAL]" else v
        }
    }

    /**
     * Honest, transparent disclosure of interception capabilities and limitations
     * per Sections 1, 10, 11, and 15 of the specification.
     */
    fun getCapabilityDisclosures(): List<CapabilityDisclosure> = listOf(
        CapabilityDisclosure(
            feature = "HTTP/1.1 & HTTP/2 Document & Subresource Interception",
            status = "SUPPORTED (Full Control)",
            details = "Intercepted natively via Chromium WebView shouldInterceptRequest + OkHttp upstream execution with full header, URL, query, and response inspection.",
            isSupported = true
        ),
        CapabilityDisclosure(
            feature = "Fetch API & XMLHttpRequest (POST / PUT / PATCH / JSON Bodies)",
            status = "SUPPORTED (Full Body Edit & Pause)",
            details = "Intercepted via injected JavaScript Fetch/XHR Bridge prior to transmission, allowing human-in-the-loop inspection and modification of JSON payloads, form fields, headers, and cookies.",
            isSupported = true
        ),
        CapabilityDisclosure(
            feature = "HTTPS Server Certificate Verification",
            status = "STRICTLY ENFORCED",
            details = "Standard Android system TrustManager validates upstream TLS certificates. Certificate verification is NEVER globally disabled, and certificates are NEVER silently installed.",
            isSupported = true
        ),
        CapabilityDisclosure(
            feature = "Certificate Pinning & Mutual TLS (mTLS)",
            status = "DISCLOSED LIMITATION",
            details = "Endpoints enforcing strict public-key pinning or client hardware certificates cannot be MITM-decrypted by a local CA and will fail closed if rejected by the OS trust store.",
            isSupported = false
        ),
        CapabilityDisclosure(
            feature = "QUIC / HTTP/3 & Raw WebRTC UDP Sockets",
            status = "DISCLOSED LIMITATION",
            details = "UDP-based WebRTC data channels bypass standard HTTP/HTTPS hooks. Upstream requests executed through INTERCEPTOR's OkHttp engine negotiate HTTP/2 or HTTP/1.1 over verified TCP/TLS.",
            isSupported = false
        ),
        CapabilityDisclosure(
            feature = "Cross-Origin Iframe & Pre-Bridge Service Worker Requests",
            status = "PARTIAL (Header/URL Level)",
            details = "Sub-frames and service workers trigger native WebView shouldInterceptRequest (allowing URL/Host/Header allow/block), though Android WebView's native API does not expose raw POST request streams for cross-origin subframes.",
            isSupported = false
        )
    )
}

internal class StrictJsonSyntaxChecker(private val src: String) {
    private var pos = 0

    fun validate(): Pair<Boolean, String> {
        return try {
            skipWhitespace()
            parseValue()
            skipWhitespace()
            if (pos != src.length) {
                false to "Unexpected trailing character at index $pos"
            } else {
                true to ""
            }
        } catch (e: IllegalArgumentException) {
            false to (e.message ?: "Syntax error")
        }
    }

    private fun parseValue() {
        skipWhitespace()
        if (pos >= src.length) throw IllegalArgumentException("Unexpected end of JSON input")
        when (val c = src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("Unexpected token '$c' at index $pos")
        }
    }

    private fun parseObject() {
        expect('{')
        skipWhitespace()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return
        }
        while (true) {
            skipWhitespace()
            if (pos >= src.length || src[pos] != '"') {
                throw IllegalArgumentException("Expected quoted object key at index $pos")
            }
            parseString()
            skipWhitespace()
            expect(':')
            parseValue()
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == '}') {
                pos++
                return
            } else {
                throw IllegalArgumentException("Expected ',' or '}' in object at index $pos")
            }
        }
    }

    private fun parseArray() {
        expect('[')
        skipWhitespace()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return
        }
        while (true) {
            parseValue()
            skipWhitespace()
            if (pos < src.length && src[pos] == ',') {
                pos++
            } else if (pos < src.length && src[pos] == ']') {
                pos++
                return
            } else {
                throw IllegalArgumentException("Expected ',' or ']' in array at index $pos")
            }
        }
    }

    private fun parseString() {
        expect('"')
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return
            if (c == '\\') {
                if (pos >= src.length) throw IllegalArgumentException("Unterminated escape sequence")
                pos++
            }
        }
        throw IllegalArgumentException("Unterminated string literal")
    }

    private fun parseNumber() {
        if (src[pos] == '-') pos++
        if (pos >= src.length || src[pos] !in '0'..'9') {
            throw IllegalArgumentException("Invalid number at index $pos")
        }
        while (pos < src.length && src[pos] in '0'..'9') pos++
        if (pos < src.length && src[pos] == '.') {
            pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw IllegalArgumentException("Invalid fractional number at index $pos")
            }
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw IllegalArgumentException("Invalid exponent at index $pos")
            }
            while (pos < src.length && src[pos] in '0'..'9') pos++
        }
    }

    private fun consumeLiteral(literal: String) {
        if (!src.startsWith(literal, pos)) {
            throw IllegalArgumentException("Expected '$literal' at index $pos")
        }
        pos += literal.length
    }

    private fun expect(expected: Char) {
        if (pos >= src.length || src[pos] != expected) {
            throw IllegalArgumentException("Expected '$expected' at index $pos")
        }
        pos++
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) {
            pos++
        }
    }
}

