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
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeMessages(recordingId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT COUNT(*) FROM chat_messages WHERE recordingId = :recordingId")
    suspend fun count(recordingId: String): Int
}
