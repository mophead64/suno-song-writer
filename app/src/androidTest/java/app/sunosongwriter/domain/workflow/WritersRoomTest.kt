package app.sunosongwriter.domain.workflow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sunosongwriter.data.db.AppDatabase
import app.sunosongwriter.data.llm.ChatEvent
import app.sunosongwriter.data.llm.ChatGateway
import app.sunosongwriter.data.llm.ChatMessage
import app.sunosongwriter.data.llm.ChatOptions
import app.sunosongwriter.data.llm.ChatTransport
import app.sunosongwriter.data.llm.Endpoint
import app.sunosongwriter.data.llm.LlmError
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
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.BackendKind
import app.sunosongwriter.domain.model.FreeAccess
import app.sunosongwriter.domain.model.LimitScope
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelRef
import app.sunosongwriter.domain.model.Participant
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WritersRoomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val secretsFile = File(context.cacheDir, "room-test-secrets.preferences_pb").also { it.delete() }

    /** Replies per model id, in order; a reply of null fails that request. */
    private class Script : ChatTransport {
        val replies = HashMap<String, ArrayDeque<String?>>()
        val calls = mutableListOf<Pair<String, String>>()
        fun queue(model: String, vararg texts: String?) = replies.getOrPut(model) { ArrayDeque() }.addAll(texts)
        override fun stream(endpoint: Endpoint, model: String, messages: List<ChatMessage>, options: ChatOptions) = run {
            calls += model to messages.last().content
            val reply = replies[model]?.removeFirstOrNull()
            if (reply == null) flowOf(ChatEvent.Failed(LlmError.Server(503, "overloaded")))
            else flowOf(ChatEvent.Delta(reply), ChatEvent.Usage(100, 50, null), ChatEvent.Completed("stop", null))
        }
    }

    private val script = Script()
    private val songs = SongRepository(db, json)
    private val sessions = SessionRepository(db.sessionDao())
    private val participants = ParticipantRepository(db.participantDao(), json)
    private val backends = BackendRepository(
        db.backendDao(), db.modelInfoDao(),
        SecretStore(PreferenceDataStoreFactory.create(scope = scope) { secretsFile }, KeystoreCipher("room-test")),
        OpenAiCompatibleClient(OkHttpClient(), json), json,
    )
    private val gateway = ChatGateway(script, backends, RequestCountRepository(db.requestCountDao()), RequestPacer(System::currentTimeMillis), QuotaTracker(System::currentTimeMillis), freeOnly = { true })
    private val runner = SessionRunner(scope, gateway, songs, sessions, TemplateRepository(db.templateDao()), participants, backends, guide = { "GUIDE" })
    private val room = WritersRoom(runner, sessions, songs, json)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        secretsFile.delete()
    }

    private fun backend(kind: BackendKind = BackendKind.OPENAI_COMPATIBLE) = Backend(
        id = 0, presetId = null, name = if (kind == BackendKind.RELAY) "Claude app" else "Local", baseUrl = "http://10.0.2.2:11434/v1",
        modelsUrl = null, auth = AuthStyle.NONE, freeAccess = FreeAccess.ALL, isFreeTier = false, limits = Limits(),
        modelLimits = emptyList(), limitScope = LimitScope.BACKEND, limitsFreeModelsOnly = false, dailyResetZone = "UTC",
        streamUsage = true, dailyRemainingHeader = false, extraHeaders = emptyMap(), keyHint = null, kind = kind, relayPackage = "com.anthropic.claude",
    )

    private inner class Team(val songId: Long, val draftId: Long, val writer: Long, val critic: Long, val relay: Long)

    private fun team(writerFallback: String? = null): Team = runBlocking {
        val api = backends.save(backend(), null)
        val relayBackend = backends.save(backend(BackendKind.RELAY), null)
        val writer = participants.save(Participant(0, "Writer", "W", null, ModelRef(api, "writer"), listOfNotNull(writerFallback?.let { ModelRef(api, it) })))
        val critic = participants.save(Participant(0, "Critic", "C", null, ModelRef(api, "critic"), emptyList()))
        val relay = participants.save(Participant(0, "Claude", "R", null, ModelRef(relayBackend, BackendRepository.RELAY_MODEL_ID), emptyList()))
        val songId = songs.createSong("Tin Roof", "The Gutters", "rain", emptyList(), null)
        Team(songId, songs.observeVariants(songId).first().single().id, writer, critic, relay)
    }

    /** What the worker does: advance, run the steps it asks for, repeat until it needs the user. */
    private fun drive(sessionId: Long): RoomStatus = runBlocking {
        var status: RoomStatus
        while (true) {
            status = room.advance(sessionId)
            if (status !is RoomStatus.Run) break
            status.messageIds.forEach { runner.execute(it) }
        }
        status
    }

    private fun state(sessionId: Long) = runBlocking { room.state(sessions.get(sessionId)!!) }

    private val lyrics = "LYRICS:\n[Verse]\nRain on the roof\nNOTE:\nfirst draft"
    private val style = "STYLE:\nGenre: pop-punk\nVocals: gritty\nNOTE:\nstyle"
    private val changes = """{"verdict":"changes","suggestions":[{"target":"lyrics","section":"Verse","change":"Add thunder"}]}"""
    private val approve = """{"verdict":"approve","suggestions":[]}"""
    private val implemented = "LYRICS:\n[Verse]\nRain on the roof\nThunder too\nNOTE:\nadded thunder"
    private val finalised = "STYLE:\nGenre: pop-punk, easycore\nProduction: close-mic vocals\nLYRICS:\n[Verse]\nRain on the roof\nThunder too\n[Instrumental Outro]\n[End]\nNOTE:\nfinal"

    @Test
    fun fullRunProducesConsensusAndFrameworkRefinedVariants() = runBlocking {
        val t = team()
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.writer, t.critic), t.writer, proposeStyles = true, t.writer, t.writer, roundCap = 3))
        script.queue("writer", lyrics, style, changes, implemented, approve, finalised)
        script.queue("critic", style, approve, approve)

        // Lyrics, then both propose styles, then the chair picks.
        assertEquals(RoomStatus.WaitingForChair, drive(id))
        assertEquals(RoomStage.STYLE_PICK, state(id).stage)
        val proposals = sessions.messages(id).filter { it.proposal }
        assertEquals(2, proposals.size)
        room.pickStyle(id, proposals.last().id)

        // Round 1: the writer wants changes; the chair accepts them.
        assertEquals(RoomStatus.WaitingForChair, drive(id))
        assertEquals(RoomStage.REVIEW_DECISION, state(id).stage)
        val suggestion = sessions.suggestions(id).single()
        sessions.decide(listOf(suggestion.id), Decision.ACCEPTED)
        room.implementAccepted(id)

        // Implement, round 2: everyone approves, so it finalises and finishes.
        assertEquals(RoomStatus.Over, drive(id))
        val final = state(id)
        assertEquals(RoomStage.DONE, final.stage)
        assertEquals(2, final.round)
        assertTrue(final.endedBecause!!.contains("approved"))

        val variants = songs.observeVariants(t.songId).first().map { it.name }
        assertEquals(listOf("Draft", "Consensus", "Framework-refined"), variants)
        val consensus = songs.latest(final.consensusVariantId)!!.content
        assertEquals("[Verse]\nRain on the roof\nThunder too", consensus.lyrics)
        assertEquals("pop-punk", consensus.style[app.sunosongwriter.domain.model.StyleSection.GENRE])
        val refined = songs.latest(final.refinedVariantId!!)!!.content
        assertTrue(refined.lyrics.endsWith("[End]"))
        // The Draft the room started from is untouched.
        assertEquals("", songs.latest(t.draftId)!!.content.lyrics)
    }

    @Test
    fun roundCapEndsTheDebate() = runBlocking {
        val t = team()
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.critic), lyricistId = null, proposeStyles = false, t.writer, finaliserId = null, roundCap = 1))
        script.queue("critic", changes)
        script.queue("writer", implemented)

        drive(id)
        sessions.decide(sessions.suggestions(id).map { it.id }, Decision.ACCEPTED)
        room.implementAccepted(id)

        assertEquals(RoomStatus.Over, drive(id))
        assertTrue(state(id).endedBecause!!.contains("cap of 1"))
        assertEquals(null, state(id).refinedVariantId) // no finaliser
    }

    @Test
    fun relayReviewerMakesTheRoomWaitUntilTheReplyArrives() = runBlocking {
        val t = team()
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.critic, t.relay), lyricistId = null, proposeStyles = false, t.writer, finaliserId = null))
        script.queue("critic", approve)

        assertEquals(RoomStatus.WaitingForRelay, drive(id))
        val waiting = sessions.messages(id).single { it.status == MessageStatus.WAITING_RELAY }
        runner.submitRelayReply(waiting.id, approve)

        assertEquals(RoomStatus.Over, drive(id)) // both approved: no finaliser, so done
    }

    @Test
    fun aFailedStepPausesTheRoomAndResumeRetriesIt() = runBlocking {
        val t = team()
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.critic), lyricistId = null, proposeStyles = false, t.writer, finaliserId = null))
        script.queue("critic", null) // fails

        val paused = drive(id)
        assertTrue(paused is RoomStatus.Paused)
        assertTrue((paused as RoomStatus.Paused).pause.reason.contains("Critic"))

        script.queue("critic", approve)
        room.resume(id)
        assertEquals(RoomStatus.Over, drive(id))
    }

    @Test
    fun stepsCutOffByProcessDeathRunAgain() = runBlocking {
        val t = team()
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.critic), lyricistId = null, proposeStyles = false, t.writer, finaliserId = null))
        val first = room.advance(id) as RoomStatus.Run
        // Simulate the process dying mid-stream.
        sessions.update(first.messageIds.single()) { it.copy(status = MessageStatus.STREAMING.name, content = "half a rep") }

        // A new worker resets what it finds mid-flight, then carries on.
        assertEquals(1, sessions.resetInterrupted(id))
        script.queue("critic", approve)
        assertEquals(RoomStatus.Over, drive(id))
        assertEquals(MessageStatus.DONE, sessions.message(first.messageIds.single())!!.status)
    }

    @Test
    fun aFailingModelFallsBackToTheNextAndSaysSo() = runBlocking {
        val t = team(writerFallback = "spare")
        val id = room.create(t.songId, t.draftId, RoomConfig(listOf(t.critic), t.writer, proposeStyles = false, t.writer, finaliserId = null))
        script.queue("writer", null) // primary fails
        script.queue("spare", lyrics)
        script.queue("critic", approve)

        assertEquals(RoomStatus.Over, drive(id))
        val lyricsStep = sessions.messages(id).first()
        assertTrue(lyricsStep.modelLabel!!.startsWith("spare"))
        assertTrue(lyricsStep.warning!!.contains("fallback"))
        assertEquals(listOf("writer", "spare", "critic"), script.calls.map { it.first })
    }

    private val detailedStyle = "STYLE:\nGenre: pop-punk\nVocals: gritty\nDrums: fast punk beat\nProduction: close-mic\nNOTE:\ndetailed"
    private val critique = """{"verdict":"changes","suggestions":[{"target":"lyrics","section":"Verse","change":"Add thunder"},{"target":"style","section":"Drums","change":"Use blast beats everywhere"}]}"""
    private val agree = """{"verdict":"changes","suggestions":[{"target":"lyrics","section":"Verse","change":"Add some thunder"}]}"""

    @Test
    fun automaticChairRunsWithoutAnyInput() = runBlocking {
        val t = team()
        val config = RoomConfig(listOf(t.writer, t.critic), lyricistId = null, proposeStyles = true, t.writer, finaliserId = t.writer, roundCap = 2, chair = ChairMode.AUTO, autoAcceptBackers = 2)
        val id = room.create(t.songId, t.draftId, config)
        script.queue("writer", style, critique, implemented, approve, finalised)
        script.queue("critic", detailedStyle, agree, approve)

        assertEquals(RoomStatus.Over, drive(id))

        val final = state(id)
        assertEquals(RoomStage.DONE, final.stage)
        val consensus = songs.latest(final.consensusVariantId)!!.content
        // The critic's style was the more detailed proposal.
        assertEquals("fast punk beat", consensus.style[app.sunosongwriter.domain.model.StyleSection.DRUMS])
        // Thunder was backed by both reviewers; blast beats by one, so rejected.
        val decisions = sessions.suggestions(id).associate { it.change to it.decision }
        assertEquals(Decision.ACCEPTED, decisions["Add thunder"])
        assertEquals(Decision.REJECTED, decisions["Use blast beats everywhere"])
        assertTrue(consensus.lyrics.contains("Thunder too"))
        assertTrue(final.refinedVariantId != null)
    }

    @Test
    fun aParticipantChairsAndTheUserCanTakeOver() = runBlocking {
        val t = team()
        val config = RoomConfig(listOf(t.critic), lyricistId = null, proposeStyles = true, t.writer, finaliserId = null, roundCap = 2, chair = ChairMode.MODEL, chairId = t.writer)
        val id = room.create(t.songId, t.draftId, config)
        script.queue("critic", detailedStyle, critique, agree)
        // The chair picks proposal 1, accepts change 1 of 2, and in round 2 gives a reply it can't be held to.
        script.queue("writer", "```json\n{\"pick\": 1, \"reason\": \"detailed\"}\n```", """{"accept": [1], "reason": "thunder fits"}""", implemented, "I think they're all fine")

        assertEquals(RoomStatus.WaitingForChair, drive(id))

        val chairSteps = sessions.messages(id).filter { it.action == app.sunosongwriter.domain.prompt.SessionAction.CHAIR }
        assertEquals(3, chairSteps.size)
        assertTrue(chairSteps.first().prompt.contains("1. From Critic"))
        assertTrue(chairSteps[1].prompt.contains("Add thunder"))
        assertTrue(chairSteps.last().error!!.contains("Decide yourself"))
        val decisions = sessions.suggestions(id).associate { it.change to it.decision }
        assertEquals(Decision.ACCEPTED, decisions["Add thunder"])
        assertEquals(Decision.REJECTED, decisions["Use blast beats everywhere"])
        assertEquals(RoomStage.REVIEW_DECISION, state(id).stage)
        assertEquals(2, state(id).round)

        room.setChair(id, ChairMode.YOU)
        assertEquals(ChairMode.YOU, room.config(sessions.get(id)!!).chair)
        room.implementAccepted(id)
        assertEquals(RoomStatus.Over, drive(id))
    }
}
