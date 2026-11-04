package com.inteldes.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real migrations on a real SQLite database on the device, against the exported schemas in app/schemas.
 * Run with:  gradlew :app:connectedDebugAndroidTest   (a phone must be connected)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IntelDesDatabase::class.java)

    private val name = "migration-test"

    private fun seedV7(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO recordings (id,title,kind,createdAt,durationSec,audioFilePath,audioMimeType,status,speakerCount,engine,templateId,templateName,locationName,latitude,longitude,errorMessage,summary,provider,notesGeneratedAt,notulensiDensity) " +
                "VALUES ('r1','Musyawarah Desa','Rapat',1700000000000,3600,'/files/a.m4a','audio/mp4','DONE',3,'CLOUD','standard','Notulensi Standar','Balai Desa',-6.2,106.8,NULL,'Ringkasan','GEMINI',1700000001000,'SEDANG')",
        )
        db.execSQL("INSERT INTO recording_marks (recordingId,timestampSec,label,createdAt) VALUES ('r1',120,'Keputusan',1700000002000),('r1',900,'Penanda 2',1700000003000)")
        db.execSQL("INSERT INTO photos (recordingId,filePath,takenAtSec,caption,createdAt) VALUES ('r1','/files/p1.jpg',300,'Foto 1',1700000004000)")
        db.execSQL("INSERT INTO chat_messages (recordingId,orderIndex,isUser,text,createdAt) VALUES ('r1',0,1,'Apa keputusannya?',1700000005000),('r1',1,0,'Anggaran disetujui.',1700000006000)")
        db.execSQL("INSERT INTO transcript_segments (recordingId,orderIndex,speaker,timestampSec,text) VALUES ('r1',0,'Pembicara 1',0,'Selamat pagi')")
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun v7ToV11KeepsEveryRowAndMatchesTheSchema() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        val db = helper.runMigrationsAndValidate(name, 11, true, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
        assertEquals(1, count(db, "recordings"))
        assertEquals(2, count(db, "recording_marks"))
        assertEquals(1, count(db, "photos"))
        assertEquals(2, count(db, "chat_messages"))
        assertEquals(1, count(db, "transcript_segments"))
        // existing rows keep working: no web id yet (it is derived from content)
        db.query("SELECT uuid FROM recording_marks").use { c -> while (c.moveToNext()) assertTrue(c.isNull(0)) }
        db.query("SELECT label FROM recording_marks ORDER BY timestampSec").use { c ->
            c.moveToFirst(); assertEquals("Keputusan", c.getString(0))
        }
    }

    @Test
    fun v8ToV9AddsTheSyncTimestampWithADefault() {
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO web_sync_state (recordingId,serverVersion,transcriptVersion,notesVersion,metaHash,transcriptHash,notesHash,marksHash,chatHash,audioSha,audioLen,pushedMarksJson,audioMissing,photosHash,pushedPhotosJson) VALUES ('r1',2,1,1,'a','b','c','d','e',NULL,0,'[]',0,'f','[]')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9)
        db.query("SELECT serverUpdatedAt FROM web_sync_state").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
    }

    @Test
    fun v9ToV10AddsAGeotagToPhotosWithoutTouchingTheRows() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        helper.runMigrationsAndValidate(name, 9, true, MIGRATION_7_8, MIGRATION_8_9).close()
        val db = helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10)
        db.query("SELECT caption, latitude, longitude, accuracyM, placeName FROM photos").use { c ->
            c.moveToFirst()
            assertEquals("Foto 1", c.getString(0))
            for (i in 1..4) assertTrue(c.isNull(i))
        }
        db.execSQL("UPDATE photos SET latitude=-7.1, longitude=110.2, accuracyM=8.5, placeName='Balai Desa'")
        db.query("SELECT latitude, placeName FROM photos").use { c -> c.moveToFirst(); assertEquals(-7.1, c.getDouble(0), 0.0001); assertEquals("Balai Desa", c.getString(1)) }
    }

    @Test
    fun v10ToV11AddsTheVideosTableAndKeepsTheRecordings() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        helper.runMigrationsAndValidate(name, 10, true, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).close()
        val db = helper.runMigrationsAndValidate(name, 11, true, MIGRATION_10_11)
        assertEquals(1, count(db, "recordings"))
        assertEquals(0, count(db, "videos"))
        db.execSQL("INSERT INTO videos (recordingId,filePath,takenAtSec,caption,createdAt,uuid,sizeBytes,mime) VALUES ('r1','/files/videos/a.mp4',12,'Lapangan',1,'u-1',123,'video/mp4')")
        assertEquals(1, count(db, "videos"))
        // a video goes with its recording (cascade)
        db.execSQL("PRAGMA foreign_keys=ON")                                       // Room switches this on in the app; the test helper does not
        db.execSQL("DELETE FROM recordings WHERE id='r1'")
        assertEquals(0, count(db, "videos"))
    }

    @Test
    fun theAppOpensAV7FileThroughRoomWithAllItsMigrations() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(ctx, IntelDesDatabase::class.java, name)
            .addMigrations(*IntelDesDatabase.ALL_MIGRATIONS)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(11, db.version)
            assertEquals(1, count(db, "recordings"))
        } finally {
            room.close()
        }
    }
}
