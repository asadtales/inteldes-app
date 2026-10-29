package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v5 -> v6: each recording can carry a meeting location (name + optional GPS fix). */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `locationName` TEXT")
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `latitude` REAL")
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `longitude` REAL")
    }
}
