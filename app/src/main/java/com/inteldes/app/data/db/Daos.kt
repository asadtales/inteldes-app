package com.inteldes.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE label = :label")
    suspend fun delete(label: String)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeOne(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getOne(id: String): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecordingEntity)

    @Update
    suspend fun update(entity: RecordingEntity)

    @Delete
    suspend fun delete(entity: RecordingEntity)

    @Query("UPDATE recordings SET kind = :newKind WHERE kind = :oldKind")
    suspend fun reassignKind(oldKind: String, newKind: String)
}

@Dao
interface RecordingMarkDao {
    @Query("SELECT * FROM recording_marks WHERE recordingId = :recordingId ORDER BY timestampSec ASC")
    fun observeForRecording(recordingId: String): Flow<List<RecordingMarkEntity>>

    @Insert
    suspend fun insert(entity: RecordingMarkEntity): Long

    @Insert
    suspend fun insertAll(entities: List<RecordingMarkEntity>)

    @Delete
    suspend fun delete(entity: RecordingMarkEntity)

    @Query("DELETE FROM recording_marks WHERE recordingId = :recordingId")
    suspend fun clear(recordingId: String)
}

@Dao
interface NotulensiHistoryDao {
    @Query("SELECT * FROM notulensi_history WHERE recordingId = :recordingId ORDER BY generatedAt DESC")
    fun observeForRecording(recordingId: String): Flow<List<NotulensiHistoryEntity>>

    @Insert
    suspend fun insert(entity: NotulensiHistoryEntity)
}

@Dao
interface NoteSectionDao {
    @Query("SELECT * FROM note_sections WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observe(recordingId: String): Flow<List<NoteSectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<NoteSectionEntity>)

    @Query("DELETE FROM note_sections WHERE recordingId = :recordingId")
    suspend fun clear(recordingId: String)

    @Transaction
    suspend fun replace(recordingId: String, items: List<NoteSectionEntity>) {
        clear(recordingId)
        insertAll(items)
    }
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY createdAt")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun getOne(id: String): TemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE recordingId = :recordingId ORDER BY COALESCE(takenAtSec, 2147483647), createdAt")
    fun observe(recordingId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun getOne(id: Long): PhotoEntity?

    @Insert
    suspend fun insert(entity: PhotoEntity): Long

    @Query("UPDATE photos SET caption = :caption WHERE id = :id")
    suspend fun updateCaption(id: Long, caption: String)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM photos WHERE recordingId = :recordingId")
    suspend fun clear(recordingId: String)
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeSegments(recordingId: String): Flow<List<TranscriptSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(items: List<TranscriptSegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun clear(recordingId: String)

    @Query("UPDATE transcript_segments SET speaker = :newName WHERE recordingId = :recordingId AND speaker = :oldName")
    suspend fun renameSpeaker(recordingId: String, oldName: String, newName: String)

    /** [orderIndex] is the segment's position in the transcript (the list index the UI shows). */
    @Query("UPDATE transcript_segments SET text = :text WHERE recordingId = :recordingId AND orderIndex = :orderIndex")
    suspend fun updateText(recordingId: String, orderIndex: Int, text: String)
}

@Dao
interface ContextDao {
    @Query("SELECT * FROM knowledge_contexts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<KnowledgeContextEntity>>

    @Query("SELECT * FROM knowledge_contexts WHERE id = :id")
    fun observeOne(id: String): Flow<KnowledgeContextEntity?>

    @Query("SELECT * FROM knowledge_contexts WHERE id = :id")
    suspend fun getOne(id: String): KnowledgeContextEntity?

    @Query("SELECT * FROM context_sources WHERE contextId = :contextId ORDER BY addedAt, id")
    fun observeSources(contextId: String): Flow<List<ContextSourceEntity>>

    @Query("SELECT contextId, COUNT(*) AS n FROM context_sources GROUP BY contextId")
    fun observeSourceCounts(): Flow<List<SourceCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KnowledgeContextEntity)

    @Insert
    suspend fun insertSources(items: List<ContextSourceEntity>)

    @Query("UPDATE knowledge_contexts SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE knowledge_contexts SET text = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateText(id: String, text: String, now: Long)

    @Query("DELETE FROM knowledge_contexts WHERE id = :id")
    suspend fun delete(id: String)
}

data class SourceCount(val contextId: String, val n: Int)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeMessages(recordingId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT COUNT(*) FROM chat_messages WHERE recordingId = :recordingId")
    suspend fun count(recordingId: String): Int
}

@Dao
interface WebSyncDao {
    @Query("SELECT * FROM web_sync_state WHERE recordingId = :id")
    suspend fun get(id: String): WebSyncStateEntity?

    @Query("SELECT * FROM web_sync_state")
    suspend fun all(): List<WebSyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: WebSyncStateEntity)

    @Query("DELETE FROM web_sync_state WHERE recordingId = :id")
    suspend fun remove(id: String)
}
