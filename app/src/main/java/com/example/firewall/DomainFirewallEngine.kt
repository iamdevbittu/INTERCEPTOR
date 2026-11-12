package com.example.firewall

import com.example.database.DomainRuleDao
import com.example.database.DomainRuleEntity
import com.example.database.RuleHistoryEntity
import com.example.network.DomainPolicyAction
import com.example.network.DomainPolicyEngineContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class DomainFirewallEngine(
    private val ruleDao: DomainRuleDao? = null,
    seedDefaultGoogleAllowRules: Boolean = false
) : DomainPolicyEngineContract {

    private val inMemoryRules = ConcurrentHashMap<String, DomainRuleEntity>()
    private val _rulesState = MutableStateFlow<List<DomainRuleEntity>>(emptyList())
    val rulesState: StateFlow<List<DomainRuleEntity>> = _rulesState.asStateFlow()

    val ruleHistoryFlow: Flow<List<RuleHistoryEntity>>? = ruleDao?.observeRuleHistory()

    init {
        if (seedDefaultGoogleAllowRules) {
            seedBuiltInWhitelistedDomains()
        }
    }

    /**
     * Seeds default trusted rules for www.google.com and its required asset domains
     * synchronously so the default home page (www.google.com) loads immediately
     * without any network block.
     */
    fun seedBuiltInWhitelistedDomains() {
        val now = System.currentTimeMillis()
        val defaultDomains = listOf(
            "www.google.com" to "Default Home Screen (Always Allowed)",
            "*.google.com" to "Google Search & Core Services",
            "google.com" to "Google Root Domain",
            "*.gstatic.com" to "Google Static Assets & Fonts",
            "*.googleapis.com" to "Google Web APIs",
            "*.googleusercontent.com" to "Google Media & Assets"
        )
        defaultDomains.forEachIndexed { idx, (pattern, note) ->
            if (!inMemoryRules.containsKey(pattern)) {
                inMemoryRules[pattern] = DomainRuleEntity(
                    domainPattern = pattern,
                    actionName = DomainPolicyAction.ALWAYS_ALLOW.name,
                    priority = idx + 1,
                    createdAt = now,
                    updatedAt = now,
                    notes = note
                )
            }
        }
        refreshSortedState()
    }

    suspend fun loadFromDatabase() {
        val loaded = ruleDao?.getAllRules() ?: emptyList()
        loaded.forEach { inMemoryRules[it.domainPattern.lowercase()] = it }
        // Persist any seeded in-memory rules that aren't in DB yet
        inMemoryRules.values.forEach { rule ->
            ruleDao?.upsertRule(rule)
        }
        refreshSortedState()
    }

    override suspend fun evaluateDomain(host: String): DomainPolicyAction {
        val normalizedHost = host.trim().lowercase().substringBefore(":")
        val fullHostWithPort = host.trim().lowercase()
        val now = System.currentTimeMillis()

        val candidates = inMemoryRules.values.sortedWith(
            compareBy<DomainRuleEntity> { it.priority }
                .thenBy { it.domainPattern.startsWith("*.") }
        )

        for (rule in candidates) {
            if (matchesDomain(normalizedHost, fullHostWithPort, rule.domainPattern.lowercase())) {
                if (rule.action == DomainPolicyAction.TEMPORARY_ALLOW &&
                    rule.expiresAtMillis != null &&
                    now > rule.expiresAtMillis
                ) {
                    removeRule(rule.domainPattern, "Temporary allow expired -> reverted to ASK")
                    return DomainPolicyAction.ASK_EVERY_TIME
                }

                if (rule.action == DomainPolicyAction.ALLOW_ONCE) {
                    val remaining = (rule.remainingAllowance ?: 1) - 1
                    if (remaining <= 0) {
                        removeRule(rule.domainPattern, "Allow-once consumed -> reverted to ASK")
                    } else {
                        val updated = rule.copy(
                            remainingAllowance = remaining,
                            hitCount = rule.hitCount + 1,
                            allowedCount = rule.allowedCount + 1,
                            updatedAt = now
                        )
                        saveRuleInternal(updated)
                    }
                    return DomainPolicyAction.ALLOW_ONCE
                }

                val isAllowed = rule.action == DomainPolicyAction.ALWAYS_ALLOW ||
                    rule.action == DomainPolicyAction.TEMPORARY_ALLOW
                val isBlocked = rule.action == DomainPolicyAction.ALWAYS_BLOCK
                val updated = rule.copy(
                    hitCount = rule.hitCount + 1,
                    allowedCount = rule.allowedCount + (if (isAllowed) 1 else 0),
                    blockedCount = rule.blockedCount + (if (isBlocked) 1 else 0),
                    updatedAt = now
                )
                saveRuleInternal(updated)
                return rule.action
            }
        }

        return DomainPolicyAction.ASK_EVERY_TIME
    }

    override suspend fun setDomainPolicy(
        domain: String,
        action: DomainPolicyAction,
        priority: Int,
        notes: String
    ) {
        val pattern = domain.trim().lowercase()
        if (pattern.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = inMemoryRules[pattern]

        val expiresAt = if (action == DomainPolicyAction.TEMPORARY_ALLOW) {
            now + 15 * 60 * 1000L
        } else null

        val allowance = if (action == DomainPolicyAction.ALLOW_ONCE) 1 else null

        val entity = DomainRuleEntity(
            domainPattern = pattern,
            actionName = action.name,
            priority = priority,
            expiresAtMillis = expiresAt,
            remainingAllowance = allowance,
            hitCount = existing?.hitCount ?: 0L,
            blockedCount = existing?.blockedCount ?: 0L,
            allowedCount = existing?.allowedCount ?: 0L,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            notes = notes
        )

        saveRuleInternal(entity)
        ruleDao?.insertRuleHistory(
            RuleHistoryEntity(
                domainPattern = pattern,
                previousAction = existing?.actionName,
                newAction = action.name,
                priority = priority,
                reason = notes.ifBlank { "Updated by user" },
                timestamp = now
            )
        )
    }

    suspend fun removeRule(domain: String, reason: String = "Rule deleted by user") {
        val pattern = domain.trim().lowercase()
        val prev = inMemoryRules.remove(pattern)
        ruleDao?.deleteRule(pattern)
        if (prev != null) {
            ruleDao?.insertRuleHistory(
                RuleHistoryEntity(
                    domainPattern = pattern,
                    previousAction = prev.actionName,
                    newAction = "DELETED (Default ASK)",
                    priority = prev.priority,
                    reason = reason
                )
            )
        }
        refreshSortedState()
    }

    private suspend fun saveRuleInternal(entity: DomainRuleEntity) {
        inMemoryRules[entity.domainPattern.lowercase()] = entity
        ruleDao?.upsertRule(entity)
        refreshSortedState()
    }

    private fun refreshSortedState() {
        _rulesState.value = inMemoryRules.values.sortedWith(
            compareBy<DomainRuleEntity> { it.priority }
                .thenByDescending { it.updatedAt }
        )
    }

    companion object {
        fun matchesDomain(hostWithoutPort: String, hostWithPort: String, pattern: String): Boolean {
            if (pattern == "*") return true
            if (pattern == hostWithoutPort || pattern == hostWithPort) return true
            if (pattern.startsWith("*.")) {
                val suffix = pattern.removePrefix("*.")
                return hostWithoutPort == suffix || hostWithoutPort.endsWith(".$suffix")
            }
            return false
        }
    }
}
