package app.sunosongwriter

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.llm.ChatGateway
import app.sunosongwriter.data.llm.OpenAiCompatibleClient
import app.sunosongwriter.data.presets.PresetRepository
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.data.repo.LibraryTransfer
import app.sunosongwriter.data.repo.ParticipantRepository
import app.sunosongwriter.data.repo.RecipeRepository
import app.sunosongwriter.data.repo.RequestCountRepository
import app.sunosongwriter.data.repo.ResultRepository
import app.sunosongwriter.data.repo.SessionRepository
import app.sunosongwriter.data.repo.SnapshotRepository
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.data.repo.TemplateRepository
import app.sunosongwriter.data.repo.UsageRepository
import app.sunosongwriter.data.secure.KeystoreCipher
import app.sunosongwriter.data.secure.SecretStore
import app.sunosongwriter.data.settings.SettingsRepository
import app.sunosongwriter.domain.usage.QuotaTracker
import app.sunosongwriter.domain.usage.RequestPacer
import app.sunosongwriter.domain.workflow.SessionRunner
import app.sunosongwriter.domain.workflow.WritersRoom
import app.sunosongwriter.work.RoomNotifier
import app.sunosongwriter.work.RoomScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(SettingsRepository.FILE_NAME)
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore(SecretStore.FILE_NAME)

/**
 * Manual dependency container, created once in [App.onCreate].
 *
 * Repositories, clients and stores are added here as lazily created properties
 * as each iteration introduces them (see docs/implementation-plan.md).
 */
class AppContainer(val application: Application) {
    val clock: () -> Long = System::currentTimeMillis

    /** For work that outlives a screen, e.g. refreshing model lists on launch. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val database by lazy { AppDatabase.create(application) }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Local models can take a while to load before the first token arrives.
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    val llmClient by lazy { OpenAiCompatibleClient(http, json, clock) }

    val presets by lazy { PresetRepository(json) { application.assets.open("presets.json") } }

    val settings by lazy { SettingsRepository(application.settingsStore) }

    private val secrets by lazy { SecretStore(application.secretsStore, KeystoreCipher()) }

    val backends by lazy {
        BackendRepository(database.backendDao(), database.modelInfoDao(), secrets, llmClient, json, clock)
    }

    val participants by lazy { ParticipantRepository(database.participantDao(), json, clock) }

    val songs by lazy { SongRepository(database, json, clock) }

    val sessions by lazy { SessionRepository(database.sessionDao(), clock) }

    val templates by lazy { TemplateRepository(database.templateDao(), clock) }

    /** The Suno v6 prompting guide, bundled for the Finalise template's `{{guide}}`. */
    val guide: String by lazy { application.assets.open("suno-guide.txt").bufferedReader().use { it.readText() } }

    val runner by lazy {
        SessionRunner(appScope, gateway, songs, sessions, templates, participants, backends, guide = { guide }, nowMillis = clock, fixContext = results::fixContext)
    }

    /** Text shared into the app (relay replies, quick capture), waiting for the share screen. */
    val pendingShare = MutableStateFlow<String?>(null)

    val room by lazy { WritersRoom(runner, sessions, songs, json) }

    val roomScheduler by lazy { RoomScheduler(application) }

    val roomNotifier by lazy { RoomNotifier(application) }

    /** A Writers' Room to open, from a notification tap. */
    val pendingOpenRoom = MutableStateFlow<Long?>(null)

    /** Wakes a room's worker whenever one of its steps settles, e.g. a relay reply arriving (spec 5.2). */
    fun watchRooms() {
        appScope.launch {
            runner.settled.collect { sessionId ->
                if (sessions.get(sessionId)?.type == SessionRepository.ROOM) roomScheduler.kick(sessionId)
            }
        }
    }

    val snapshots by lazy { SnapshotRepository(database.snapshotDao(), json, clock) }

    val results by lazy { ResultRepository(database, snapshots, clock) }

    val recipes by lazy { RecipeRepository(database.recipeDao(), songs, json, clock) }

    val libraryTransfer by lazy { LibraryTransfer(database, songs, json, clock) }

    val requestCounts by lazy { RequestCountRepository(database.requestCountDao(), clock) }

    val quotas by lazy { QuotaTracker(clock) }

    val usage by lazy { UsageRepository(database.usageDao(), settings.spendLimits, clock) }

    private val pacer by lazy { RequestPacer(clock) }

    val gateway by lazy {
        ChatGateway(
            transport = llmClient,
            targets = backends,
            counter = requestCounts,
            pacer = pacer,
            quotas = quotas,
            freeOnly = settings::isFreeOnly,
            nowMillis = clock,
            ledger = usage,
            jsonMode = { b -> b.presetId?.let { presets.find(it)?.jsonMode } == true },
        )
    }
}
