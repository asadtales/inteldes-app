package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 -> v11: short videos attached to a recording. A new table and its index only; nothing that exists is touched.
 */
internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `videos` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordingId` TEXT NOT NULL, `filePath` TEXT NOT NULL, " +
                "`takenAtSec` INTEGER, `caption` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `uuid` TEXT NOT NULL, " +
                "`latitude` REAL, `longitude` REAL, `accuracyM` REAL, `placeName` TEXT, `durationSec` REAL, " +
                "`sizeBytes` INTEGER NOT NULL, `mime` TEXT NOT NULL, `syncedSha` TEXT, `syncError` TEXT, `deletedAt` INTEGER, " +
                "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_recordingId` ON `videos` (`recordingId`)")
    }
}
