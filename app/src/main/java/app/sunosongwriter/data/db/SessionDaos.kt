package app.sunosongwriter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM prompt_template ORDER BY action, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_template WHERE id = :id")
    suspend fun get(id: Long): PromptTemplateEntity?

    @Insert
    suspend fun insert(template: PromptTemplateEntity): Long

    @Update
    suspend fun update(template: PromptTemplateEntity)

    @Query("DELETE FROM prompt_template WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM template_default")
    fun observeDefaults(): Flow<List<TemplateDefaultEntity>>

    @Query("SELECT * FROM template_default")
    suspend fun defaults(): List<TemplateDefaultEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setDefault(row: TemplateDefaultEntity)

    @Query("DELETE FROM template_default WHERE action = :action AND participantId = :participantId")
    suspend fun clearDefault(action: String, participantId: Long)

    @Query("DELETE FROM template_default WHERE templateKey = :templateKey")
    suspend fun clearDefaultsFor(templateKey: String)
}

@Dao
interface SessionDao {
    /** Whether any step has ever finished, for the Getting started checklist. */
    @Query("SELECT EXISTS(SELECT 1 FROM session_message WHERE status = 'DONE')")
    fun observeAnyStepDone(): Flow<Boolean>

    @Query("SELECT * FROM session WHERE variantId = :variantId AND type = :type LIMIT 1")
    suspend fun find(variantId: Long, type: String): SessionEntity?

    @Query("SELECT * FROM session WHERE variantId = :variantId AND type = :type LIMIT 1")
    fun observe(variantId: Long, type: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE session SET fullTranscript = :full WHERE id = :id")
    suspend fun setFullTranscript(id: Long, full: Boolean)

    @Query("UPDATE session SET stateJson = :stateJson WHERE id = :id")
    suspend fun setState(id: Long, stateJson: String)

    @Query("UPDATE session SET configJson = :configJson WHERE id = :id")
    suspend fun setConfig(id: Long, configJson: String)

    @Query("SELECT * FROM session WHERE songId = :songId AND type = :type ORDER BY createdAt DESC")
    fun observeByType(songId: Long, type: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE type = :type")
    suspend fun allOfType(type: String): List<SessionEntity>

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observeMessages(sessionId: Long): Flow<List<SessionMessageEntity>>

    @Query("SELECT * FROM session_message WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun messages(sessionId: Long): List<SessionMessageEntity>

    @Query("SELECT * FROM session_message WHERE id = :id")
    suspend fun message(id: Long): SessionMessageEntity?

    @Insert
    suspend fun insertMessage(message: SessionMessageEntity): Long

    @Update
    suspend fun updateMessage(message: SessionMessageEntity)

    @Query("UPDATE session_message SET content = :content, status = :status, updatedAt = :at WHERE id = :id")
    suspend fun updateStreaming(id: Long, content: String, status: String, at: Long)

    @Query("DELETE FROM session_message WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** Manual steps left running when the process died. Relay steps are left waiting; Writers' Room steps are resumed instead. */
    @Query(
        """
        UPDATE session_message SET status = 'FAILED', error = :error, updatedAt = :at
        WHERE status IN ('PENDING', 'STREAMING', 'RATE_LIMITED') AND sessionId IN (SELECT id FROM session WHERE type = 'manual')
        """,
    )
    suspend fun failInterrupted(error: String, at: Long): Int

    /** A Writers' Room worker starting up: anything it finds mid-flight was cut off, so run it again from the start. */
    @Query("UPDATE session_message SET status = 'PENDING', content = '', error = NULL, updatedAt = :at WHERE sessionId = :sessionId AND status IN ('STREAMING', 'RATE_LIMITED')")
    suspend fun resetInterrupted(sessionId: Long, at: Long): Int

    /** Relay steps waiting for a reply, newest first, with their song for display. */
    @Query(
        """
        SELECT m.*, s.songId AS songId, s.type AS sessionType FROM session_message m JOIN session s ON s.id = m.sessionId
        WHERE m.status = 'WAITING_RELAY' ORDER BY m.createdAt DESC
        """,
    )
    fun observeWaitingRelay(): Flow<List<WaitingRelayRow>>

    @Query("SELECT * FROM suggestion WHERE messageId IN (SELECT id FROM session_message WHERE sessionId = :sessionId) ORDER BY id")
    fun observeSuggestions(sessionId: Long): Flow<List<SuggestionEntity>>

    @Query("SELECT * FROM suggestion WHERE messageId IN (SELECT id FROM session_message WHERE sessionId = :sessionId) ORDER BY id")
    suspend fun suggestions(sessionId: Long): List<SuggestionEntity>

    @Insert
    suspend fun insertSuggestions(rows: List<SuggestionEntity>)

    @Query("UPDATE suggestion SET decision = :decision, editedChange = :edited WHERE id IN (:ids)")
    suspend fun decide(ids: List<Long>, decision: String, edited: String?)

    @Query("UPDATE suggestion SET implementedInVersionId = :versionId WHERE id IN (:ids)")
    suspend fun markImplemented(ids: List<Long>, versionId: Long)
}

data class WaitingRelayRow(
    @androidx.room.Embedded val message: SessionMessageEntity,
    val songId: Long,
    val sessionType: String,
)
