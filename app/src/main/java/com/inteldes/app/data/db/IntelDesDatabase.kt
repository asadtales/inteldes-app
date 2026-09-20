package com.inteldes.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecordingEntity::class,
        TopicEntity::class,
        DecisionEntity::class,
        QuestionEntity::class,
        ActionItemEntity::class,
        TranscriptSegmentEntity::class,
        ChatMessageEntity::class,
        CategoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class IntelDesDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun notesDao(): NotesDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun chatDao(): ChatDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var instance: IntelDesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_label` ON `categories` (`label`)")
            }
        }

        fun get(context: Context): IntelDesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IntelDesDatabase::class.java,
                    "inteldes.db",
                ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
