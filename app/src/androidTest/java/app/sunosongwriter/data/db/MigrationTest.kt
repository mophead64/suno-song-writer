package app.sunosongwriter.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema change from here on ships with a migration (docs/implementation-plan.md, iteration 2). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsBackendsAndAddsTheLibrary() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO backend (id, presetId, name, baseUrl, modelsUrl, auth, freeAccess, isFreeTier, rpm, rpd, tpm,
                  modelLimitsJson, limitScope, limitsFreeModelsOnly, dailyResetZone, streamUsage, dailyRemainingHeader,
                  extraHeadersJson, keyHint, createdAt)
                VALUES (1, 'ollama-local', 'Ollama', 'http://10.0.2.2:11434/v1', NULL, 'NONE', 'ALL', 0, NULL, NULL, NULL,
                  '[]', 'BACKEND', 0, 'UTC', 1, 0, '{}', NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name FROM backend").use { c ->
                c.moveToFirst()
                assertEquals("Ollama", c.getString(0))
            }
            db.execSQL("INSERT INTO song (id, folderId, title, artist, concept, createdAt, updatedAt) VALUES (1, NULL, 't', 'a', '', 0, 0)")
            db.execSQL("INSERT INTO song_search (rowid, title, artist, tags, concept, lyrics) VALUES (1, 't', 'a', '', '', 'rain on the roof')")
            db.query("SELECT rowid FROM song_search WHERE song_search MATCH 'rain*'").use { c ->
                assertEquals(1, c.count)
            }
        }
    }

    @Test
    fun migrate2To3AddsRelayColumnsAndSessionTables() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO backend (id, presetId, name, baseUrl, modelsUrl, auth, freeAccess, isFreeTier, rpm, rpd, tpm,
                  modelLimitsJson, limitScope, limitsFreeModelsOnly, dailyResetZone, streamUsage, dailyRemainingHeader,
                  extraHeadersJson, keyHint, createdAt)
                VALUES (1, 'groq', 'Groq', 'https://api.groq.com/openai/v1', NULL, 'BEARER', 'KEY_TIER', 1, 30, 1000, NULL,
                  '[]', 'MODEL', 0, 'UTC', 1, 1, '{}', NULL, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT kind, relayPackage FROM backend WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("OPENAI_COMPATIBLE", c.getString(0))
                assertEquals(true, c.isNull(1))
            }
            db.query("SELECT COUNT(*) FROM session_message").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun migrate3To4AddsMessageWarnings() {
        helper.createDatabase(DB, 3).close()

        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT warning FROM session_message").use { c -> assertEquals(0, c.count) }
        }
    }

    @Test
    fun migrate4To5AddsRoomColumns() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO song (id, folderId, title, artist, concept, createdAt, updatedAt) VALUES (1, NULL, 't', 'a', '', 0, 0)")
            db.execSQL("INSERT INTO variant (id, songId, name, createdAt) VALUES (1, 1, 'Draft', 0)")
            db.execSQL("INSERT INTO session (id, songId, variantId, type, fullTranscript, createdAt) VALUES (1, 1, 1, 'manual', 0, 0)")
        }

        helper.runMigrationsAndValidate(DB, 5, true).use { db ->
            db.query("SELECT configJson, stateJson FROM session WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("", c.getString(0))
                assertEquals("", c.getString(1))
            }
        }
    }

    @Test
    fun migrate5To6AddsSnapshots() {
        helper.createDatabase(DB, 5).use { db ->
            db.execSQL("INSERT INTO song (id, folderId, title, artist, concept, createdAt, updatedAt) VALUES (1, NULL, 't', 'a', '', 0, 0)")
        }

        helper.runMigrationsAndValidate(DB, 6, true).use { db ->
            db.execSQL("INSERT INTO snapshot (songId, versionId, versionLabel, styleText, lyricsText, settingsJson, sentAt) VALUES (1, 1, 'Draft v1', 's', 'l', '{}', 0)")
            db.query("SELECT COUNT(*) FROM snapshot").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
