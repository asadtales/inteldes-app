package com.inteldes.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private class RawSection(val title: String, val type: String, val payload: String)

/**
 * v4 -> v5: notes stop being four fixed tables (topics/decisions/questions/action_items) and
 * become one generic `note_sections` table shaped by a template; photos and user templates are
 * new. Existing notulensi are CONVERTED, not dropped: each one becomes the "Notulensi Standar"
 * template's five sections, and archived history snapshots are rewritten into the new
 * section-based snapshot format. (The branch this came from reset the database instead, which
 * would have deleted every existing recording — a real migration avoids that.)
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    private val gson = Gson()

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `templateId` TEXT NOT NULL DEFAULT 'builtin_standard'")
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `templateName` TEXT NOT NULL DEFAULT 'Notulensi Standar'")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_sections` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordingId` TEXT NOT NULL, " +
                "`orderIndex` INTEGER NOT NULL, `title` TEXT NOT NULL, `type` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_sections_recordingId` ON `note_sections` (`recordingId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `templates` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                "`sectionsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `photos` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recordingId` TEXT NOT NULL, " +
                "`filePath` TEXT NOT NULL, `takenAtSec` INTEGER, `caption` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_recordingId` ON `photos` (`recordingId`)")

        convertNotes(db)
        convertHistory(db)

        db.execSQL("DROP TABLE IF EXISTS `topics`")
        db.execSQL("DROP TABLE IF EXISTS `decisions`")
        db.execSQL("DROP TABLE IF EXISTS `questions`")
        db.execSQL("DROP TABLE IF EXISTS `action_items`")
    }

    private fun convertNotes(db: SupportSQLiteDatabase) {
        val recordings = mutableListOf<Pair<String, String>>()
        db.query("SELECT `id`, `summary` FROM `recordings` WHERE `summary` IS NOT NULL").use { c ->
            while (c.moveToNext()) recordings += c.getString(0) to c.getString(1)
        }
        for ((id, summary) in recordings) {
            val sections = standardSections(
                summary = summary,
                topics = readRows(db, "SELECT `timestampLabel`, `timestampSec`, `title`, `body` FROM `topics` WHERE `recordingId` = ? ORDER BY `orderIndex`", id) { c ->
                    JsonObject().apply {
                        addProperty("timestampLabel", c.getString(0)); addProperty("timestampSec", c.getInt(1))
                        addProperty("title", c.getString(2)); addProperty("body", c.getString(3))
                    }
                },
                decisions = readRows(db, "SELECT `text` FROM `decisions` WHERE `recordingId` = ? ORDER BY `orderIndex`", id) { c ->
                    com.google.gson.JsonPrimitive(c.getString(0))
                },
                questions = readRows(db, "SELECT `question`, `status`, `note` FROM `questions` WHERE `recordingId` = ? ORDER BY `orderIndex`", id) { c ->
                    JsonObject().apply {
                        addProperty("question", c.getString(0)); addProperty("status", c.getString(1)); addProperty("note", c.getString(2))
                    }
                },
                actions = readRows(db, "SELECT `task`, `pic`, `due`, `sourceTimestampLabel`, `done` FROM `action_items` WHERE `recordingId` = ? ORDER BY `orderIndex`", id) { c ->
                    JsonObject().apply {
                        addProperty("task", c.getString(0)); addProperty("pic", c.getString(1)); addProperty("due", c.getString(2))
                        addProperty("sourceTimestampLabel", c.getString(3)); addProperty("done", c.getInt(4) != 0)
                    }
                },
            )
            sections.forEachIndexed { index, s ->
                db.execSQL(
                    "INSERT INTO `note_sections` (`recordingId`, `orderIndex`, `title`, `type`, `payload`) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(id, index, s.title, s.type, s.payload),
                )
            }
        }
    }

    /** Old history blobs were `{summary, provider, topics, decisions, questions, actions}`; rewrite to `{summary, provider, sections}`. */
    private fun convertHistory(db: SupportSQLiteDatabase) {
        val rows = mutableListOf<Pair<Long, String>>()
        db.query("SELECT `id`, `notulensiJson` FROM `notulensi_history`").use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        for ((rowId, json) in rows) {
            val converted = runCatching {
                val old = JsonParser.parseString(json).asJsonObject
                val sections = standardSections(
                    summary = old.get("summary")?.asString.orEmpty(),
                    topics = old.getArray("topics"),
                    decisions = old.getArray("decisions").map { it.asJsonObject.get("text") },
                    questions = old.getArray("questions"),
                    actions = old.getArray("actions"),
                )
                JsonObject().apply {
                    addProperty("summary", old.get("summary")?.asString.orEmpty())
                    addProperty("provider", old.get("provider")?.asString.orEmpty())
                    addProperty("templateName", "Notulensi Standar")
                    add("sections", JsonArray().also { arr ->
                        sections.forEach { s ->
                            arr.add(JsonObject().apply {
                                addProperty("title", s.title); addProperty("type", s.type); addProperty("payload", s.payload)
                            })
                        }
                    })
                }.toString()
            }.getOrNull()
            if (converted != null) {
                db.execSQL("UPDATE `notulensi_history` SET `notulensiJson` = ? WHERE `id` = ?", arrayOf<Any>(converted, rowId))
            } else {
                // Unreadable snapshot: it can't be shown anyway, so drop it rather than crash later.
                db.execSQL("DELETE FROM `notulensi_history` WHERE `id` = ?", arrayOf<Any>(rowId))
            }
        }
    }

    private fun standardSections(
        summary: String,
        topics: List<JsonElement>,
        decisions: List<JsonElement>,
        questions: List<JsonElement>,
        actions: List<JsonElement>,
    ) = listOf(
        RawSection("Ringkasan", "PARAGRAPH", gson.toJson(summary)),
        RawSection("Poin bahasan", "TOPICS", toArray(topics)),
        RawSection("Keputusan", "DECISIONS", toArray(decisions)),
        RawSection("Poin pertanyaan", "QUESTIONS", toArray(questions)),
        RawSection("Action item", "ACTIONS", toArray(actions)),
    )

    private fun toArray(items: List<JsonElement>) = JsonArray().also { a -> items.forEach(a::add) }.toString()

    private fun JsonObject.getArray(name: String): List<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun readRows(db: SupportSQLiteDatabase, sql: String, recordingId: String, map: (android.database.Cursor) -> JsonElement): List<JsonElement> {
        val out = mutableListOf<JsonElement>()
        db.query(sql, arrayOf<Any>(recordingId)).use { c -> while (c.moveToNext()) out += map(c) }
        return out
    }
}
