package com.example.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.network.DomainPolicyAction
import com.example.network.InterceptedRequest
import com.example.network.InterceptedResponse
import com.example.network.InterceptionSource
import com.example.network.RequestState
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "traffic_records")
data class TrafficRecordEntity(
    @PrimaryKey val id: String,
    val url: String,
    val method: String,
    val host: String,
    val path: String,
    val headersJson: String,
    val queryParamsJson: String,
    val cookiesJson: String,
    val contentType: String?,
    val body: String?,
    val isBinaryBody: Boolean,
    val timestamp: Long,
    val stateName: String,
    val sourceName: String,
    val wasModified: Boolean,
    val originalUrl: String,
    val originalMethod: String,
    val originalHeadersJson: String,
    val originalBody: String?,
    val modificationSummaryJson: String,
    val matchedPolicyName: String?,
    val responseStatusCode: Int?,
    val responseStatusMessage: String?,
    val responseHeadersJson: String?,
    val responseBody: String?,
    val responseIsBinary: Boolean,
    val responseContentType: String?,
    val responseSizeBytes: Long,
    val responseDurationMs: Long,
    val redirectChainJson: String?,
    val tlsVersion: String?,
    val cipherSuite: String?,
    val certificateInfo: String?,
    val errorMessage: String?
)

@Entity(tableName = "domain_rules")
data class DomainRuleEntity(
    @PrimaryKey val domainPattern: String,
    val actionName: String,
    val priority: Int = 100,
    val expiresAtMillis: Long? = null,
    val remainingAllowance: Int? = null,
    val hitCount: Long = 0L,
    val blockedCount: Long = 0L,
    val allowedCount: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
) {
    val action: DomainPolicyAction
        get() = try {
            DomainPolicyAction.valueOf(actionName)
        } catch (_: Exception) {
            DomainPolicyAction.ASK_EVERY_TIME
        }
}

@Entity(tableName = "rule_history")
data class RuleHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domainPattern: String,
    val previousAction: String?,
    val newAction: String,
    val priority: Int,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TrafficDao {
    @Query("SELECT * FROM traffic_records ORDER BY timestamp DESC")
    fun observeAllTraffic(): Flow<List<TrafficRecordEntity>>

    @Query("SELECT * FROM traffic_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTraffic(limit: Int = 500): List<TrafficRecordEntity>

    @Query("SELECT * FROM traffic_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TrafficRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTraffic(record: TrafficRecordEntity)

    @Query("DELETE FROM traffic_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM traffic_records")
    suspend fun clearAllTraffic()
}

@Dao
interface DomainRuleDao {
    @Query("SELECT * FROM domain_rules ORDER BY priority ASC, updatedAt DESC")
    fun observeRules(): Flow<List<DomainRuleEntity>>

    @Query("SELECT * FROM domain_rules ORDER BY priority ASC, updatedAt DESC")
    suspend fun getAllRules(): List<DomainRuleEntity>

    @Query("SELECT * FROM domain_rules WHERE domainPattern = :domain LIMIT 1")
    suspend fun getRuleForDomain(domain: String): DomainRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: DomainRuleEntity)

    @Query("DELETE FROM domain_rules WHERE domainPattern = :domain")
    suspend fun deleteRule(domain: String)

    @Query("DELETE FROM domain_rules")
    suspend fun clearAllRules()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRuleHistory(entry: RuleHistoryEntity)

    @Query("SELECT * FROM rule_history ORDER BY timestamp DESC LIMIT 100")
    fun observeRuleHistory(): Flow<List<RuleHistoryEntity>>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteBookmark(url: String)
}

@Database(
    entities = [
        TrafficRecordEntity::class,
        DomainRuleEntity::class,
        RuleHistoryEntity::class,
        BookmarkEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class InterceptorDatabase : RoomDatabase() {
    abstract fun trafficDao(): TrafficDao
    abstract fun domainRuleDao(): DomainRuleDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var INSTANCE: InterceptorDatabase? = null

        fun getInstance(context: Context): InterceptorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    InterceptorDatabase::class.java,
                    "interceptor_firewall.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

object TrafficEntityMapper {
    fun toEntity(req: InterceptedRequest): TrafficRecordEntity {
        val resp = req.response
        return TrafficRecordEntity(
            id = req.id,
            url = req.url,
            method = req.method,
            host = req.host,
            path = req.path,
            headersJson = mapToJson(req.headers),
            queryParamsJson = mapToJson(req.queryParams),
            cookiesJson = mapToJson(req.cookies),
            contentType = req.contentType,
            body = req.body,
            isBinaryBody = req.isBinaryBody,
            timestamp = req.timestamp,
            stateName = req.state.name,
            sourceName = req.source.name,
            wasModified = req.wasModified,
            originalUrl = req.originalUrl,
            originalMethod = req.originalMethod,
            originalHeadersJson = mapToJson(req.originalHeaders),
            originalBody = req.originalBody,
            modificationSummaryJson = listToJson(req.modificationSummary),
            matchedPolicyName = req.matchedPolicy?.name,
            responseStatusCode = resp?.statusCode,
            responseStatusMessage = resp?.statusMessage,
            responseHeadersJson = resp?.let { mapToJson(it.headers) },
            responseBody = resp?.body,
            responseIsBinary = resp?.isBinary ?: false,
            responseContentType = resp?.contentType,
            responseSizeBytes = resp?.sizeBytes ?: 0L,
            responseDurationMs = resp?.durationMs ?: 0L,
            redirectChainJson = resp?.let { listToJson(it.redirectChain) },
            tlsVersion = resp?.tlsVersion,
            cipherSuite = resp?.cipherSuite,
            certificateInfo = resp?.certificateInfo,
            errorMessage = req.errorMessage
        )
    }

    fun fromEntity(entity: TrafficRecordEntity): InterceptedRequest {
        val resp = if (entity.responseStatusCode != null) {
            InterceptedResponse(
                statusCode = entity.responseStatusCode,
                statusMessage = entity.responseStatusMessage ?: "",
                headers = jsonToMap(entity.responseHeadersJson),
                body = entity.responseBody,
                isBinary = entity.responseIsBinary,
                contentType = entity.responseContentType,
                sizeBytes = entity.responseSizeBytes,
                durationMs = entity.responseDurationMs,
                redirectChain = jsonToList(entity.redirectChainJson),
                tlsVersion = entity.tlsVersion,
                cipherSuite = entity.cipherSuite,
                certificateInfo = entity.certificateInfo
            )
        } else null

        val state = try {
            RequestState.valueOf(entity.stateName)
        } catch (_: Exception) {
            RequestState.COMPLETED
        }

        val source = try {
            InterceptionSource.valueOf(entity.sourceName)
        } catch (_: Exception) {
            InterceptionSource.WEBVIEW_NATIVE
        }

        val policy = entity.matchedPolicyName?.let {
            try {
                DomainPolicyAction.valueOf(it)
            } catch (_: Exception) {
                null
            }
        }

        return InterceptedRequest(
            id = entity.id,
            url = entity.url,
            method = entity.method,
            host = entity.host,
            path = entity.path,
            queryParams = jsonToMap(entity.queryParamsJson),
            headers = jsonToMap(entity.headersJson),
            cookies = jsonToMap(entity.cookiesJson),
            contentType = entity.contentType,
            body = entity.body,
            isBinaryBody = entity.isBinaryBody,
            timestamp = entity.timestamp,
            state = state,
            source = source,
            wasModified = entity.wasModified,
            originalUrl = entity.originalUrl,
            originalMethod = entity.originalMethod,
            originalHeaders = jsonToMap(entity.originalHeadersJson),
            originalBody = entity.originalBody,
            modificationSummary = jsonToList(entity.modificationSummaryJson),
            matchedPolicy = policy,
            response = resp,
            errorMessage = entity.errorMessage
        )
    }

    private fun mapToJson(map: Map<String, String>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun jsonToMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.optString(key, "")
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun listToJson(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun jsonToList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(arr.optString(i, ""))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
