package com.inteldes.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Bump [version] on EVERY schema change: Room refuses to open a database whose layout differs from
 * the declared one but carries the same version number (crash on launch for anyone who had an
 * earlier build installed). The destructive fallback below then recreates the tables.
 */
@Database(
    entities = [
        RecordingEntity::class,
        NoteSectionEntity::class,
        TemplateEntity::class,
        PhotoEntity::class,
        TranscriptSegmentEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class IntelDesDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun noteSectionDao(): NoteSectionDao
    abstract fun templateDao(): TemplateDao
    abstract fun photoDao(): PhotoDao
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
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
