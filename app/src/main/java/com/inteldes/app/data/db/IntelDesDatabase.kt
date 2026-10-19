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
        NotulensiHistoryEntity::class,
        RecordingMarkEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class IntelDesDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun notesDao(): NotesDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun chatDao(): ChatDao
    abstract fun categoryDao(): CategoryDao
    abstract fun notulensiHistoryDao(): NotulensiHistoryDao
    abstract fun recordingMarkDao(): RecordingMarkDao

    companion object {
        @Volatile private var instance: IntelDesDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_label` ON `categories` (`label`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recordings` ADD COLUMN `notulensiDensity` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notulensi_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordingId` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL, " +
                        "`density` TEXT NOT NULL, `provider` TEXT NOT NULL, `notulensiJson` TEXT NOT NULL, " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notulensi_history_recordingId` ON `notulensi_history` (`recordingId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recording_marks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`recordingId` TEXT NOT NULL, `timestampSec` INTEGER NOT NULL, " +
                        "`label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_marks_recordingId` ON `recording_marks` (`recordingId`)")
            }
        }

        fun get(context: Context): IntelDesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IntelDesDatabase::class.java,
                    "inteldes.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
