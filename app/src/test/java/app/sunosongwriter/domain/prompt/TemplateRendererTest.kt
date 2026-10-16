package app.sunosongwriter.domain.prompt

import app.sunosongwriter.domain.workflow.SessionRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRendererTest {
    @Test
    fun replacesKnownVariablesAllowingSpacesAndCase() {
        val out = TemplateRenderer.render("{{title}} / {{ Artist }} / {{lyrics}}", TemplateContext(title = "Tin Roof", artist = "The Gutters", lyrics = "la"))

        assertEquals("Tin Roof / The Gutters / la", out)
    }

    @Test
    fun emptyValuesBecomeNoneAndUnknownVariablesStay() {
        val out = TemplateRenderer.render("{{concept}} {{mystery}}", TemplateContext())

        assertEquals("(none) {{mystery}}", out)
        assertEquals(listOf("mystery"), TemplateRenderer.unknownVariables("{{concept}} {{mystery}} {{ mystery }}"))
    }

    @Test
    fun builtInsUseOnlyKnownVariablesAndCoverEveryAction() {
        BuiltInTemplates.all.forEach { assertEquals(it.name, emptyList<String>(), TemplateRenderer.unknownVariables(it.body)) }
        assertEquals(SessionAction.entries.toSet(), BuiltInTemplates.all.map { it.action }.toSet())
        assertTrue(BuiltInTemplates.FINALISE.body.contains("{{guide}}"))
        assertTrue(BuiltInTemplates.IMPLEMENT.body.contains("{{suggestions}}"))
        assertTrue(BuiltInTemplates.REVIEW.body.contains("JSON"))
    }

    @Test
    fun truncationHeuristic() {
        assertTrue(SessionRunner.looksTruncated(readTokens = 4_000, estimatedTokens = 6_200))
        assertFalse(SessionRunner.looksTruncated(readTokens = 1_170, estimatedTokens = 1_250))
        assertFalse(SessionRunner.looksTruncated(readTokens = 300, estimatedTokens = 800)) // too short to judge
    }

    @Test
    fun templateKeys() {
        assertTrue(TemplateKey.builtIn("review").isBuiltIn)
        assertEquals(42L, TemplateKey.user(42).userId)
        assertFalse(TemplateKey.user(42).isBuiltIn)
    }
}
