package com.example.interceptor

import com.example.database.TrafficDao
import com.example.database.TrafficEntityMapper
import com.example.network.DomainPolicyAction
import com.example.network.DomainPolicyEngineContract
import com.example.network.InterceptedRequest
import com.example.network.InterceptedResponse
import com.example.network.InterceptionSource
import com.example.network.NetworkExecutor
import com.example.network.RequestEditDraft
import com.example.network.RequestState
import com.example.network.UserApprovalDecision
import com.example.security.SecurityValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class RequestInterceptorEngine(
    private val networkExecutor: NetworkExecutor,
    private val firewallEngine: DomainPolicyEngineContract,
    private val trafficDao: TrafficDao? = null,
    initialApprovalTimeoutMs: Long = 30_000L,
    initialStrictMode: Boolean = true
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var approvalTimeoutMs: Long = initialApprovalTimeoutMs

    @Volatile
    var strictInterceptionMode: Boolean = initialStrictMode

    @Volatile
    var followRedirects: Boolean = true

    private val pendingDeferredMap = ConcurrentHashMap<String, CompletableDeferred<UserApprovalDecision>>()
    private val activeRequestsMap = ConcurrentHashMap<String, InterceptedRequest>()

    private val _liveTraffic = MutableStateFlow<List<InterceptedRequest>>(emptyList())
    val liveTraffic: StateFlow<List<InterceptedRequest>> = _liveTraffic.asStateFlow()

    private val _pendingRequests = MutableStateFlow<List<InterceptedRequest>>(emptyList())
    val pendingRequests: StateFlow<List<InterceptedRequest>> = _pendingRequests.asStateFlow()

    suspend fun loadPersistedHistory() {
        val entities = trafficDao?.getRecentTraffic(300) ?: return
        entities.forEach { entity ->
            val req = TrafficEntityMapper.fromEntity(entity)
            // Any previously pending request from a dead process is marked CANCELLED
            val cleanReq = if (req.state == RequestState.PENDING || req.state == RequestState.CREATED) {
                req.copy(state = RequestState.CANCELLED, errorMessage = "Session terminated before approval")
            } else req
            activeRequestsMap[cleanReq.id] = cleanReq
        }
        emitSnapshots()
    }

    /**
     * Primary interception pipeline:
     * WEBSITE -> NETWORK INTERCEPTOR -> REQUEST CLASSIFICATION -> USER APPROVAL ->
     * ALLOW / BLOCK / EDIT -> NETWORK TRANSMISSION -> RESPONSE INSPECTION -> WEBSITE
     */
    suspend fun interceptAndProcess(rawRequest: InterceptedRequest): InterceptedRequest {
        // 1. Validate basic URL before processing
        val urlValidation = SecurityValidator.validateUrl(rawRequest.url)
        if (!urlValidation.isValid) {
            val failed = rawRequest.copy(
                state = RequestState.BLOCKED,
                errorMessage = urlValidation.errors.joinToString("; ")
            )
            recordAndPersist(failed)
            return failed
        }

        // 2. Request Classification & Initial State (CREATED)
        val createdRequest = rawRequest.copy(
            host = InterceptedRequest.extractHost(rawRequest.url),
            path = InterceptedRequest.extractPath(rawRequest.url),
            queryParams = InterceptedRequest.extractQueryParams(rawRequest.url),
            cookies = InterceptedRequest.extractCookies(rawRequest.headers),
            contentType = InterceptedRequest.findHeaderIgnoreCase(rawRequest.headers, "Content-Type")
                ?: rawRequest.contentType,
            state = RequestState.CREATED
        )
        recordAndPersist(createdRequest)

        // 3. Evaluate Domain Firewall Policy
        val policy = firewallEngine.evaluateDomain(createdRequest.host)
        val withPolicy = createdRequest.copy(matchedPolicy = policy)

        when (policy) {
            DomainPolicyAction.ALWAYS_BLOCK -> {
                val blocked = withPolicy.copy(
                    state = RequestState.BLOCKED,
                    errorMessage = "Blocked by Domain Firewall Rule (${createdRequest.host})"
                )
                recordAndPersist(blocked)
                return blocked
            }
            DomainPolicyAction.ALWAYS_ALLOW,
            DomainPolicyAction.ALLOW_ONCE,
            DomainPolicyAction.TEMPORARY_ALLOW -> {
                val approved = withPolicy.copy(state = RequestState.APPROVED)
                recordAndPersist(approved)
                return transmitApprovedRequest(approved)
            }
            DomainPolicyAction.ASK_EVERY_TIME -> {
                if (!strictInterceptionMode) {
                    val autoApproved = withPolicy.copy(state = RequestState.APPROVED)
                    recordAndPersist(autoApproved)
                    return transmitApprovedRequest(autoApproved)
                }
                // Fall through to Human-in-the-Loop Pending Queue
            }
        }

        // 4. Human-in-the-Loop Approval Gate (PENDING)
        // Fundamental rule: NEVER transmit a PENDING request.
        val pendingReq = withPolicy.copy(state = RequestState.PENDING)
        val deferred = CompletableDeferred<UserApprovalDecision>()
        pendingDeferredMap[pendingReq.id] = deferred
        recordAndPersist(pendingReq)

        val decision: UserApprovalDecision? = try {
            withTimeoutOrNull(approvalTimeoutMs) {
                deferred.await()
            }
        } finally {
            pendingDeferredMap.remove(pendingReq.id)
        }

        // 5. Handle timeout (Fail-Closed!)
        if (decision == null) {
            val timedOut = pendingReq.copy(
                state = RequestState.TIMED_OUT,
                errorMessage = "Approval timed out after ${approvalTimeoutMs}ms (Fail-Closed)"
            )
            recordAndPersist(timedOut)
            return timedOut
        }

        // 6. Process User Decision: ALLOW / BLOCK / EDIT & SEND
        return when (decision) {
            is UserApprovalDecision.AllowOriginal -> {
                val approved = pendingReq.copy(state = RequestState.APPROVED)
                recordAndPersist(approved)
                transmitApprovedRequest(approved)
            }
            is UserApprovalDecision.Block -> {
                val blocked = pendingReq.copy(
                    state = RequestState.BLOCKED,
                    errorMessage = decision.reason
                )
                recordAndPersist(blocked)
                blocked
            }
            is UserApprovalDecision.EditAndSend -> {
                val modifiedResult = applyDraftModifications(pendingReq, decision.draft)
                if (modifiedResult.isFailure) {
                    val invalid = pendingReq.copy(
                        state = RequestState.FAILED,
                        errorMessage = modifiedResult.exceptionOrNull()?.message ?: "Invalid request edit"
                    )
                    recordAndPersist(invalid)
                    invalid
                } else {
                    val modifiedReq = modifiedResult.getOrThrow()
                    recordAndPersist(modifiedReq)
                    transmitApprovedRequest(modifiedReq)
                }
            }
        }
    }

    fun approveRequest(requestId: String): Boolean {
        val deferred = pendingDeferredMap[requestId] ?: return false
        return deferred.complete(UserApprovalDecision.AllowOriginal)
    }

    fun blockRequest(requestId: String, reason: String = "Blocked by user"): Boolean {
        val deferred = pendingDeferredMap[requestId] ?: return false
        return deferred.complete(UserApprovalDecision.Block(reason))
    }

    fun editAndSendRequest(requestId: String, draft: RequestEditDraft): ValidationResultAndComplete {
        val validation = SecurityValidator.validateDraft(draft)
        if (!validation.isValid) {
            return ValidationResultAndComplete(false, validation.errors)
        }
        val deferred = pendingDeferredMap[requestId]
            ?: return ValidationResultAndComplete(false, listOf("Request is no longer pending (may have timed out)."))
        val completed = deferred.complete(UserApprovalDecision.EditAndSend(draft))
        return ValidationResultAndComplete(completed, if (completed) emptyList() else listOf("Request already completed."))
    }

    fun cancelPendingRequest(requestId: String) {
        val deferred = pendingDeferredMap.remove(requestId)
        if (deferred != null) {
            deferred.cancel()
            val existing = activeRequestsMap[requestId]
            if (existing != null) {
                val cancelled = existing.copy(
                    state = RequestState.CANCELLED,
                    errorMessage = "Cancelled before transmission"
                )
                engineScope.launch { recordAndPersist(cancelled) }
            }
        }
    }

    fun approveAllPending() {
        val ids = pendingDeferredMap.keys.toList()
        ids.forEach { approveRequest(it) }
    }

    fun blockAllPending() {
        val ids = pendingDeferredMap.keys.toList()
        ids.forEach { blockRequest(it, "Batch blocked by user") }
    }

    /**
     * Replays a previously captured request (or modified copy) through the pipeline.
     */
    fun replayRequestAsync(original: InterceptedRequest, onComplete: (InterceptedRequest) -> Unit = {}) {
        engineScope.launch {
            val replayReq = original.copy(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                state = RequestState.CREATED,
                source = InterceptionSource.MANUAL_REPLAY,
                response = null,
                errorMessage = null
            )
            val result = interceptAndProcess(replayReq)
            onComplete(result)
        }
    }

    suspend fun clearAllHistory() {
        val pendingIds = pendingDeferredMap.keys.toSet()
        val toRemove = activeRequestsMap.keys.filter { it !in pendingIds }
        toRemove.forEach { activeRequestsMap.remove(it) }
        trafficDao?.clearAllTraffic()
        emitSnapshots()
    }

    private fun applyDraftModifications(
        original: InterceptedRequest,
        draft: RequestEditDraft
    ): Result<InterceptedRequest> {
        val validation = SecurityValidator.validateDraft(draft)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
        }

        // Reconstruct URL if query parameters were edited in the structured editor
        val urlQueryFromText = InterceptedRequest.extractQueryParams(draft.url.trim())
        val finalUrl = if (draft.queryParams != original.queryParams && draft.queryParams != urlQueryFromText) {
            InterceptedRequest.buildUrlWithQueryParams(draft.url.trim(), draft.queryParams)
        } else {
            draft.url.trim()
        }

        val finalHeaders = LinkedHashMap<String, String>()
        draft.headers.forEach { (k, v) ->
            if (!k.equals("Cookie", ignoreCase = true)) {
                finalHeaders[k.trim()] = v
            }
        }
        if (draft.cookies.isNotEmpty()) {
            finalHeaders["Cookie"] = InterceptedRequest.formatCookieHeader(draft.cookies)
        }

        val normalizedDraft = draft.copy(
            url = finalUrl,
            method = draft.method.trim().uppercase(),
            headers = finalHeaders,
            queryParams = InterceptedRequest.extractQueryParams(finalUrl)
        )

        val summary = SecurityValidator.computeModificationSummary(original, normalizedDraft)
        val contentType = InterceptedRequest.findHeaderIgnoreCase(finalHeaders, "Content-Type")
            ?: original.contentType

        return Result.success(
            original.copy(
                url = finalUrl,
                method = normalizedDraft.method,
                host = InterceptedRequest.extractHost(finalUrl),
                path = InterceptedRequest.extractPath(finalUrl),
                queryParams = normalizedDraft.queryParams,
                headers = finalHeaders,
                cookies = draft.cookies,
                contentType = contentType,
                body = draft.body,
                state = RequestState.MODIFIED,
                wasModified = summary.isNotEmpty(),
                modificationSummary = summary
            )
        )
    }

    private suspend fun transmitApprovedRequest(request: InterceptedRequest): InterceptedRequest {
        // Guardrail: Ensure neither PENDING nor BLOCKED nor TIMED_OUT requests can ever be transmitted
        check(request.state == RequestState.APPROVED || request.state == RequestState.MODIFIED) {
            "Illegal state transition: cannot transmit request in state ${request.state}"
        }

        val sentState = request.copy(state = RequestState.SENT)
        recordAndPersist(sentState)

        val execResult = networkExecutor.execute(sentState, followRedirects = followRedirects)
        return execResult.fold(
            onSuccess = { response ->
                val completed = sentState.copy(
                    state = RequestState.COMPLETED,
                    response = response,
                    errorMessage = null
                )
                recordAndPersist(completed)
                completed
            },
            onFailure = { err ->
                val failed = sentState.copy(
                    state = RequestState.FAILED,
                    errorMessage = err.message ?: "Network transmission error"
                )
                recordAndPersist(failed)
                failed
            }
        )
    }

    private suspend fun recordAndPersist(request: InterceptedRequest) {
        activeRequestsMap[request.id] = request
        emitSnapshots()
        if (!request.isMockPreview && request.state != RequestState.CREATED) {
            try {
                trafficDao?.upsertTraffic(TrafficEntityMapper.toEntity(request))
            } catch (_: Exception) {
            }
        }
    }

    private fun emitSnapshots() {
        val all = activeRequestsMap.values.sortedByDescending { it.timestamp }
        _liveTraffic.value = all
        _pendingRequests.value = all
            .filter { it.state == RequestState.PENDING }
            .sortedBy { it.timestamp }
    }
}

data class ValidationResultAndComplete(
    val success: Boolean,
    val errors: List<String> = emptyList()
)
