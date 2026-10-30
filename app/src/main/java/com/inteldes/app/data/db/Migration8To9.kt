package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v8 -> v9: remember the server's `updatedAt` per synced recording, so marks, photos and chat that the web adds
 * (which move no version number) are noticed. Purely additive: one column with a default, no row is rewritten.
 */
internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `web_sync_state` ADD COLUMN `serverUpdatedAt` INTEGER NOT NULL DEFAULT 0")
    }
}
