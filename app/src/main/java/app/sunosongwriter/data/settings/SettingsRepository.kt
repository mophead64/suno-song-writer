package app.sunosongwriter.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.sunosongwriter.domain.lint.SunoLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    /** Spec 5.2: on by default for new installs. */
    val freeOnly: Flow<Boolean> = dataStore.data.map { it[FREE_ONLY] ?: true }

    suspend fun isFreeOnly(): Boolean = freeOnly.first()

    suspend fun setFreeOnly(enabled: Boolean) {
        dataStore.edit { it[FREE_ONLY] = enabled }
    }

    /** Suno's box limits, editable because Suno changes them (spec 5.9). */
    val sunoLimits: Flow<SunoLimits> = dataStore.data.map { p ->
        val d = SunoLimits()
        SunoLimits(p[STYLE_CHARS] ?: d.styleChars, p[LYRICS_CHARS] ?: d.lyricsChars, p[TAG_WORDS] ?: d.tagWords)
    }

    suspend fun setSunoLimits(limits: SunoLimits) {
        dataStore.edit {
            it[STYLE_CHARS] = limits.styleChars
            it[LYRICS_CHARS] = limits.lyricsChars
            it[TAG_WORDS] = limits.tagWords
        }
    }

    companion object {
        const val FILE_NAME = "settings"
        private val FREE_ONLY = booleanPreferencesKey("free_only")
        private val STYLE_CHARS = intPreferencesKey("suno_style_chars")
        private val LYRICS_CHARS = intPreferencesKey("suno_lyrics_chars")
        private val TAG_WORDS = intPreferencesKey("suno_tag_words")
    }
}
