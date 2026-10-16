package app.sunosongwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongProgressTest {
    private fun progress(idea: Boolean = false, team: Boolean = false, draft: Boolean = false, refined: Boolean = false, sent: Boolean = false, results: Boolean = false) =
        SongProgress.of(idea, team, draft, refined, sent, results)

    @Test
    fun aNewSongWithNoTeamAsksForTheTeamAfterTheIdea() {
        assertEquals(SongStep.IDEA, progress().next)
        assertEquals(SongStep.TEAM, progress(idea = true).next)
        assertEquals(SongStep.DRAFT, progress(idea = true, team = true).next)
    }

    @Test
    fun sendingCountsAsRefinedSoHandWrittenSongsMoveOn() {
        val p = progress(idea = true, team = true, draft = true, sent = true)
        assertTrue(SongStep.REFINE in p.done)
        assertEquals(SongStep.RESULTS, p.next)
    }

    @Test
    fun everythingDoneMeansNoNextStep() {
        assertNull(progress(idea = true, team = true, draft = true, refined = true, sent = true, results = true).next)
    }

    @Test
    fun refinedMeansTheTeamChangedTheSong() {
        val draft = Variant(1, 1, "Draft", 0)
        fun version(step: String?) = Version(1, 1, 1, null, SongContent(), Author.USER, step, "", 0)
        assertFalse(SongProgress.refined(listOf(draft), listOf(version(null), version("Quick start"))))
        assertTrue(SongProgress.refined(listOf(draft), listOf(version("Implement"))))
        assertTrue(SongProgress.refined(listOf(draft, Variant(2, 1, "Consensus 2", 0)), emptyList()))
    }
}
