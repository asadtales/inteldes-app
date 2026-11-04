package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v9 -> v10: photo geotag (latitude, longitude, accuracy, place name). Purely additive nullable columns: no row
 * is rewritten. The server stores a geotag per photo and a sync that does not carry it back would erase it.
 */
internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `photos` ADD COLUMN `latitude` REAL")
        db.execSQL("ALTER TABLE `photos` ADD COLUMN `longitude` REAL")
        db.execSQL("ALTER TABLE `photos` ADD COLUMN `accuracyM` REAL")
        db.execSQL("ALTER TABLE `photos` ADD COLUMN `placeName` TEXT")
    }
}
