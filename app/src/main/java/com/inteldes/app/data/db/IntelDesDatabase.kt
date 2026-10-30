package com.inteldes.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Bump [version] on EVERY schema change AND add a real migration: Room refuses to open a database
 * whose layout differs from the declared one but carries the same version number (crash on launch
 * for anyone who had an earlier build installed). There is deliberately NO destructive fallback:
 * a missing migration must fail loudly instead of silently deleting every recording.
 */
@Database(
    entities = [
        RecordingEntity::class,
        NoteSectionEntity::class,
        TemplateEntity::class,
        PhotoEntity::class,
        TranscriptSegmentEntity::class,
        ChatMessageEntity::class,
        CategoryEntity::class,
        NotulensiHistoryEntity::class,
        RecordingMarkEntity::class,
        KnowledgeContextEntity::class,
        ContextSourceEntity::class,
        WebSyncStateEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class IntelDesDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun noteSectionDao(): NoteSectionDao
    abstract fun templateDao(): TemplateDao
    abstract fun photoDao(): PhotoDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun chatDao(): ChatDao
    abstract fun categoryDao(): CategoryDao
    abstract fun notulensiHistoryDao(): NotulensiHistoryDao
    abstract fun recordingMarkDao(): RecordingMarkDao
    abstract fun contextDao(): ContextDao
    abstract fun webSyncDao(): WebSyncDao

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
                backUpBeforeMigration(context.applicationContext)
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    IntelDesDatabase::class.java,
                    "inteldes.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
            }

        /**
         * Before Room migrates an older file, keep a copy next to it (`inteldes_v<old>.bak`, once per old version).
         * It is only ever read back by a person; nothing deletes it automatically.
         */
        private fun backUpBeforeMigration(context: Context) {
            try {
                val file = context.getDatabasePath("inteldes.db")
                if (!file.exists()) return
                val old = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { it.version }
                if (old <= 0 || old >= 8) return
                val bak = java.io.File(file.parentFile, "inteldes_v$old.bak")
                if (!bak.exists()) {
                    // The WAL may hold recent writes, so checkpoint through a normal open first.
                    android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                        it.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c -> c.moveToFirst() }
                    }
                    file.copyTo(bak, overwrite = false)
                }
            } catch (_: Exception) {
                // A failed backup must not stop the app from opening its own database.
            }
        }
    }
}
