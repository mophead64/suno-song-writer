package app.sunosongwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BackendDao {
    @Query("SELECT * FROM backend ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BackendEntity>>

    @Query("SELECT * FROM backend ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<BackendEntity>

    @Query("SELECT * FROM backend WHERE id = :id")
    suspend fun get(id: Long): BackendEntity?

    @Query("SELECT * FROM backend WHERE id = :id")
    fun observe(id: Long): Flow<BackendEntity?>

    @Insert
    suspend fun insert(backend: BackendEntity): Long

    @Update
    suspend fun update(backend: BackendEntity)

    @Query("DELETE FROM backend WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ModelInfoDao {
    @Query("SELECT * FROM model_info WHERE backendId = :backendId ORDER BY available DESC, modelId COLLATE NOCASE")
    fun observeForBackend(backendId: Long): Flow<List<ModelInfoEntity>>

    @Query("SELECT * FROM model_info ORDER BY modelId COLLATE NOCASE")
    fun observeAll(): Flow<List<ModelInfoEntity>>

    @Query("SELECT * FROM model_info WHERE backendId = :backendId")
    suspend fun getForBackend(backendId: Long): List<ModelInfoEntity>

    @Query("SELECT * FROM model_info WHERE backendId = :backendId AND modelId = :modelId")
    suspend fun get(backendId: Long, modelId: String): ModelInfoEntity?

    @Upsert
    suspend fun upsertAll(models: List<ModelInfoEntity>)

    /** Applies a fresh model list: upserts what was seen and marks the rest unavailable (spec 5.2: model churn). */
    @Transaction
    suspend fun replaceListing(backendId: Long, seen: List<ModelInfoEntity>) {
        val seenIds = seen.mapTo(HashSet()) { it.modelId }
        val missing = getForBackend(backendId).filter { it.modelId !in seenIds && it.available }
        upsertAll(seen + missing.map { it.copy(available = false) })
    }
}

@Dao
interface ParticipantDao {
    @Query("SELECT * FROM participant ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ParticipantEntity>>

    @Query("SELECT * FROM participant WHERE id = :id")
    suspend fun get(id: Long): ParticipantEntity?

    @Insert
    suspend fun insert(participant: ParticipantEntity): Long

    @Update
    suspend fun update(participant: ParticipantEntity)

    @Query("DELETE FROM participant WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RequestCountDao {
    @Query("SELECT count FROM request_count WHERE backendId = :backendId AND modelId = :modelId AND day = :day")
    suspend fun countForModel(backendId: Long, modelId: String, day: String): Int?

    @Query("SELECT COALESCE(SUM(count), 0) FROM request_count WHERE backendId = :backendId AND day = :day")
    suspend fun countForBackend(backendId: Long, day: String): Int

    @Query("SELECT * FROM request_count WHERE day >= :sinceDay")
    fun observeSince(sinceDay: String): Flow<List<RequestCountEntity>>

    @Query("UPDATE request_count SET count = count + 1 WHERE backendId = :backendId AND modelId = :modelId AND day = :day")
    suspend fun bump(backendId: Long, modelId: String, day: String): Int

    @Insert
    suspend fun insert(row: RequestCountEntity)

    /** Update-or-insert; SQL `ON CONFLICT … DO UPDATE` needs SQLite 3.24 (Android 11+). */
    @Transaction
    suspend fun increment(backendId: Long, modelId: String, day: String) {
        if (bump(backendId, modelId, day) == 0) insert(RequestCountEntity(backendId, modelId, day, 1))
    }

    @Query("DELETE FROM request_count WHERE day < :beforeDay")
    suspend fun deleteBefore(beforeDay: String)
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(record: UsageRecordEntity): Long

    @Query("SELECT * FROM usage_record WHERE at >= :from ORDER BY at")
    fun observeSince(from: Long): Flow<List<UsageRecordEntity>>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_record WHERE at >= :from")
    suspend fun costSince(from: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_record WHERE at >= :from")
    fun observeCostSince(from: Long): Flow<Double>

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_record WHERE sessionId = :sessionId")
    suspend fun sessionCost(sessionId: Long): Double

    @Query("SELECT COALESCE(SUM(costUsd), 0) FROM usage_record WHERE sessionId = :sessionId")
    fun observeSessionCost(sessionId: Long): Flow<Double>

    @Query("SELECT * FROM price_entry ORDER BY backendId, modelId")
    fun observePrices(): Flow<List<PriceEntryEntity>>

    @Query("SELECT * FROM price_entry WHERE backendId = :backendId AND modelId = :modelId")
    suspend fun price(backendId: Long, modelId: String): PriceEntryEntity?

    @Upsert
    suspend fun upsertPrice(price: PriceEntryEntity)

    @Query("DELETE FROM price_entry WHERE backendId = :backendId AND modelId = :modelId")
    suspend fun deletePrice(backendId: Long, modelId: String)
}
