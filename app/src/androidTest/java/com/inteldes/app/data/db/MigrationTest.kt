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
    fun v7ToV9KeepsEveryRowAndMatchesTheSchema() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        val db = helper.runMigrationsAndValidate(name, 9, true, MIGRATION_7_8, MIGRATION_8_9)
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
    fun theAppOpensAV7FileThroughRoomWithAllItsMigrations() {
        helper.createDatabase(name, 7).apply { seedV7(this); close() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(ctx, IntelDesDatabase::class.java, name)
            .addMigrations(*IntelDesDatabase.ALL_MIGRATIONS)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            val db = room.openHelper.writableDatabase
            assertEquals(9, db.version)
            assertEquals(1, count(db, "recordings"))
        } finally {
            room.close()
        }
    }
}
