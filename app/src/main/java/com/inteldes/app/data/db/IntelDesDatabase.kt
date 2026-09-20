package com.inteldes.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecordingEntity::class,
        TopicEntity::class,
        DecisionEntity::class,
        QuestionEntity::class,
        ActionItemEntity::class,
        TranscriptSegmentEntity::class,
        ChatMessageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class IntelDesDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun notesDao(): NotesDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: IntelDesDatabase? = null

        fun get(context: Context): IntelDesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IntelDesDatabase::class.java,
                    "inteldes.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}
