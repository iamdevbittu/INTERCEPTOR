package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

class OkHttpNetworkExecutor(
    private val connectTimeoutSeconds: Long = 15L,
    private val readTimeoutSeconds: Long = 20L
) : NetworkExecutor {

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override suspend fun execute(
        request: InterceptedRequest,
        followRedirects: Boolean
    ): Result<InterceptedResponse> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val redirectChain = mutableListOf<String>()
            val client = if (followRedirects) {
                baseClient.newBuilder()
                    .addNetworkInterceptor { chain ->
                        val req = chain.request()
                        val resp = chain.proceed(req)
                        if (resp.isRedirect) {
                            val location = resp.header("Location")
                            if (location != null) {
                                redirectChain.add("${resp.code} -> $location")
                            }
                        }
                        resp
                    }
                    .build()
            } else {
                noRedirectClient
            }

            val headersBuilder = Headers.Builder()
            request.headers.forEach { (k, v) ->
                if (k.isNotBlank()) {
                    headersBuilder.addUnsafeNonAscii(k.trim(), v)
                }
            }

            if (request.cookies.isNotEmpty() &&
                request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }
            ) {
                headersBuilder.add("Cookie", InterceptedRequest.formatCookieHeader(request.cookies))
            }

            val methodUpper = request.method.trim().uppercase()
            val contentTypeMedia = request.contentType?.toMediaTypeOrNull()
                ?: "text/plain; charset=utf-8".toMediaTypeOrNull()

            val requestBody = when {
                !HttpMethod.permitsRequestBody(methodUpper) -> null
                request.body != null -> request.body.toRequestBody(contentTypeMedia)
                HttpMethod.requiresRequestBody(methodUpper) -> "".toRequestBody(contentTypeMedia)
                else -> null
            }

            val okRequest = Request.Builder()
                .url(request.url)
                .headers(headersBuilder.build())
                .method(methodUpper, requestBody)
                .build()

            client.newCall(okRequest).execute().use { response ->
                val duration = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                val responseHeaders = LinkedHashMap<String, String>()
                response.headers.names().forEach { name ->
                    responseHeaders[name] = response.headers.values(name).joinToString("; ")
                }

                val respContentType = response.header("Content-Type")
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val sizeBytes = rawBytes.size.toLong()
                val isBinary = isBinaryContentType(respContentType, rawBytes)

                val bodyString = if (isBinary) {
                    "[Binary Payload: $sizeBytes bytes, Content-Type: ${respContentType ?: "application/octet-stream"}]"
                } else {
                    String(rawBytes, Charsets.UTF_8)
                }

                val handshake = response.handshake
                val tlsVersion = handshake?.tlsVersion?.javaName
                val cipherSuite = handshake?.cipherSuite?.javaName
                val peerCert = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val certSummary = peerCert?.let {
                    "Subject: ${it.subjectX500Principal.name} | Issuer: ${it.issuerX500Principal.name} | Valid until: ${it.notAfter}"
                }

                Result.success(
                    InterceptedResponse(
                        statusCode = response.code,
                        statusMessage = response.message.ifBlank { httpStatusReason(response.code) },
                        headers = responseHeaders,
                        body = bodyString,
                        rawBytes = rawBytes,
                        isBinary = isBinary,
                        contentType = respContentType,
                        sizeBytes = sizeBytes,
                        durationMs = duration,
                        redirectChain = redirectChain,
                        protocol = response.protocol.toString().uppercase(),
                        tlsVersion = tlsVersion,
                        cipherSuite = cipherSuite,
                        certificateInfo = certSummary
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun isBinaryContentType(contentType: String?, bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val ct = contentType?.lowercase() ?: ""
            if (ct.contains("json") || ct.contains("text/") || ct.contains("xml") ||
                ct.contains("javascript") || ct.contains("html") || ct.contains("form-urlencoded")
            ) {
                return false
            }
            if (ct.contains("image/") || ct.contains("video/") || ct.contains("audio/") ||
                ct.contains("application/octet-stream") || ct.contains("application/pdf") ||
                ct.contains("font/") || ct.contains("application/zip")
            ) {
                return true
            }
            // Check first 512 bytes for null bytes or excessive non-printable control chars
            val sampleSize = minOf(bytes.size, 512)
            var unprintable = 0
            for (i in 0 until sampleSize) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return true
                if (b < 9 || (b in 14..31)) unprintable++
            }
            return (unprintable.toDouble() / sampleSize) > 0.15
        }

        fun httpStatusReason(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "HTTP $code"
        }
    }
}
