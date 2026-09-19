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
interface NotesDao {
    @Query("SELECT * FROM topics WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeTopics(recordingId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM decisions WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeDecisions(recordingId: String): Flow<List<DecisionEntity>>

    @Query("SELECT * FROM questions WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeQuestions(recordingId: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM action_items WHERE recordingId = :recordingId ORDER BY orderIndex")
    fun observeActionItems(recordingId: String): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items ORDER BY orderIndex")
    fun observeAllActionItems(): Flow<List<ActionItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopics(items: List<TopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecisions(items: List<DecisionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(items: List<QuestionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionItems(items: List<ActionItemEntity>)

    @Query("DELETE FROM topics WHERE recordingId = :recordingId")
    suspend fun clearTopics(recordingId: String)

    @Query("DELETE FROM decisions WHERE recordingId = :recordingId")
    suspend fun clearDecisions(recordingId: String)

    @Query("DELETE FROM questions WHERE recordingId = :recordingId")
    suspend fun clearQuestions(recordingId: String)

    @Query("DELETE FROM action_items WHERE recordingId = :recordingId")
    suspend fun clearActionItems(recordingId: String)

    @Transaction
    suspend fun replaceNotes(
        recordingId: String,
        topics: List<TopicEntity>,
        decisions: List<DecisionEntity>,
        questions: List<QuestionEntity>,
        actions: List<ActionItemEntity>,
    ) {
        clearTopics(recordingId); clearDecisions(recordingId); clearQuestions(recordingId); clearActionItems(recordingId)
        insertTopics(topics); insertDecisions(decisions); insertQuestions(questions); insertActionItems(actions)
    }
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
