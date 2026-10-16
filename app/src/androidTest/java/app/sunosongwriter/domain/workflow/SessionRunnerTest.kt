package app.sunosongwriter.domain.workflow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.llm.ChatEvent
import app.sunosongwriter.data.llm.ChatGateway
import app.sunosongwriter.data.llm.ChatMessage
import app.sunosongwriter.data.llm.ChatTransport
import app.sunosongwriter.data.llm.OpenAiCompatibleClient
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.data.repo.ParticipantRepository
import app.sunosongwriter.data.repo.RequestCountRepository
import app.sunosongwriter.data.repo.SessionRepository
import app.sunosongwriter.data.repo.SongRepository
import app.sunosongwriter.data.repo.TemplateRepository
import app.sunosongwriter.data.secure.KeystoreCipher
import app.sunosongwriter.data.secure.SecretStore
import app.sunosongwriter.domain.model.AuthStyle
import app.sunosongwriter.domain.model.AuthorType
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.review.Decision
import app.sunosongwriter.domain.usage.QuotaTracker
import app.sunosongwriter.domain.usage.RequestPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SessionRunnerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secretsFile = File(context.cacheDir, "runner-test-secrets.preferences_pb").also { it.delete() }

    /** Replies with the queued texts in order, and records what it was sent. */
    private class ScriptedModel : ChatTransport {
        val replies = ArrayDeque<String>()
        val sent = mutableListOf<List<ChatMessage>>()
        override fun stream(endpoint: app.sunosongwriter.data.llm.Endpoint, model: String, messages: List<ChatMessage>, options: app.sunosongwriter.data.llm.ChatOptions) =
            flowOf(ChatEvent.Delta(replies.removeFirst()), ChatEvent.Completed("stop", null)).also { sent += messages }
    }

    private val model = ScriptedModel()
    private val songs = SongRepository(db, json)
    private val sessions = SessionRepository(db.sessionDao())
    private val templates = TemplateRepository(db.templateDao())
    private val participants = ParticipantRepository(db.participantDao(), json)
    private val backends = BackendRepository(
        db.backendDao(), db.modelInfoDao(),
        SecretStore(PreferenceDataStoreFactory.create(scope = scope) { secretsFile }, KeystoreCipher("runner-test")),
        OpenAiCompatibleClient(OkHttpClient(), json), json,
    )
    private val gateway = ChatGateway(model, backends, RequestCountRepository(db.requestCountDao()), RequestPacer(System::currentTimeMillis), QuotaTracker(System::currentTimeMillis), freeOnly = { true })
    private val runner = SessionRunner(scope, gateway, songs, sessions, templates, participants, backends, guide = { "GUIDE TEXT" })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        secretsFile.delete()
    }

    private fun backend(kind: BackendKind = BackendKind.OPENAI_COMPATIBLE, pkg: String? = null) = Backend(
        id = 0, presetId = null, name = if (kind == BackendKind.RELAY) "Claude app (relay)" else "Ollama", baseUrl = "http://10.0.2.2:11434/v1",
        modelsUrl = null, auth = AuthStyle.NONE, freeAccess = FreeAccess.ALL, isFreeTier = false, limits = Limits(),
        modelLimits = emptyList(), limitScope = LimitScope.BACKEND, limitsFreeModelsOnly = false, dailyResetZone = "UTC",
        streamUsage = true, dailyRemainingHeader = false, extraHeaders = emptyMap(), keyHint = null, kind = kind, relayPackage = pkg,
    )

    private class Fixture(val songId: Long, val variantId: Long, val writer: Long, val relay: Long)

    private fun fixture(): Fixture = runBlocking {
        val apiBackend = backends.save(backend(), null)
        val relayBackend = backends.save(backend(BackendKind.RELAY, "com.anthropic.claude"), null)
        val writer = participants.save(Participant(0, "Llama", "🦙", null, ModelRef(apiBackend, "llama3.2"), emptyList()))
        val relay = participants.save(Participant(0, "Claude", "🎻", null, ModelRef(relayBackend, BackendRepository.RELAY_MODEL_ID), emptyList()))
        val songId = songs.createSong("Tin Roof", "The Gutters", "rain", listOf("pop-punk"), null)
        Fixture(songId, songs.observeVariants(songId).first().single().id, writer, relay)
    }

    private fun awaitSettled(messageId: Long): SessionMessage = runBlocking {
        withTimeout(10_000) {
            var m: SessionMessage
            do {
                m = sessions.message(messageId)!!
                if (!m.status.isActive) break
                kotlinx.coroutines.delay(50)
            } while (true)
            m
        }
    }

    @Test
    fun writeLyricsCreatesAVersionAuthoredByTheModel() = runBlocking {
        val f = fixture()
        model.replies += "LYRICS:\n[Verse]\nRain on the roof\nNOTE:\nfirst draft"

        val id = runner.start(RunRequest(f.songId, f.variantId, SessionAction.WRITE_LYRICS, f.writer))
        val message = awaitSettled(id)

        assertEquals(MessageStatus.DONE, message.status)
        val latest = songs.latest(f.variantId)!!
        assertEquals("[Verse]\nRain on the roof", latest.content.lyrics)
        assertEquals("first draft", latest.changeNote)
        assertEquals(AuthorType.MODEL, latest.author.type)
        assertTrue(latest.author.label.startsWith("Llama"))
        assertEquals(latest.id, message.producedVersionId)
        assertTrue(model.sent.single().single().content.contains("Tin Roof"))
    }

    @Test
    fun reviewSuggestionsThenImplementOnlyTheAcceptedOnes() = runBlocking {
        val f = fixture()
        model.replies += """{"verdict":"changes","summary":"ok","suggestions":[
            {"target":"lyrics","section":"Verse","change":"Add a second line about thunder","rationale":"build"},
            {"target":"style","section":"Vocals","change":"Use falsetto everywhere","rationale":"why not"}]}"""
        val review = awaitSettled(runner.start(RunRequest(f.songId, f.variantId, SessionAction.REVIEW, f.writer)))
        val suggestions = sessions.suggestions(review.sessionId)
        assertEquals(2, suggestions.size)

        sessions.decide(listOf(suggestions[0].id), Decision.ACCEPTED)
        sessions.decide(listOf(suggestions[1].id), Decision.REJECTED)
        model.replies += "STYLE:\nGenre: pop-punk\nLYRICS:\n[Verse]\nRain on the roof\nThunder rolls in\nNOTE:\nadded thunder"
        val implement = awaitSettled(runner.start(RunRequest(f.songId, f.variantId, SessionAction.IMPLEMENT, f.writer)))

        val prompt = model.sent.last().last().content
        assertTrue(prompt.contains("Add a second line about thunder"))
        assertFalse(prompt.contains("falsetto"))
        assertEquals(MessageStatus.DONE, implement.status)
        val after = sessions.suggestions(review.sessionId)
        assertEquals(implement.producedVersionId, after[0].implementedInVersionId)
        assertEquals(null, after[1].implementedInVersionId)
    }

    @Test
    fun implementWithNothingAcceptedRefusesToStart() = runBlocking {
        val f = fixture()
        val error = runCatching { runner.start(RunRequest(f.songId, f.variantId, SessionAction.IMPLEMENT, f.writer)) }.exceptionOrNull()
        assertTrue(error is RunException)
    }

    @Test
    fun unreadableReviewJsonIsRetriedOnceThenKeptAsRawText() = runBlocking {
        val f = fixture()
        model.replies += "The chorus is great but the verse drags."
        model.replies += """{"verdict":"changes","suggestions":[{"target":"lyrics","change":"Tighten the verse"}]}"""
        val retried = awaitSettled(runner.start(RunRequest(f.songId, f.variantId, SessionAction.REVIEW, f.writer)))
        assertEquals(1, sessions.suggestions(retried.sessionId).size)
        assertEquals(SessionRunner.JSON_RETRY, model.sent.last().last().content)

        model.replies += "still not json"
        model.replies += "nope"
        val failed = awaitSettled(runner.start(RunRequest(f.songId, f.variantId, SessionAction.REVIEW, f.writer)))
        assertTrue(failed.parseFailed)
        assertEquals(MessageStatus.DONE, failed.status)
    }

    @Test
    fun relayStepsWaitForThePastedReply() = runBlocking {
        val f = fixture()
        val id = runner.start(RunRequest(f.songId, f.variantId, SessionAction.REVIEW, f.relay))

        val waiting = sessions.message(id)!!
        assertEquals(MessageStatus.WAITING_RELAY, waiting.status)
        assertTrue(waiting.prompt.contains("JSON"))
        assertTrue(model.sent.isEmpty())
        assertEquals(listOf(id), sessions.observeWaitingRelay().first().map { it.message.id })

        runner.submitRelayReply(id, "```json\n{\"verdict\":\"approve\",\"suggestions\":[]}\n```")

        assertEquals(MessageStatus.DONE, sessions.message(id)!!.status)
        assertTrue(sessions.observeWaitingRelay().first().isEmpty())
    }

    @Test
    fun participantDefaultTemplateWinsOverEveryonesDefault() = runBlocking {
        val f = fixture()
        val everyone = templates.create(SessionAction.WRITE_LYRICS, "Everyone", "EVERYONE {{title}}")
        val mine = templates.create(SessionAction.WRITE_LYRICS, "Mine", "MINE {{title}}")
        templates.setDefault(SessionAction.WRITE_LYRICS, 0, everyone)
        templates.setDefault(SessionAction.WRITE_LYRICS, f.writer, mine)
        model.replies += "[Verse]\nla"

        awaitSettled(runner.start(RunRequest(f.songId, f.variantId, SessionAction.WRITE_LYRICS, f.writer)))

        assertEquals("MINE Tin Roof", model.sent.single().single().content)
    }
}
