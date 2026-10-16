package app.sunosongwriter.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.sunosongwriter.domain.lint.SunoLimits
import app.sunosongwriter.domain.usage.SpendLimits
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

    /** Spend limits for paid models (spec 5.17). */
    val spendLimits: Flow<SpendLimits> = dataStore.data.map { p ->
        SpendLimits(p[MONTHLY_LIMIT], p[SESSION_LIMIT], p[WARN_PERCENT] ?: SpendLimits().warnPercent)
    }

    suspend fun setSpendLimits(limits: SpendLimits) {
        dataStore.edit {
            if (limits.monthlyUsd != null) it[MONTHLY_LIMIT] = limits.monthlyUsd else it.remove(MONTHLY_LIMIT)
            if (limits.sessionUsd != null) it[SESSION_LIMIT] = limits.sessionUsd else it.remove(SESSION_LIMIT)
            it[WARN_PERCENT] = limits.warnPercent
        }
    }

    /** Getting started opens by itself once, on first launch. */
    val gettingStartedShown: Flow<Boolean> = dataStore.data.map { it[GETTING_STARTED_SHOWN] ?: false }

    suspend fun setGettingStartedShown() {
        dataStore.edit { it[GETTING_STARTED_SHOWN] = true }
    }

    companion object {
        const val FILE_NAME = "settings"
        private val FREE_ONLY = booleanPreferencesKey("free_only")
        private val STYLE_CHARS = intPreferencesKey("suno_style_chars")
        private val LYRICS_CHARS = intPreferencesKey("suno_lyrics_chars")
        private val TAG_WORDS = intPreferencesKey("suno_tag_words")
        private val GETTING_STARTED_SHOWN = booleanPreferencesKey("getting_started_shown")
        private val MONTHLY_LIMIT = doublePreferencesKey("spend_monthly_usd")
        private val SESSION_LIMIT = doublePreferencesKey("spend_session_usd")
        private val WARN_PERCENT = intPreferencesKey("spend_warn_percent")
    }
}
