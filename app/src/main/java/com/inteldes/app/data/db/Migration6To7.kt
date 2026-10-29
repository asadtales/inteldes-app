package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v6 -> v7: saved knowledge contexts (merged transcripts/notulensi) and the list of recordings behind each. */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `knowledge_contexts` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_sources` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contextId` TEXT NOT NULL, " +
                "`recordingId` TEXT NOT NULL, `recordingTitle` TEXT NOT NULL, `kind` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`contextId`) REFERENCES `knowledge_contexts`(`id`) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_sources_contextId` ON `context_sources` (`contextId`)")
    }
}
