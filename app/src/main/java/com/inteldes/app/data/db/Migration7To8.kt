package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v7 -> v8: web sync. Purely additive: nullable `uuid` columns on the child rows the server needs ids for
 * (null = derive from content, so every existing row keeps working untouched) and the per-recording sync
 * state table. No row is rewritten or removed.
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recording_marks` ADD COLUMN `uuid` TEXT")
        db.execSQL("ALTER TABLE `photos` ADD COLUMN `uuid` TEXT")
        db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `uuid` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `web_sync_state` (" +
                "`recordingId` TEXT NOT NULL, `serverVersion` INTEGER NOT NULL, `transcriptVersion` INTEGER NOT NULL, " +
                "`notesVersion` INTEGER NOT NULL, `metaHash` TEXT NOT NULL, `transcriptHash` TEXT NOT NULL, " +
                "`notesHash` TEXT NOT NULL, `marksHash` TEXT NOT NULL, `chatHash` TEXT NOT NULL, `audioSha` TEXT, " +
                "`audioLen` INTEGER NOT NULL, `pushedMarksJson` TEXT NOT NULL, `audioMissing` INTEGER NOT NULL, " +
                "PRIMARY KEY(`recordingId`))",
        )
    }
}
